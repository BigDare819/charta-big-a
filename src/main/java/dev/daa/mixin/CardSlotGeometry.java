package dev.daa.mixin;

import dev.daa.client.CardSlotAccess;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Injects a mutable view of {@link CardSlot}'s geometry into Charta's own slot class.
 *
 * <p>{@code x} and {@code y} are {@code public final}, and width/height are not stored at all — the
 * widget derives them from {@link CardSlot.Type} through two <em>static</em> methods, and uses the
 * declared width as the box the cards fan inside. That makes the declared width the only lever that
 * spreads a hand out, so the F9 layout editor needs a per-slot size. The static lookups cannot be
 * overridden, so this records the size on the instance and {@link CardSlotAccess} reads it back.
 *
 * <p>{@code @Mutable} is required, not decorative: {@code x} and {@code y} are {@code final}, and a
 * plain accessor setter fails at runtime with
 * {@code IllegalAccessError: Update to non-static final field ... attempted from a different method}.
 */
@Mixin(CardSlot.class)
public abstract class CardSlotGeometry implements CardSlotAccess {

    @Unique
    private float daa$width;

    @Unique
    private float daa$height;

    /** Marks the field as set, so a width of 0 can stay legal. */
    @Unique
    private boolean daa$sized;

    @Override
    public void daa$setSize(float width, float height) {
        this.daa$width = width;
        this.daa$height = height;
        this.daa$sized = true;
    }

    @Override
    public float daa$width() {
        return this.daa$sized ? this.daa$width : CardSlot.getWidth(daa$type());
    }

    @Override
    public float daa$height() {
        return this.daa$sized ? this.daa$height : CardSlot.getHeight(daa$type());
    }

    @Mutable
    @Accessor("x")
    abstract void daa$setFieldX(float x);

    @Mutable
    @Accessor("y")
    abstract void daa$setFieldY(float y);

    @Accessor("type")
    abstract CardSlot.Type daa$type();

    @Override
    public void daa$setX(float x) {
        this.daa$setFieldX(x);
    }

    @Override
    public void daa$setY(float y) {
        this.daa$setFieldY(y);
    }
}
