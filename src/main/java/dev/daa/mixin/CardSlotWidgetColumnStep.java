package dev.daa.mixin;

import dev.lucaargolo.charta.client.render.screen.widgets.CardSlotWidget;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Lets a vertical hand spread as far as its declared box, instead of stopping after 10px a card.
 *
 * <p>A north/south fan spreads to fill its box: {@code CardSlotWidget} solves the per-card step from
 * the declared width. East and west do not work that way. Their step starts at a <em>hard-coded
 * 10</em> and is only reduced when the hand would overflow, so a column's per-card step is
 * {@code min(10, (height - 52.5) / (size - 1))} and growing the box past 172px changes nothing at all.
 *
 * <p>This injector swaps that 10 for the height of a card, which makes the cap <em>one whole card</em>.
 *
 * <p>{@code renderWidget} holds two identical {@code ldc 10.0f} constants; the other one is the
 * {@code childWidth / 10f} slack that sets {@code maxLeftOffset} for the horizontal fan. They are 30
 * instructions apart with the fan's one first, so {@code ordinal = 1} is the column step.
 */
@Mixin(CardSlotWidget.class)
public abstract class CardSlotWidgetColumnStep {

    private static float daa$fullCardStep() {
        return CardSlot.getHeight(CardSlot.Type.DEFAULT);
    }

    @ModifyConstant(
            method = "renderWidget",
            constant = @Constant(floatValue = 10f, ordinal = 1))
    private static float daa$columnStep(float original) {
        return daa$fullCardStep();
    }
}
