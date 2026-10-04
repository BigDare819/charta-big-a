package dev.daa.mixin;

import dev.daa.client.CardSlotAccess;
import dev.lucaargolo.charta.client.render.screen.widgets.CardSlotWidget;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the card fan honour the slot's declared box.
 *
 * <p>{@code CardSlotWidget} reads the box through {@code CardSlot.getWidth(slot)} /
 * {@code getHeight(slot)} — both <em>static</em>, both switching on {@link CardSlot.Type}. So a slot
 * could be moved but never resized: {@code daa$setSize} wrote a field nothing read, and the fan was
 * pinned to 150px for every {@code HORIZONTAL} slot.
 *
 * <p>Only the two {@code (CardSlot)} overloads are redirected. The {@code (CardSlot.Type)} ones read
 * fixed sizes for the child cards themselves and must stay untouched.
 *
 * <h2>Why the {@code remap} flags are asymmetric</h2>
 *
 * <p>{@code renderWidget} <b>overrides a vanilla method</b>, so Charta's release jar carries it under
 * its intermediary name, {@code method_48579}; the selector therefore has to stay remappable.
 * {@code CardSlot.getWidth} is Charta's own, never remapped, so the {@code @At} target is literal and
 * is flagged {@code remap = false}.
 */
@Mixin(value = CardSlotWidget.class, priority = 1500)
public abstract class CardSlotWidgetMetrics {

    @Redirect(
            method = "renderWidget",
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getWidth(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float daa$declaredWidth(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).daa$width();
    }

    @Redirect(
            method = "renderWidget",
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/lucaargolo/charta/common/menu/CardSlot;getHeight(Ldev/lucaargolo/charta/common/menu/CardSlot;)F"))
    private static float daa$declaredHeight(CardSlot<?, ?> slot) {
        return ((CardSlotAccess) slot).daa$height();
    }
}
