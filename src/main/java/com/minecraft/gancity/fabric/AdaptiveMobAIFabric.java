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

            Object listener = Proxy.newProxyInstance(
                loadCallbackInterface.getClassLoader(),
                new Class<?>[]{loadCallbackInterface},
                (proxy, method, args) -> {
                    // Signature varies across mappings; treat first two args as entity/world.
                    if (args != null && args.length >= 2) {
                        try {
                            MobTierAssignmentHandler.onEntityLoad(
                                (net.minecraft.world.entity.Entity) args[0],
                                (net.minecraft.server.level.ServerLevel) args[1]
                            );
                        } catch (Throwable ignored) {
                            // Never break server load if mappings/signature differ.
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
