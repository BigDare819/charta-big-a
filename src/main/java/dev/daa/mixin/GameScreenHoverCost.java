package dev.daa.mixin;

import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.utils.HoverableRenderable;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the hovered widget from being painted once per <em>other</em> widget.
 *
 * <p>{@code GameScreen.render} walks {@code this.renderables} and, before drawing each one, re-draws
 * {@code this.hoverable} first. The comment says it keeps cards from "flashing weirdly"; what it
 * actually costs is that with a card on hover, the hovered widget is drawn {@code renderables.size()}
 * times instead of once — and the widget here is a whole hand of up to 22 cards.
 *
 * <p>Dropping the interleaved draws changes nothing: the trailing draw already happens last, so the
 * hovered widget ends up on top either way, and every intermediate draw uses identical geometry.
 */
@Mixin(value = GameScreen.class, priority = 1500)
public abstract class GameScreenHoverCost {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ldev/lucaargolo/charta/common/utils/HoverableRenderable;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private static void daa$skipInterleavedHoverable(HoverableRenderable hoverable,
                                                     GuiGraphics guiGraphics,
                                                     int mouseX, int mouseY, float partialTick) {
        // Deliberately empty: the trailing draw after the loop already covers this.
    }
}
