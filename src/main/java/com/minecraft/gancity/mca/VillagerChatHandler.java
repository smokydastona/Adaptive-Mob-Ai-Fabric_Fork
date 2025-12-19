package com.minecraft.gancity.mca;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.VillagerDialogueAI;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles chat-based conversations with MCA villagers
 * Players can type messages and nearby villagers respond
 */
@SuppressWarnings("null")
public class VillagerChatHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Track which player is talking to which villager
    private static final Map<UUID, UUID> activeConversations = new HashMap<>();
    private static final Map<UUID, Long> lastInteractionTime = new HashMap<>();
    
    private static final long CONVERSATION_TIMEOUT = 30000; // 30 seconds
    private static final double HEARING_RANGE = 8.0; // blocks
    
    /**
     * Best-effort Fabric chat hook registration.
     *
     * We use reflection to avoid hard-coding a specific Fabric API message event
     * signature (which has changed across MC/Fabric API versions).
     */
    public static void tryRegisterFabricChatHooks() {
        try {
            Class<?> serverMessageEvents = Class.forName("net.fabricmc.fabric.api.message.v1.ServerMessageEvents");

            // Listener type is a nested interface in modern Fabric API versions.
            // We reflect it so this class can still load even if the event API changes.
            Class<?> allowListenerType;
            try {
                allowListenerType = Class.forName("net.fabricmc.fabric.api.message.v1.ServerMessageEvents$AllowChatMessage");
            } catch (ClassNotFoundException e) {
                allowListenerType = null;
            }

            // Prefer ALLOW_CHAT_MESSAGE if present (lets us cancel broadcast).
            try {
                Object allowEvent = serverMessageEvents.getField("ALLOW_CHAT_MESSAGE").get(null);

                // The event is a Fabric Event<T>. At runtime, register is erased to register(Object).
                // We still need the listener to implement the correct functional interface; otherwise
                // java.lang.reflect.Proxy will throw "Object is not an interface".
                // IMPORTANT: the runtime event implementation class may be package-private.
                // Reflecting on it directly can throw IllegalAccessException even if the method is public.
                // Invoke via the public Event interface instead.
                Class<?> fabricEventInterface = Class.forName("net.fabricmc.fabric.api.event.Event");
                java.lang.reflect.Method registerMethod = fabricEventInterface.getMethod("register", Object.class);

                if (allowListenerType == null || !allowListenerType.isInterface()) {
                    LOGGER.warn("MCA AI Enhanced - Fabric chat hooks not available (ALLOW_CHAT_MESSAGE listener type missing)");
                    return;
                }

                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        VillagerChatHandler.class.getClassLoader(),
                        new Class<?>[]{allowListenerType},
                        (proxy, method, args) -> {
                            // Handle Object methods (toString/hashCode/equals)
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" -> "VillagerChatHandler-AllowChatMessageProxy";
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                                    default -> null;
                                };
                            }

                            // Expected args vary by version. We only care about sender (ServerPlayer) and message string.
                            ServerPlayer player = null;
                            String message = null;

                            for (Object arg : args) {
                                if (arg instanceof ServerPlayer sp) {
                                    player = sp;
                                }
                                // PlayerChatMessage / Component / String - normalize best effort.
                                if (message == null && arg != null) {
                                    if (arg.getClass().getName().equals("net.minecraft.network.chat.PlayerChatMessage")) {
                                        try {
                                            Object content = arg.getClass().getMethod("decoratedContent").invoke(arg);
                                            if (content instanceof Component c) {
                                                message = c.getString();
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    } else if (arg instanceof Component c) {
                                        message = c.getString();
                                    } else if (arg instanceof String s) {
                                        message = s;
                                    }
                                }
                            }

                            if (player == null || message == null) {
                                return true;
                            }

                            boolean consumed = handleChatMessage(player, message);
                            return !consumed;
                        });

                registerMethod.invoke(allowEvent, listener);
                LOGGER.info("MCA AI Enhanced - Fabric chat hook registered (ALLOW_CHAT_MESSAGE)");
                return;
            } catch (NoSuchFieldException ignored) {
                // Fall through
            }

            LOGGER.warn("MCA AI Enhanced - Fabric chat hooks not available (no ALLOW_CHAT_MESSAGE)");
        } catch (Throwable t) {
            LOGGER.warn("MCA AI Enhanced - Could not register Fabric chat hooks: {}", t.getMessage());
        }
    }

    /**
     * Handle a chat message and return true if consumed (i.e., should not broadcast).
     */
    public static boolean handleChatMessage(ServerPlayer player, String message) {
        if (!MCAIntegration.isMCALoaded()) {
            return false;
        }

        UUID playerId = player.getUUID();
        
        // Check if player has an active conversation
        UUID activeVillager = activeConversations.get(playerId);
        
        if (activeVillager != null) {
            // Check if conversation timed out
            Long lastTime = lastInteractionTime.get(playerId);
            if (lastTime != null && System.currentTimeMillis() - lastTime > CONVERSATION_TIMEOUT) {
                activeConversations.remove(playerId);
                player.sendSystemMessage(Component.literal("§7[The villager stopped listening]"));
                return true;
            }
            
            // Find the villager entity
            Entity villagerEntity = player.serverLevel().getEntity(activeVillager);
            if (villagerEntity != null && villagerEntity.distanceTo(player) <= HEARING_RANGE) {
                // Generate response
                respondToPlayer(player, villagerEntity, message);
                lastInteractionTime.put(playerId, System.currentTimeMillis());
                
                // Cancel the chat event so message doesn't broadcast
                // (Fabric hook uses return value to cancel)
                
                // Show what player said
                player.sendSystemMessage(Component.literal("§e" + player.getName().getString() + ": §f" + message));
                return true;
            } else {
                activeConversations.remove(playerId);
                player.sendSystemMessage(Component.literal("§7[The villager is too far away]"));
            }
        }
        
        // Check if player is starting a conversation with nearby villager
        if (message.toLowerCase().startsWith("@villager") || message.toLowerCase().startsWith("hey villager")) {
            Entity nearestVillager = findNearestMCAVillager(player);
            if (nearestVillager != null) {
                // Start conversation
                activeConversations.put(playerId, nearestVillager.getUUID());
                lastInteractionTime.put(playerId, System.currentTimeMillis());
                
                player.sendSystemMessage(Component.literal("§a[Now talking to " + getVillagerName(nearestVillager) + "]"));
                player.sendSystemMessage(Component.literal("§7(Type 'bye' to end conversation)"));
                
                // Remove the @villager prefix and respond
                String actualMessage = message.replaceFirst("(?i)@villager\\s*", "")
                                              .replaceFirst("(?i)hey villager\\s*", "");
                
                if (!actualMessage.isEmpty()) {
                    respondToPlayer(player, nearestVillager, actualMessage);
                }

                return true;
            } else {
                player.sendSystemMessage(Component.literal("§c[No villager nearby]"));
            }
        }
        
        // Check for bye/goodbye to end conversation
        if (activeVillager != null && (message.equalsIgnoreCase("bye") || message.equalsIgnoreCase("goodbye"))) {
            activeConversations.remove(playerId);
            lastInteractionTime.remove(playerId);
            player.sendSystemMessage(Component.literal("§7[Conversation ended]"));
            return true;
        }

        return false;
    }
    
    /**
     * Generate and send villager's response
     */
    private static void respondToPlayer(ServerPlayer player, Entity villager, String playerMessage) {
        try {
            // Create dialogue context
            VillagerDialogueAI.DialogueContext context = new VillagerDialogueAI.DialogueContext("conversation");
            context.playerName = player.getName().getString();
            context.playerId = player.getUUID();
            context.villagerName = getVillagerName(villager);
            
            // Get profession if available
            try {
                var professionMethod = villager.getClass().getMethod("getProfession");
                Object profession = professionMethod.invoke(villager);
                if (profession != null) {
                    context.profession = profession.toString();
                }
            } catch (Exception ignored) {}
            
            // Generate response
            VillagerDialogueAI dialogueAI = GANCityMod.getVillagerDialogueAI();
            String response = dialogueAI.generateDialogue(villager.getUUID(), playerMessage, context);
            
            // Send response
            player.sendSystemMessage(Component.literal("§b" + context.villagerName + ": §f" + response));
            
            LOGGER.debug("Villager {} responded to {}: {}", villager.getUUID(), player.getName().getString(), response);
            
        } catch (Exception e) {
            LOGGER.warn("Failed to generate villager response: {}", e.getMessage());
            player.sendSystemMessage(Component.literal("§b" + getVillagerName(villager) + ": §f*confused silence*"));
        }
    }
    
    /**
     * Find nearest MCA villager within hearing range
     */
    private static Entity findNearestMCAVillager(ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(HEARING_RANGE);
        List<Entity> nearbyEntities = player.serverLevel().getEntities(player, searchBox);
        
        Entity nearest = null;
        double nearestDistance = HEARING_RANGE + 1;
        
        for (Entity entity : nearbyEntities) {
            if (isMCAVillager(entity)) {
                double distance = entity.distanceTo(player);
                if (distance < nearestDistance) {
                    nearest = entity;
                    nearestDistance = distance;
                }
            }
        }
        
        return nearest;
    }
    
    /**
     * Check if entity is MCA villager using reflection
     */
    private static boolean isMCAVillager(Entity entity) {
        try {
            Class<?> mcaVillagerClass = Class.forName("mca.entity.VillagerEntityMCA");
            return mcaVillagerClass.isInstance(entity);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Get villager name via reflection
     */
    private static String getVillagerName(Entity villager) {
        try {
            var method = villager.getClass().getMethod("getVillagerName");
            Object name = method.invoke(villager);
            return name != null ? name.toString() : "Villager";
        } catch (Exception e) {
            return "Villager";
        }
    }
    
    /**
     * End all conversations for a player (called on logout)
     */
    public static void endConversation(UUID playerId) {
        activeConversations.remove(playerId);
        lastInteractionTime.remove(playerId);
    }
}
