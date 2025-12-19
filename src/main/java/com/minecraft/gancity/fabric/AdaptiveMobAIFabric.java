package com.minecraft.gancity.fabric;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.command.GANCityCommand;
import com.minecraft.gancity.event.MobTierAssignmentHandler;
import com.minecraft.gancity.mca.MCADialogueHandler;
import com.minecraft.gancity.mca.VillagerChatHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

/**
 * Fabric entrypoint.
 */
@SuppressWarnings({"unused"})
public final class AdaptiveMobAIFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        GANCityMod.bootstrap();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GANCityCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> GANCityMod.onServerStarting());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GANCityMod.onServerStopping());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GANCityMod.onServerTick(server);
            MobTierAssignmentHandler.onServerTick(server);
        });

        ServerEntityEvents.ENTITY_LOAD.register(MobTierAssignmentHandler::onEntityLoad);

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                MCADialogueHandler.onUseEntity(player, world, hand, entity));

        VillagerChatHandler.tryRegisterFabricChatHooks();
    }
}
