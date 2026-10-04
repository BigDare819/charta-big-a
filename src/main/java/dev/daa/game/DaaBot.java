package dev.daa.game;

import dev.lucaargolo.charta.common.game.impl.AutoPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

/**
 * The AI that fills the empty chairs of a {@link DaaGame}.
 *
 * <p>A plain {@link AutoPlayer} — Charta already knows how to drive one: it waits a randomised beat
 * whenever it is that player's turn and then asks the game for {@code getBestPlay}, which for this game
 * means {@link DaaAi}. All that is added here is a readable identity and the {@link #equals} quirk.
 *
 * <h2>Why {@code equals} is deliberately lopsided</h2>
 *
 * <p>{@code CardTableBlockEntity.serverTick} keeps a game alive with
 * {@code if (!seatedPlayers.containsAll(game.getPlayers())) game.endGame();}. Seated players come from
 * the world and never contain a bot, so with a naive identity {@code equals} every game that pads a
 * chair would be killed on the first tick.
 *
 * <p>{@code contains(o)} is implemented as {@code indexOf(o)} and compares {@code o.equals(element)},
 * so it is the <em>argument's</em> equals that runs. Returning {@code true} for every non-bot makes
 * {@code contains(bot)} succeed against the first real player and the table stays alive.
 *
 * <p>The one thing this breaks is {@code playerList.indexOf(bot)}, which then also answers with the
 * first real seat. {@link DaaGame} therefore never looks a bot up by {@code indexOf}.
 */
public class DaaBot extends AutoPlayer {

    private final int number;
    private final DyeColor color;

    public DaaBot(int number) {
        // 0.4 lands the thinking delay around a second, which is brisk without being instant.
        super(0.4f);
        this.number = number;
        this.color = switch (number % 4) {
            case 1 -> DyeColor.ORANGE;
            case 2 -> DyeColor.LIGHT_BLUE;
            case 3 -> DyeColor.PINK;
            default -> DyeColor.LIME;
        };
    }

    /** 1-based, only used to tell the bots apart in chat and on the table labels. */
    public int getNumber() {
        return number;
    }

    @Override
    public Component getName() {
        return Component.translatable("player.daa.bot", number);
    }

    @Override
    public DyeColor getColor() {
        return color;
    }

    @Override
    public int getId() {
        // Negative ids are the wire format for "not a real entity"; the client turns them back into a
        // bot in GameType.getGameForMenu, and DaaGame rebuilds it as a DaaBot.
        return -1 - number;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        return !(other instanceof DaaBot);
    }

    @Override
    public int hashCode() {
        // Identity hash: two bots in the same roster must not collapse into one map key.
        return System.identityHashCode(this);
    }
}
