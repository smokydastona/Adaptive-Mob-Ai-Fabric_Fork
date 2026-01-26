package com.minecraft.gancity.client.config;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.MobBehaviorAI;
import com.minecraft.gancity.config.ModdedMobTacticMappingStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client-side config UI for mapping modded mobs to existing tactic profiles.
 *
 * This provides fine tuning: users can override the auto-selected profile
 * for any entity type id (e.g. "alexsmobs:grizzly_bear" -> "polar_bear").
 */
@SuppressWarnings({"null", "ConstantConditions"})
public final class AdaptiveMobAiModdedMobTacticsConfigScreen extends Screen {
    private final Screen parent;

    private EditBox search;
    private EntityList entityList;
    private String query = "";

    private Button toggleEnabled;
    private Button toggleAuto;
    private Button setOverride;
    private Button clearOverride;

    private boolean showVanilla = false;
    private Button toggleShowVanilla;

    private String selectedEntityId;

    // Local editable copy (write on Save)
    private ModdedMobTacticMappingStore.Config working;

    private List<String> allEntityIds = List.of();
    private List<String> profileKeys = List.of();
    private final Map<String, String> suggestedCache = new HashMap<>();

    public AdaptiveMobAiModdedMobTacticsConfigScreen(Screen parent) {
        super(Component.literal("Adaptive Mob AI - Modded Mob Tactics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Load config once per screen open
        ModdedMobTacticMappingStore.loadIfNeeded();
        ModdedMobTacticMappingStore.Config cfg = ModdedMobTacticMappingStore.get();
        working = deepCopy(cfg);

        profileKeys = loadProfileKeyOptions();
        allEntityIds = buildEntityIdList(showVanilla);

        int leftWidth = Math.max(240, this.width / 3);
        int rightX = leftWidth + 14;

        search = new EditBox(this.font, 10, 12, leftWidth - 20, 18, Component.literal("Search entities"));
        search.setMaxLength(128);
        search.setValue(query == null ? "" : query);
        search.setResponder(s -> {
            query = s;
            refreshList();
        });
        addRenderableWidget(search);

        int bottomButtonsY = this.height - 28;
        entityList = new EntityList(this.minecraft, leftWidth, this.height, 36, bottomButtonsY - 12, 18);
        entityList.setLeftPos(10);
        addRenderableWidget(entityList);

        int rowY = 42;
        int gap = 4;
        int rowH = 20;
        int fieldW = Math.min(320, this.width - rightX - 12);

        toggleEnabled = addRenderableWidget(Button.builder(Component.literal("Mapping: " + (working.enabled ? "ON" : "OFF")), b -> {
            working.enabled = !working.enabled;
            refreshRightPanel();
        }).bounds(rightX, rowY, fieldW, rowH).build());

        toggleAuto = addRenderableWidget(Button.builder(Component.literal("Auto-Assign: " + (working.autoAssignEnabled ? "ON" : "OFF")), b -> {
            working.autoAssignEnabled = !working.autoAssignEnabled;
            refreshRightPanel();
        }).bounds(rightX, rowY + (rowH + gap), fieldW, rowH).build());

        toggleShowVanilla = addRenderableWidget(Button.builder(Component.literal("Show Vanilla: " + (showVanilla ? "ON" : "OFF")), b -> {
            showVanilla = !showVanilla;
            allEntityIds = buildEntityIdList(showVanilla);
            refreshList();
            toggleShowVanilla.setMessage(Component.literal("Show Vanilla: " + (showVanilla ? "ON" : "OFF")));
        }).bounds(rightX, rowY + (rowH + gap) * 2, fieldW, rowH).build());

        setOverride = addRenderableWidget(Button.builder(Component.literal("Set Override"), b -> openProfilePicker())
            .bounds(rightX, rowY + (rowH + gap) * 3, fieldW, rowH)
            .build());

        clearOverride = addRenderableWidget(Button.builder(Component.literal("Clear Override"), b -> {
            if (selectedEntityId != null) {
                working.overrides.remove(selectedEntityId);
                refreshRightPanel();
                refreshList();
            }
        }).bounds(rightX, rowY + (rowH + gap) * 4, fieldW, rowH).build());

        // Default profiles (simple global fallbacks)
        addRenderableWidget(Button.builder(Component.literal("Default Hostile: " + working.defaultHostileProfile), b -> {
            openProfilePickerForDefault(true);
        }).bounds(rightX, rowY + (rowH + gap) * 5, fieldW, rowH).build());

        addRenderableWidget(Button.builder(Component.literal("Default Passive: " + working.defaultPassiveProfile), b -> {
            openProfilePickerForDefault(false);
        }).bounds(rightX, rowY + (rowH + gap) * 6, fieldW, rowH).build());

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            applyAndSave();
            onClose();
        }).bounds(rightX, bottomButtonsY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(rightX + 110, bottomButtonsY, 100, 20)
            .build());

        // Initial selection
        if (!allEntityIds.isEmpty()) {
            setSelectedEntity(allEntityIds.get(0));
        }
        refreshList();
        refreshRightPanel();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void applyAndSave() {
        // Normalize stored values
        working.defaultHostileProfile = ModdedMobTacticMappingStore.normalizeProfileKey(working.defaultHostileProfile);
        working.defaultPassiveProfile = ModdedMobTacticMappingStore.normalizeProfileKey(working.defaultPassiveProfile);
        if (working.defaultHostileProfile == null || working.defaultHostileProfile.isBlank()) {
            working.defaultHostileProfile = "zombie";
        }
        if (working.defaultPassiveProfile == null || working.defaultPassiveProfile.isBlank()) {
            working.defaultPassiveProfile = "cow";
        }

        // Write back using store APIs
        ModdedMobTacticMappingStore.setEnabled(working.enabled);
        ModdedMobTacticMappingStore.setAutoAssignEnabled(working.autoAssignEnabled);

        // Replace overrides/namespaces wholesale
        ModdedMobTacticMappingStore.Config persisted = ModdedMobTacticMappingStore.get();
        persisted.defaultHostileProfile = working.defaultHostileProfile;
        persisted.defaultPassiveProfile = working.defaultPassiveProfile;
        persisted.overrides.clear();
        persisted.overrides.putAll(working.overrides);
        persisted.namespaceDefaults.clear();
        persisted.namespaceDefaults.putAll(working.namespaceDefaults);
        ModdedMobTacticMappingStore.save();
    }

    private void refreshList() {
        if (entityList == null) {
            return;
        }

        String q = search == null ? query : search.getValue();
        String normalized = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        List<String> filtered;
        if (normalized.isEmpty()) {
            filtered = allEntityIds;
        } else {
            filtered = allEntityIds.stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
        }

        entityList.replaceEntries(filtered);
    }

    private void setSelectedEntity(String entityId) {
        selectedEntityId = entityId;
        refreshRightPanel();
    }

    private void refreshRightPanel() {
        toggleEnabled.setMessage(Component.literal("Mapping: " + (working.enabled ? "ON" : "OFF")));
        toggleAuto.setMessage(Component.literal("Auto-Assign: " + (working.autoAssignEnabled ? "ON" : "OFF")));

        boolean hasSelection = selectedEntityId != null;
        setOverride.active = hasSelection;
        clearOverride.active = hasSelection;

        if (!hasSelection) {
            setOverride.setMessage(Component.literal("Set Override"));
            clearOverride.setMessage(Component.literal("Clear Override"));
            return;
        }

        String override = working.overrides.get(selectedEntityId);
        String overrideLabel = (override == null || override.isBlank()) ? "(none)" : override;
        setOverride.setMessage(Component.literal("Set Override (currently: " + overrideLabel + ")"));
        clearOverride.setMessage(Component.literal("Clear Override"));
    }

    private void openProfilePicker() {
        if (selectedEntityId == null) {
            return;
        }

        String current = working.overrides.get(selectedEntityId);
        String currentLabel = (current == null || current.isBlank()) ? "(none)" : current;

        List<String> options = new ArrayList<>();
        options.add("(none)");
        options.addAll(profileKeys);

        Minecraft.getInstance().setScreen(new StringSelectScreen(
            this,
            Component.literal("Select Tactic Profile"),
            options,
            currentLabel,
            selected -> {
                if (selected == null || selected.equalsIgnoreCase("(none)")) {
                    working.overrides.remove(selectedEntityId);
                } else {
                    String normalized = ModdedMobTacticMappingStore.normalizeProfileKey(selected);
                    if (normalized == null || normalized.isBlank()) {
                        working.overrides.remove(selectedEntityId);
                    } else {
                        working.overrides.put(selectedEntityId, normalized);
                    }
                }
                refreshRightPanel();
                refreshList();
            }
        ));
    }

    private void openProfilePickerForDefault(boolean hostile) {
        String current = hostile ? working.defaultHostileProfile : working.defaultPassiveProfile;
        List<String> options = new ArrayList<>(profileKeys);
        String title = hostile ? "Select Default Hostile Profile" : "Select Default Passive Profile";

        Minecraft.getInstance().setScreen(new StringSelectScreen(
            this,
            Component.literal(title),
            options,
            current,
            selected -> {
                String normalized = ModdedMobTacticMappingStore.normalizeProfileKey(selected);
                if (hostile) {
                    working.defaultHostileProfile = normalized;
                } else {
                    working.defaultPassiveProfile = normalized;
                }
                // Re-init to refresh button text for defaults
                this.init();
            }
        ));
    }

    private static ModdedMobTacticMappingStore.Config deepCopy(ModdedMobTacticMappingStore.Config cfg) {
        ModdedMobTacticMappingStore.Config copy = new ModdedMobTacticMappingStore.Config();
        if (cfg == null) {
            return copy;
        }
        copy.enabled = cfg.enabled;
        copy.autoAssignEnabled = cfg.autoAssignEnabled;
        copy.defaultHostileProfile = cfg.defaultHostileProfile;
        copy.defaultPassiveProfile = cfg.defaultPassiveProfile;
        copy.overrides = new HashMap<>();
        if (cfg.overrides != null) {
            copy.overrides.putAll(cfg.overrides);
        }
        copy.namespaceDefaults = new HashMap<>();
        if (cfg.namespaceDefaults != null) {
            copy.namespaceDefaults.putAll(cfg.namespaceDefaults);
        }
        return copy;
    }

    private static List<String> buildEntityIdList(boolean includeVanilla) {
        Set<ResourceLocation> keys = BuiltInRegistries.ENTITY_TYPE.keySet();

        return keys.stream()
            .filter(rl -> includeVanilla || !"minecraft".equals(rl.getNamespace()))
            .map(ResourceLocation::toString)
            .sorted()
            .collect(Collectors.toList());
    }

    private static List<String> loadProfileKeyOptions() {
        MobBehaviorAI ai = GANCityMod.getMobBehaviorAI();
        if (ai != null) {
            return ai.getAvailableProfileKeys();
        }

        // Fallback: build vanilla entity paths as "profile keys".
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream()
            .filter(rl -> "minecraft".equals(rl.getNamespace()))
            .map(ResourceLocation::getPath)
            .sorted()
            .toList();
    }

    private static String suggestProfileLabel(String entityId) {
        if (entityId == null) {
            return null;
        }

        try {
            // For vanilla ids, the default profile key is the path.
            ResourceLocation rl = ResourceLocation.tryParse(entityId);
            if (rl != null) {
                return rl.getPath();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private final class EntityList extends ObjectSelectionList<EntityEntry> {
        public EntityList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight) {
            super(mc, width, height, y0, y1, itemHeight);
        }

        void replaceEntries(List<String> entityIds) {
            this.clearEntries();
            if (entityIds == null) {
                return;
            }
            for (String id : entityIds) {
                this.addEntry(new EntityEntry(id));
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 20;
        }
    }

    private final class EntityEntry extends ObjectSelectionList.Entry<EntityEntry> {
        private final String entityId;

        EntityEntry(String entityId) {
            this.entityId = entityId;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entityId);
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            String suffix = buildSuffix(entityId);
            graphics.drawString(font, entityId + suffix, x + 4, y + 2, 0xFFFFFF, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            entityList.setSelected(this);
            setSelectedEntity(entityId);
            return true;
        }

        private String buildSuffix(String entityId) {
            String override = working.overrides.get(entityId);
            if (override != null && !override.isBlank()) {
                return " [override: " + override + "]";
            }
            String suggested = suggestedCache.computeIfAbsent(entityId, AdaptiveMobAiModdedMobTacticsConfigScreen::suggestProfileLabel);
            return suggested != null ? " [auto: " + suggested + "]" : "";
        }
    }
}
