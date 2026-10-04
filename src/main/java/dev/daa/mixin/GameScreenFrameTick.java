package dev.daa.mixin;

import dev.daa.client.DaaFrame;
import dev.daa.game.DaaScreen;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands {@code GameScreen.containerTick}'s card tilt a mouse in design-frame coordinates.
 *
 * <p>{@code DaaScreen} paints inside a scaled, centred frame (see {@link DaaFrame}) and therefore
 * reports its widgets and its mouse in frame coordinates. Everything that goes through the render pass
 * is converted along the way, but {@code containerTick} sidesteps it: it reads
 * {@code MouseHandler.xpos()} straight off the window and multiplies it into a GUI coordinate itself.
 * With a frame scale of 0.67 that hands every widget a mouse about 1.5x too far from it, and
 * {@code AbstractCardWidget.tick} turns that difference into a hover tilt without clamping anything.
 *
 * <p>The handler is an instance method with a guard because {@code GameScreen} is also the base class
 * of Charta's own games, which paint in plain window coordinates.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenFrameTick {

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;xpos()D"))
    private double daa$frameMouseX(MouseHandler mouseHandler) {
        double raw = mouseHandler.xpos();
        return ((Object) this) instanceof DaaScreen ? DaaFrame.tickX(raw) : raw;
    }

    @Redirect(
            method = "containerTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;ypos()D"))
    private double daa$frameMouseY(MouseHandler mouseHandler) {
        double raw = mouseHandler.ypos();
        return ((Object) this) instanceof DaaScreen ? DaaFrame.tickY(raw) : raw;
    }
}
