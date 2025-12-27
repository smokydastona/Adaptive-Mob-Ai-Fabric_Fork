package com.minecraft.gancity.fabric;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.command.GANCityCommand;
import com.minecraft.gancity.event.MobTierAssignmentHandler;
import com.minecraft.gancity.mca.MCADialogueHandler;
import com.minecraft.gancity.mca.VillagerChatHandler;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Proxy;

/**
 * Fabric entrypoint.
 */
@SuppressWarnings({"unused"})
public final class AdaptiveMobAIFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        GANCityMod.bootstrap();

        // Register commands via reflection to avoid hard-linking callback parameter types.
        // This keeps the entrypoint resilient across mapping/tooling variations.
        tryRegisterCommands();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> GANCityMod.onServerStarting());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GANCityMod.onServerStopping());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GANCityMod.onServerTick(server);
            MobTierAssignmentHandler.onServerTick(server);
        });

        tryRegisterEntityLoad();

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                MCADialogueHandler.onUseEntity(player, world, hand, entity));

        VillagerChatHandler.tryRegisterFabricChatHooks();
    }

    private static void tryRegisterCommands() {
        try {
            Class<?> callbackInterface = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            Object event = callbackInterface.getField("EVENT").get(null);

            Object listener = Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class<?>[]{callbackInterface},
                (proxy, method, args) -> {
                    if (!"register".equals(method.getName()) || args == null || args.length < 1) {
                        return null;
                    }

                    Object dispatcherObj = args[0];
                    if (dispatcherObj instanceof CommandDispatcher<?> dispatcher) {
                        @SuppressWarnings("unchecked")
                        CommandDispatcher<CommandSourceStack> typed = (CommandDispatcher<CommandSourceStack>) dispatcher;
                        GANCityCommand.register(typed);
                    }

                    return null;
                }
            );

            // Fabric Event#register is generic; erases to register(Object) in most implementations.
            try {
                event.getClass().getMethod("register", Object.class).invoke(event, listener);
            } catch (NoSuchMethodException ignored) {
                event.getClass().getMethod("register", callbackInterface).invoke(event, listener);
            }
        } catch (Throwable t) {
            // Do not crash the mod if command API isn't present or signature changed.
            GANCityMod.LOGGER.warn("Command registration hook failed (commands may be unavailable): {}", t.toString());
        }
    }

    private static void tryRegisterEntityLoad() {
        try {
            Class<?> loadCallbackInterface = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents$Load");
            Class<?> serverEntityEventsClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents");
            Object event = serverEntityEventsClass.getField("ENTITY_LOAD").get(null);

            // Resolve types reflectively to avoid mapping name mismatches in dev tooling.
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");

            Object listener = Proxy.newProxyInstance(
                loadCallbackInterface.getClassLoader(),
                new Class<?>[]{loadCallbackInterface},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2) {
                        return null;
                    }

                    Object a0 = args[0];
                    Object a1 = args[1];

                    Object entityObj = null;
                    Object levelObj = null;

                    // Common signature: (Entity, ServerLevel)
                    if (a0 != null && a1 != null && entityClass.isInstance(a0) && serverLevelClass.isInstance(a1)) {
                        entityObj = a0;
                        levelObj = a1;
                    }
                    // Some environments may swap args: (ServerLevel, Entity)
                    else if (a0 != null && a1 != null && serverLevelClass.isInstance(a0) && entityClass.isInstance(a1)) {
                        entityObj = a1;
                        levelObj = a0;
                    }

                    if (entityObj != null && levelObj != null) {
                        try {
                            MobTierAssignmentHandler.onEntityLoad(
                                (net.minecraft.world.entity.Entity) entityObj,
                                (net.minecraft.server.level.ServerLevel) levelObj
                            );
                        } catch (Throwable t) {
                            // Never crash server entity loading; log at debug for diagnosis.
                            GANCityMod.LOGGER.debug("ENTITY_LOAD handler threw: {}", t.toString());
                        }
                    }

                    return null;
                }
            );

            try {
                event.getClass().getMethod("register", Object.class).invoke(event, listener);
            } catch (NoSuchMethodException ignored) {
                event.getClass().getMethod("register", loadCallbackInterface).invoke(event, listener);
            }
        } catch (Throwable t) {
            GANCityMod.LOGGER.warn("ENTITY_LOAD hook failed (tier assignment may be limited): {}", t.toString());
        }
    }
}
