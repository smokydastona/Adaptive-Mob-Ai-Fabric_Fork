package com.minecraft.gancity;

import com.minecraft.gancity.ai.MobBehaviorAI;
import com.minecraft.gancity.ai.VillagerDialogueAI;
import com.minecraft.gancity.compat.ModCompatibility;
import com.minecraft.gancity.config.ModdedMobTacticMappingStore;
import com.minecraft.gancity.config.PerMobAiDefaultsStore;
import com.minecraft.gancity.config.PlayerMobLoadoutStore;
import com.minecraft.gancity.mca.MCAIntegration;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

@SuppressWarnings({"null"})
public class GANCityMod {
    public static final String MODID = "adaptivemobai";
    public static final Logger LOGGER = LogUtils.getLogger();  // Changed to public for mixin access
    
    // DIAGNOSTIC: Static initializer runs FIRST - if this doesn't log, class loading itself failed
    static {
        System.out.println("=== MCA AI Enhanced: Static initialization START ===");
        System.out.println("=== If you see this but no 'FINISH', class loading failed ===");
        LOGGER.info("=== MCA AI Enhanced: Static initialization START ===");
    }
    
    private static MobBehaviorAI mobBehaviorAI;
    private static VillagerDialogueAI villagerDialogueAI;
    private static boolean federationInitialized = false;

    // =====================================================
    // Config (minimal TOML parsing; no ForgeConfigSpec yet)
    // =====================================================
    private static final String CONFIG_FILE_NAME = "adaptivemobai-common.toml";
    private static final String DEFAULT_CLOUDFLARE_ENDPOINT = "https://mca-ai-tactics-api.mc-ai-datcol.workers.dev";

    private static volatile boolean configLoaded = false;
    private static volatile boolean safeMode = false;
    private static volatile boolean enableMobAI = true;
    private static volatile boolean enableVillagerDialogue = true;
    private static volatile boolean enableLearning = true;
    private static volatile float aiDifficulty = 1.0f;

    private static volatile boolean enableCrossMobLearning = true;
    private static volatile float crossMobRewardMultiplier = 3.0f;
    private static volatile boolean enableContextualDifficulty = true;

    private static volatile boolean enableFederatedLearning = true;
    private static volatile String cloudApiEndpoint = DEFAULT_CLOUDFLARE_ENDPOINT;
    private static volatile String cloudApiKey = "";

    private static volatile boolean tierProgressionEnabled = true;
    private static volatile boolean visualTierIndicators = true;
    private static volatile float expRateMultiplier = 1.0f;
    private static volatile boolean syncTiersWithFederation = true;

    // Global (server-wide) mob weapon loadouts from config
    private static volatile Map<String, List<String>> globalMobWeaponLoadouts = Map.of();
    private static volatile String defaultBowArrowItemId = "minecraft:arrow";
    private static volatile Map<String, String> bowArrowOverrides = Map.of();
    
    // Auto-save tracking (10 minutes = 12000 ticks)
    private static final int AUTO_SAVE_INTERVAL_TICKS = 12000;
    private static int tickCounter = 0;
    private static long lastSaveTime = 0;

    private static boolean isModLoaded(String modId) {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = fabricLoaderClass.getMethod("getInstance").invoke(null);
            Object result = fabricLoaderClass.getMethod("isModLoaded", String.class).invoke(loader, modId);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.nio.file.Path getConfigDir() {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = fabricLoaderClass.getMethod("getInstance").invoke(null);
            Object configDir = fabricLoaderClass.getMethod("getConfigDir").invoke(loader);
            if (configDir instanceof java.nio.file.Path p) {
                return p;
            }
        } catch (Throwable ignored) {
        }

        return java.nio.file.Paths.get("config");
    }

    /**
     * One-time mod bootstrap for Fabric.
     */
    public static void bootstrap() {
        LOGGER.info("MCA AI Enhanced - Initializing (Fabric)...");

        try {
            ensureDefaultConfigFilesExist();

            // Configure DJL cache (safe - just system properties, no classloading)
            String gameDir = System.getProperty("user.dir");
            String djlCachePath = gameDir + "/libraries/ai.djl";
            System.setProperty("DJL_CACHE_DIR", djlCachePath);
            System.setProperty("ai.djl.offline", "false");
            LOGGER.info("DJL cache configured: {}", djlCachePath);

            ModCompatibility.init();

            boolean mcaLoaded = isModLoaded("mca");
            MCAIntegration.setMCALoaded(mcaLoaded);

            if (mcaLoaded) {
                LOGGER.info("MCA AI Enhanced - MCA Reborn detected! Enhanced villager AI enabled.");
            } else {
                LOGGER.warn("MCA AI Enhanced - MCA Reborn not found. Villager dialogue features disabled.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize MCA AI Enhanced: {}", e.getMessage(), e);
        }
    }

    private static void ensureDefaultConfigFilesExist() {
        try {
            Path configDir = getConfigDir();
            Files.createDirectories(configDir);

            Path commonConfig = configDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(commonConfig)) {
                try (InputStream in = GANCityMod.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
                    if (in == null) {
                        LOGGER.warn("Default config resource {} was not found in the JAR", CONFIG_FILE_NAME);
                    } else {
                        Files.copy(in, commonConfig, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Created default config: {}", commonConfig.toAbsolutePath());
                    }
                }
            }

            PlayerMobLoadoutStore.ensureFileExists();
            ModdedMobTacticMappingStore.ensureFileExists();
            PerMobAiDefaultsStore.ensureFileExists();
        } catch (Exception e) {
            LOGGER.warn("Failed to ensure default config files exist: {}", e.toString());
        }
    }

    public static void onServerStarting() {
        LOGGER.info("MCA AI Enhanced - Server starting with AI enhancements");
        initFederationIfNeeded();
    }
    
    /**
     * Lazy initialization of federation - can be called from anywhere
     * Safe to call multiple times (idempotent)
     */
    public static void initFederationIfNeeded() {
        if (!federationInitialized) {
            federationInitialized = true;
            try {
                initializeFederatedLearning();
            } catch (Exception e) {
                LOGGER.error("Failed to initialize federation, continuing without it: {}", e.getMessage());
            }
        }
    }
    
    public static void onServerStopping() {
        LOGGER.info("MCA AI Enhanced - Server stopping, saving ML models...");
        
        if (mobBehaviorAI != null) {
            mobBehaviorAI.saveModel();
        }
    }
    
    public static void onServerTick(MinecraftServer server) {
        try {
            tickCounter++;
            
            // Auto-save every 10 minutes (12000 ticks)
            if (tickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
                tickCounter = 0;
                performAutoSave();
            }
        } catch (Exception e) {
            LOGGER.error("Exception in server tick: {}", e.getMessage());
        }
    }
    
    private static void performAutoSave() {
        if (mobBehaviorAI == null) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long timeSinceLastSave = (currentTime - lastSaveTime) / 1000; // seconds
        
        LOGGER.info("═══════════════════════════════════════════════════════");
        LOGGER.info("[AUTO-SAVE] Starting periodic save (last save: {}s ago)", timeSinceLastSave);
        LOGGER.info("═══════════════════════════════════════════════════════");
        
        try {
            // 1. Save models locally
            LOGGER.info("[AUTO-SAVE] Step 1/2: Saving ML models locally...");
            mobBehaviorAI.saveModel();
            LOGGER.info("[AUTO-SAVE] ✓ Local models saved");
            
            // 2. Sync with Cloudflare (upload + download)
            LOGGER.info("[AUTO-SAVE] Step 2/2: Syncing with Cloudflare...");
            mobBehaviorAI.syncWithCloudflare();
            LOGGER.info("[AUTO-SAVE] ✓ Cloudflare sync completed");
            
            lastSaveTime = currentTime;
            LOGGER.info("═══════════════════════════════════════════════════════");
            LOGGER.info("[AUTO-SAVE] ✓ All operations completed successfully!");
            LOGGER.info("═══════════════════════════════════════════════════════");
        } catch (Exception e) {
            LOGGER.error("═══════════════════════════════════════════════════════");
            LOGGER.error("[AUTO-SAVE] ✗ Failed: {}", e.getMessage());
            LOGGER.error("═══════════════════════════════════════════════════════");
        }
    }

    public static MobBehaviorAI getMobBehaviorAI() {
        loadConfigIfNeeded();

        // SAFE MODE / DISABLE: Skip AI initialization entirely
        if (safeMode || !enableMobAI) {
            LOGGER.warn("⚠️ SAFE MODE ENABLED - All ML/AI features disabled");
            return null;  // Mixin will skip AI enhancement if null
        }
        
        if (mobBehaviorAI == null) {
            synchronized (GANCityMod.class) {
                if (mobBehaviorAI == null) {
                    LOGGER.info("Lazy-initializing MobBehaviorAI...");
                    mobBehaviorAI = new MobBehaviorAI();

                    // Apply config (defaults are ON; user can disable in config)
                    try {
                        mobBehaviorAI.setDifficultyMultiplier(aiDifficulty);
                        mobBehaviorAI.setLearningEnabled(enableLearning);
                    } catch (Exception e) {
                        LOGGER.warn("Could not apply core AI config: {}", e.getMessage());
                    }
                    
                    // Load cross-mob learning configuration (default enabled)
                    try {
                        mobBehaviorAI.setCrossMobLearning(enableCrossMobLearning, crossMobRewardMultiplier);
                    } catch (Exception e) {
                        LOGGER.warn("Could not enable cross-mob learning: {}", e.getMessage());
                    }
                    
                    // Load contextual difficulty configuration (default enabled)
                    try {
                        mobBehaviorAI.setContextualDifficulty(enableContextualDifficulty);
                    } catch (Exception e) {
                        LOGGER.warn("Could not enable contextual difficulty: {}", e.getMessage());
                    }
                }
            }
        }
        return mobBehaviorAI;
    }
    
    public static VillagerDialogueAI getVillagerDialogueAI() {
        loadConfigIfNeeded();

        if (safeMode || !enableVillagerDialogue) {
            return null;
        }
        if (villagerDialogueAI == null) {
            synchronized (GANCityMod.class) {
                if (villagerDialogueAI == null) {
                    LOGGER.info("Lazy-initializing VillagerDialogueAI...");
                    villagerDialogueAI = new VillagerDialogueAI();
                }
            }
        }
        return villagerDialogueAI;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
    
    /**
     * Initialize federated learning from config
     */
    private static void initializeFederatedLearning() {
        try {
            loadConfigIfNeeded();

            if (safeMode || !enableMobAI) {
                LOGGER.info("Federation skipped (safe mode / mob AI disabled)");
                return;
            }

            MobBehaviorAI ai = getMobBehaviorAI();
            if (ai == null) {
                LOGGER.info("Federation skipped (AI not initialized)");
                return;
            }

            if (enableFederatedLearning && cloudApiEndpoint != null && !cloudApiEndpoint.isEmpty()) {
                LOGGER.info("Enabling federated learning (Cloudflare only)...");
                LOGGER.info("  Cloud API: {}", cloudApiEndpoint);

                ai.enableFederatedLearning(null, cloudApiEndpoint, cloudApiKey == null || cloudApiKey.isEmpty() ? null : cloudApiKey);

                LOGGER.info("Testing Cloudflare Worker connection...");
                boolean connected = ai.testCloudflareConnection();
                if (connected) {
                    LOGGER.info("✓ Cloudflare Worker connected successfully!");
                } else {
                    LOGGER.warn("⚠ Cloudflare Worker connection failed - running in offline mode");
                }
            } else {
                LOGGER.info("Federated learning disabled in config");
            }

            // Configure HNN-inspired tier progression system
            LOGGER.info("Configuring AI tier progression system...");
            ai.setTierSystemEnabled(tierProgressionEnabled);
            ai.setVisualTierIndicators(visualTierIndicators);

            if (tierProgressionEnabled) {
                LOGGER.info("✓ AI Tier Progression ENABLED");
                LOGGER.info("  Visual Indicators: {}", visualTierIndicators ? "ON" : "OFF");
                LOGGER.info("  Experience Rate: {}x", expRateMultiplier);
                LOGGER.info("  Federation Sync: {}", syncTiersWithFederation ? "ON" : "OFF");
                LOGGER.info("  Tiers: UNTRAINED → LEARNING → TRAINED → EXPERT → MASTER");
            } else {
                LOGGER.info("✗ AI Tier Progression DISABLED - All mobs use baseline difficulty");
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize federated learning: {}", e.getMessage());
        }
    }

    private static void loadConfigIfNeeded() {
        if (configLoaded) {
            return;
        }
        synchronized (GANCityMod.class) {
            if (configLoaded) {
                return;
            }
            try {
                java.nio.file.Path configPath = getConfigDir().resolve(CONFIG_FILE_NAME);
                if (!java.nio.file.Files.exists(configPath)) {
                    LOGGER.info("Config file not found, creating default config...");
                    createDefaultConfigFromResources(configPath);
                }

                java.util.Map<String, String> kv = parseTomlKeyValues(configPath);

                safeMode = parseBoolean(kv, "safeMode", false);
                enableMobAI = parseBoolean(kv, "enableMobAI", true);
                enableVillagerDialogue = parseBoolean(kv, "enableVillagerDialogue", true);
                enableLearning = parseBoolean(kv, "enableLearning", true);
                aiDifficulty = parseFloat(kv, "aiDifficulty", 1.0f);

                enableCrossMobLearning = parseBoolean(kv, "enableCrossMobLearning", true);
                crossMobRewardMultiplier = parseFloat(kv, "crossMobRewardMultiplier", 3.0f);
                enableContextualDifficulty = parseBoolean(kv, "enableContextualDifficulty", true);

                enableFederatedLearning = parseBoolean(kv, "enableFederatedLearning", true);
                cloudApiEndpoint = parseString(kv, "cloudApiEndpoint", DEFAULT_CLOUDFLARE_ENDPOINT);
                if (cloudApiEndpoint == null || cloudApiEndpoint.isEmpty()) {
                    cloudApiEndpoint = DEFAULT_CLOUDFLARE_ENDPOINT;
                }
                cloudApiKey = parseString(kv, "cloudApiKey", "");

                tierProgressionEnabled = parseBoolean(kv, "enableTierProgression", true);
                visualTierIndicators = parseBoolean(kv, "enableVisualTierIndicators", true);
                expRateMultiplier = parseFloat(kv, "experienceRateMultiplier", 1.0f);
                syncTiersWithFederation = parseBoolean(kv, "syncTiersWithFederation", true);

                // Loadouts (list-of-strings)
                globalMobWeaponLoadouts = parseMobWeaponLoadouts(parseStringList(kv, "mobWeaponLoadouts"), 5);
                defaultBowArrowItemId = normalizeItemId(parseString(kv, "defaultBowArrowItem", "minecraft:arrow"), "minecraft:arrow", true);
                bowArrowOverrides = parseKeyValueMap(parseStringList(kv, "mobBowArrowOverrides"));

                configLoaded = true;
            } catch (Exception e) {
                // Fail open with safe defaults (ON) so players have zero setup.
                LOGGER.warn("Failed to load config; using built-in defaults: {}", e.getMessage());
                configLoaded = true;
            }
        }
    }

    /**
     * Force a re-read of the TOML on disk so in-game config UI changes take effect immediately.
     */
    public static void reloadConfigFromDisk() {
        synchronized (GANCityMod.class) {
            configLoaded = false;
        }
        loadConfigIfNeeded();
    }

    public static ItemStack chooseConfiguredWeaponForMob(String mobTypeId, Random random) {
        loadConfigIfNeeded();
        if (mobTypeId == null || mobTypeId.isBlank()) {
            return null;
        }

        List<String> options = globalMobWeaponLoadouts.get(mobTypeId);
        if (options == null || options.isEmpty()) {
            return null;
        }

        String selected = options.get(random.nextInt(options.size()));
        if (selected == null) {
            return null;
        }

        selected = selected.trim();
        if (selected.isEmpty()) {
            return null;
        }

        if (selected.equalsIgnoreCase("none")) {
            return ItemStack.EMPTY;
        }

        ResourceLocation id = safeResourceLocation(selected);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return null;
        }
        return new ItemStack(item);
    }

    public static ItemStack getConfiguredArrowStackForMob(String mobTypeId) {
        loadConfigIfNeeded();

        String arrowId = null;
        if (mobTypeId != null && !mobTypeId.isBlank()) {
            arrowId = bowArrowOverrides.get(mobTypeId);
        }
        if (arrowId == null || arrowId.isBlank()) {
            arrowId = defaultBowArrowItemId;
        }

        arrowId = normalizeItemId(arrowId, "minecraft:arrow", true);
        if (arrowId == null || arrowId.isBlank() || arrowId.equalsIgnoreCase("none")) {
            return ItemStack.EMPTY;
        }

        ResourceLocation id = safeResourceLocation(arrowId);
        if (id == null) {
            return ItemStack.EMPTY;
        }

        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }

        return new ItemStack(item, 64);
    }

    private static List<String> parseStringList(Map<String, String> kv, String key) {
        String value = kv.get(key);
        if (value == null) {
            return List.of();
        }

        String v = value.trim();
        if (v.isEmpty()) {
            return List.of();
        }

        // Accept a single string value
        if (!v.startsWith("[") || !v.endsWith("]")) {
            String single = stripQuotes(v).trim();
            return single.isEmpty() ? List.of() : List.of(single);
        }

        String inner = v.substring(1, v.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                inQuotes = true;
                quoteChar = c;
                continue;
            }

            if (c == ',') {
                String token = stripQuotes(current.toString()).trim();
                if (!token.isEmpty()) {
                    out.add(token);
                }
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        String token = stripQuotes(current.toString()).trim();
        if (!token.isEmpty()) {
            out.add(token);
        }

        return out;
    }

    private static Map<String, List<String>> parseMobWeaponLoadouts(List<? extends String> rawEntries, int maxWeaponsPerMob) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> result = new HashMap<>();
        for (String entry : rawEntries) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq >= trimmed.length() - 1) {
                continue;
            }

            String mobId = trimmed.substring(0, eq).trim();
            String weaponsCsv = trimmed.substring(eq + 1).trim();
            if (mobId.isEmpty() || weaponsCsv.isEmpty()) {
                continue;
            }

            String[] parts = weaponsCsv.split(",");
            List<String> weapons = new ArrayList<>();
            for (String part : parts) {
                if (weapons.size() >= maxWeaponsPerMob) {
                    break;
                }
                if (part == null) {
                    continue;
                }
                String weaponId = part.trim();
                if (weaponId.isEmpty()) {
                    continue;
                }

                if (weaponId.equalsIgnoreCase("none")) {
                    weapons.add("none");
                    continue;
                }

                String normalized = normalizeItemId(weaponId, null, false);
                if (normalized != null) {
                    weapons.add(normalized);
                }
            }

            if (!weapons.isEmpty()) {
                result.put(mobId, Collections.unmodifiableList(weapons));
            }
        }

        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    private static Map<String, String> parseKeyValueMap(List<? extends String> rawEntries) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        for (String entry : rawEntries) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq >= trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }

            String normalizedValue = normalizeItemId(value, null, true);
            if (normalizedValue != null) {
                result.put(key, normalizedValue);
            }
        }

        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    private static String normalizeItemId(String raw, String fallback, boolean allowNone) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return fallback;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (allowNone && lower.equals("none")) {
            return "none";
        }

        if (!lower.contains(":")) {
            lower = "minecraft:" + lower;
        }

        return safeResourceLocation(lower) != null ? lower : fallback;
    }

    private static ResourceLocation safeResourceLocation(String raw) {
        try {
            return new ResourceLocation(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static java.util.Map<String, String> parseTomlKeyValues(java.nio.file.Path configPath) throws java.io.IOException {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        java.util.List<String> lines = java.nio.file.Files.readAllLines(configPath);
        for (String rawLine : lines) {
            String line = rawLine;
            int hashIdx = line.indexOf('#');
            if (hashIdx >= 0) {
                line = line.substring(0, hashIdx);
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            int eqIdx = line.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = line.substring(0, eqIdx).trim();
            String value = line.substring(eqIdx + 1).trim();
            kv.put(key, value);
        }
        return kv;
    }

    private static boolean parseBoolean(java.util.Map<String, String> kv, String key, boolean defaultValue) {
        String value = kv.get(key);
        if (value == null) {
            return defaultValue;
        }
        value = stripQuotes(value).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return defaultValue;
    }

    private static float parseFloat(java.util.Map<String, String> kv, String key, float defaultValue) {
        String value = kv.get(key);
        if (value == null) {
            return defaultValue;
        }
        value = stripQuotes(value).trim();
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String parseString(java.util.Map<String, String> kv, String key, String defaultValue) {
        String value = kv.get(key);
        if (value == null) {
            return defaultValue;
        }
        value = stripQuotes(value).trim();
        return value;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
    
    /**
     * Create default config file from resources
     */
    private static void createDefaultConfigFromResources(java.nio.file.Path configPath) throws java.io.IOException {
        // Create config directory if it doesn't exist
        java.nio.file.Files.createDirectories(configPath.getParent());
        
        // Copy from resources to config directory
        try (java.io.InputStream inputStream = GANCityMod.class.getResourceAsStream("/adaptivemobai-common.toml")) {
            if (inputStream != null) {
                java.nio.file.Files.copy(inputStream, configPath);
                LOGGER.info("Created default config at {}", configPath);
            } else {
                LOGGER.error("Could not find default config in resources");
            }
        }
    }
}
