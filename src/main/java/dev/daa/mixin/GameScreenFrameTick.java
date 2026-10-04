package dev.daa.mixin;

import dev.daa.client.DaaFrame;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands {@code GameScreen.containerTick}'s card tilt a mouse in design-frame coordinates.
 *
 * <p>A framed screen paints inside a scaled, centred frame (see {@link DaaFrame}) and therefore
 * reports its widgets and its mouse in frame coordinates. Everything that goes through the render pass
 * is converted along the way, but {@code containerTick} sidesteps it: it reads
 * {@code MouseHandler.xpos()} straight off the window and multiplies it into a GUI coordinate itself.
 * With a frame scale of 0.67 that hands every widget a mouse about 1.5x too far from it, and
 * {@code AbstractCardWidget.tick} turns that difference into a hover tilt without clamping anything.
 *
 * <p>Fires for every framed screen rather than only {@code DaaScreen}: every frame is the same 640x360
 * design mapped the same way, so the conversion is identical, and covering both keeps this working on
 * whichever addon's copy of the mixin Mixin decides to skip.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenFrameTick {

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;xpos()D"))
    private double daa$frameMouseX(MouseHandler mouseHandler) {
        double raw = mouseHandler.xpos();
        return DaaFrame.isFramedScreen(this) ? DaaFrame.tickX(raw) : raw;
    }

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;ypos()D"))
    private double daa$frameMouseY(MouseHandler mouseHandler) {
        double raw = mouseHandler.ypos();
        return DaaFrame.isFramedScreen(this) ? DaaFrame.tickY(raw) : raw;
    }
}
