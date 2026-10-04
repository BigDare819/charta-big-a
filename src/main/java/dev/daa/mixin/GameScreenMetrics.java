package dev.daa.mixin;

import dev.daa.client.CardSlotAccess;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the click hit-tests on the same numbers the cards are painted with.
 *
 * <p>{@code isHoveringPrecise} builds its box from the static {@code CardSlot.getWidth/getHeight}
 * overloads, while {@link CardSlotWidgetMetrics} makes the <em>painter</em> use the per-slot declared
 * box. Redirecting only one of the two would slide a hand's clickable area off the cards.
 *
 * <p>Scoped to that single method on purpose: {@code GameScreen.render} also compares a slot's width
 * against {@code CardImage.WIDTH * 1.5f} to decide whether to blit the framed drop-target background,
 * and that comparison should stay on the vanilla type sizes.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenMetrics {

    @Redirect(
            method = "isHoveringPrecise(Ldev/lucaargolo/charta/common/menu/CardSlot;FF)Z",
            require = 0,
            remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getWidth(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float daa$declaredWidth(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).daa$width();
    }

    @Redirect(
            method = "isHoveringPrecise(Ldev/lucaargolo/charta/common/menu/CardSlot;FF)Z",
            require = 0,
            remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getHeight(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float daa$declaredHeight(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).daa$height();
    }
}
