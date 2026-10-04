package dev.daa.mixin;

import dev.daa.game.DaaScreen;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Takes the chat out of {@code GameScreen.render} so {@code DaaScreen} can draw it <em>after</em> the
 * design frame is popped.
 *
 * <p>It is the one thing on this screen that vanilla also draws on its own: {@code Gui}'s chat layer
 * runs before any screen and paints a copy at {@code guiHeight() - 40} in plain window coordinates.
 * {@code GameScreen} paints a second copy 25 px higher. Both line up only while "window coordinates"
 * and "screen coordinates" are the same thing, which inside a scaled frame they are not.
 *
 * <p>Redirecting the single {@code ChatComponent.render} call to nothing is enough: {@code DaaScreen}
 * then repeats exactly the same call after popping the pose.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenChatFrame {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V"))
    private void daa$chatOutsideFrame(ChatComponent chat, GuiGraphics guiGraphics,
                                      int ticks, int mouseX, int mouseY, boolean focused) {
        if (!(((Object) this) instanceof DaaScreen)) {
            chat.render(guiGraphics, ticks, mouseX, mouseY, focused);
        }
    }
}
