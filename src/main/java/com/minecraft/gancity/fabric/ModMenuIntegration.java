package com.minecraft.gancity.fabric;

import com.minecraft.gancity.client.config.AdaptiveMobAiConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration (Fabric).
 *
 * Requires the Mod Menu mod to be installed by the user.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AdaptiveMobAiConfigScreen::new;
    }
}
