package dev.daa;

import dev.daa.game.DaaScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Client side. {@code DaaScreen} extends Charta's {@code GameScreen}, so the card widgets, their hover
 * animation, the history panel, the options panel and the deck viewer all come from Charta for free.
 *
 * <p>This is a {@code client} entrypoint, which Fabric runs after every {@code main} entrypoint, so
 * Charta is guaranteed to be fully initialised by the time this runs — no deferral needed.
 */
@Environment(EnvType.CLIENT)
public class DaaClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MenuScreens.register(DaaMod.DAA_MENU, DaaScreen::new);
    }

}
