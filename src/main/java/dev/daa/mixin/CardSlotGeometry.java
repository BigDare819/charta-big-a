package dev.daa.mixin;

import dev.daa.client.CardSlotAccess;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
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
 *
 * <h2>Why the fields are called {@code bridge$...} and are not {@code @Unique}</h2>
 *
 * <p>Charta addons by the same author overlap, and a second addon declares the same three fields. Mixin
 * merges any field that is <em>not</em> {@code @Unique} into an identically named field already on the
 * target, so keeping the exact name and descriptor makes both addons read and write <em>one</em> slot
 * size. That matters because of how a {@code @Redirect} collision is resolved: the higher priority
 * config wins and the other is skipped outright, so whichever addon's metric redirect survives must
 * still see the size the other addon's layout editor wrote.
 */
@Mixin(CardSlot.class)
public abstract class CardSlotGeometry implements CardSlotAccess {

    /** Shared with the bridge addon; see the class note. Not {@code @Unique}, on purpose. */
    private float bridge$width;

    private float bridge$height;

    /** Marks the field as set, so a width of 0 can stay legal. */
    private boolean bridge$sized;

    @Override
    public void daa$setSize(float width, float height) {
        this.bridge$width = width;
        this.bridge$height = height;
        this.bridge$sized = true;
    }

    @Override
    public float daa$width() {
        return this.bridge$sized ? this.bridge$width : CardSlot.getWidth(daa$type());
    }

    @Override
    public float daa$height() {
        return this.bridge$sized ? this.bridge$height : CardSlot.getHeight(daa$type());
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
