package dev.daa.mixin;

import dev.daa.game.DaaBot;
import dev.daa.game.DaaGame;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.impl.AutoPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives a human sitting at a Big A table more than two seconds to think.
 *
 * <h2>What Charta does</h2>
 *
 * <p>{@code AutoPlayer.tick} is not only the bot brain: a real player's {@code CardPlayer} is an
 * anonymous {@code AutoPlayer} built by {@code LivingEntityMixin}, so it inherits the same behaviour.
 * Once it is that player's turn, the entity waits
 * {@code Mth.lerp(intelligence, 50, 20) + random(-5, 40)} ticks and then, if no play has arrived,
 * <b>plays its own first legal move</b>. On a 52 card board that is a harmless nudge; on a 108 card,
 * five hand board the 15-90 tick window means a thoughtful player keeps losing their turn to the
 * autopilot.
 *
 * <h2>The seam</h2>
 *
 * <p>Only the {@code Mth.lerp} that computes the base delay is redirected, and only when the game is a
 * {@link DaaGame} and the player is not a {@link DaaBot}: 10 to 16 seconds plus the same jitter, which
 * is enough to read a twenty-two card hand and click out an eight card run, while bots keep Charta's
 * brisk pace and Charta's own games are untouched.
 */
@Mixin(AutoPlayer.class)
public abstract class AutoPlayerPace {

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float daa$thinkLonger(float delta, float start, float end, Game<?, ?> game) {
        if (game instanceof DaaGame && !(((Object) this) instanceof DaaBot)) {
            return Mth.lerp(delta, 320f, 200f);
        }
        return Mth.lerp(delta, start, end);
    }
}
