package dev.daa.game;

import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * What a bot plays.
 *
 * <h2>Shape of the decision</h2>
 *
 * <p>Two very different problems. <b>Leading</b> is a choice out of everything the hand can make and is
 * played by dumping the cheapest thing that is not worth keeping. <b>Following</b> is a choice out of
 * the candidates that beat the pile, and is played mostly by finding the cheapest one that still wins.
 *
 * <h2>What it holds back</h2>
 *
 * <p>Four cards are worth more than their rank here — a 大A wins a trick practically by itself, a 王
 * beats everything below a 大蛋子, and a 4 is the only card that can take a pile back. So they are
 * priced above their face value when the bot is choosing what to spend, and it will happily lead a
 * three instead of touching them.
 *
 * <h2>Partner</h2>
 *
 * <p>In a hidden-partner game a bot that beats its own partner is throwing a card away, so a pile held
 * by a known partner is normally left alone. The 主A is always known, and the 次A becomes known the
 * moment their 大A hits the table.
 *
 * <h2>Three strengths</h2>
 *
 * <p>{@link Skill} is the 人机模式 difficulty. {@code RELAXED} plays greedily with no partner
 * awareness at all — it beats whatever is in front of it whenever it legally can — {@code NORMAL} is
 * the partner-aware game described above, and {@code FIERCE} also takes every 4 recycle it is offered
 * and spends a bomb the moment an opponent is down to their last two cards.
 */
public final class DaaAi {

    /** How hard a bot plays. Chosen on the table screen; see {@code rule.daa.bot_skill}. */
    public enum Skill {
        /** Cheap and blind: always the smallest legal play, never saves anything. */
        RELAXED,
        /** Partner aware, holds the four special cards for their moment. */
        NORMAL,
        /** Takes every recycle, bombs a player who is about to go out. */
        FIERCE;

        public static Skill of(int ordinal) {
            Skill[] values = values();
            return values[Math.floorMod(ordinal, values.length)];
        }
    }

    /** Added to a card's key when deciding whether it is cheap enough to spend. */
    private static final int BIG_A_PRICE = 6;
    private static final int JOKER_PRICE = 3;
    /** A pile this low is worth taking back even from a partner. */
    private static final int JUNK_KEY = 6;

    private static final Random RANDOM = new Random();

    private DaaAi() {
    }

    /** The cards to play, or an empty list to pass. */
    public static List<Card> choose(DaaGame game, CardPlayer me) {
        return choose(game, me, Skill.NORMAL);
    }

    /** The cards to play, or an empty list to pass. */
    public static List<Card> choose(DaaGame game, CardPlayer me, Skill skill) {
        int seat = game.getSeat(me);
        if (seat < 0) {
            return List.of();
        }
        List<Card> hand = new ArrayList<>();
        game.getPlayerHand(me).forEach(hand::add);
        if (hand.isEmpty()) {
            return List.of();
        }
        Combo target = game.getTableCombo();

        if (target == null) {
            return lead(game, seat, hand, skill);
        }
        return follow(game, seat, hand, target, skill);
    }

    // ---------------------------------------------------------------------------------------------
    // Leading
    // ---------------------------------------------------------------------------------------------

    private static List<Card> lead(DaaGame game, int seat, List<Card> hand, Skill skill) {
        List<List<Card>> options = Combo.candidates(hand, null, game.getTrump());
        if (options.isEmpty()) {
            return List.of(hand.getFirst());
        }
        if (skill == Skill.RELAXED) {
            // No pricing at all: lead the shortest thing that is not a bomb, ties broken at random.
            List<Card> smallest = null;
            for (List<Card> option : options) {
                Combo combo = Combo.of(option, game.getTrump());
                if (combo == null || Combo.isBomb(combo.kind())) {
                    continue;
                }
                if (smallest == null || option.size() < smallest.size()) {
                    smallest = option;
                }
            }
            return smallest != null ? smallest : options.getFirst();
        }

        // Leading with the whole count of a rank gets it out of the hand in one move, which is what
        // makes a hand shrink; a run does the same for several ranks at once.
        List<Card> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (List<Card> option : options) {
            Combo combo = Combo.of(option, game.getTrump());
            if (combo == null || Combo.isBomb(combo.kind())) {
                continue;
            }
            int score = price(option, game, combo);
            // Prefer a bigger dump when the price is close: three junk cards out of the hand is worth
            // more than one.
            score -= option.size() * 2;
            if (score < bestScore) {
                bestScore = score;
                best = option;
            }
        }
        if (best != null) {
            return best;
        }

        // Only bombs left: lead the weakest one rather than hoarding until the end.
        for (List<Card> option : options) {
            return option;
        }
        return List.of(hand.getFirst());
    }

    // ---------------------------------------------------------------------------------------------
    // Following
    // ---------------------------------------------------------------------------------------------

    private static List<Card> follow(DaaGame game, int seat, List<Card> hand, Combo target, Skill skill) {
        List<List<Card>> options = Combo.candidates(hand, target, game.getTrump());
        if (options.isEmpty()) {
            return List.of();
        }

        if (skill == Skill.RELAXED) {
            // Beats whatever it legally can, cheapest first, and falls back to the cheapest bomb --
            // including a 4 recycle, which it cannot tell apart from a bomb.
            List<Card> plain = cheapest(options, game, target, false);
            if (plain != null) {
                return plain;
            }
            List<Card> bomb = cheapest(options, game, target, true);
            return bomb != null ? bomb : List.of();
        }

        // FIERCE always takes a recycle: it is the only play in the game that leaves the hand bigger
        // than it found it, so there is no pile worth more than it.
        if (skill == Skill.FIERCE) {
            List<Card> recycle = null;
            for (List<Card> option : options) {
                if (Combo.isRecycle(option, target)) {
                    recycle = option;
                    break;
                }
            }
            if (recycle != null) {
                return recycle;
            }
        }

        boolean partnerHolds = game.isMainASide(seat) && game.isRevealed(game.getTableSeat())
                && game.isMainASide(game.getTableSeat());
        int active = 0;
        for (int i = 0; i < DaaGame.PLAYERS; i++) {
            if (!game.isFinished(i)) {
                active++;
            }
        }
        // Whoever is left to act after us still gets a chance to beat a partner's pile.
        int stillToAct = Math.max(0, active - 1 - game.getPasses());
        boolean pileClosing = stillToAct == 0;

        if (partnerHolds && !pileClosing) {
            // A partner's pile is usually worth keeping: only take it over when the pile is junk, or
            // when taking it is nearly free. A fierce bot is more willing to take over.
            int leash = skill == Skill.FIERCE ? JUNK_KEY + 3 : JUNK_KEY;
            if (target.key() > leash && !Combo.isBomb(target.kind())) {
                return List.of();
            }
            List<Card> cheapest = cheapest(options, game, target, false);
            if (cheapest != null && price(cheapest, game, Combo.of(cheapest, game.getTrump()))
                    <= (skill == Skill.FIERCE ? 4 : 2)) {
                return cheapest;
            }
            return List.of();
        }

        // Bomb when an opponent is nearly out: letting them shed their last cards decides the board.
        boolean opponentClose = false;
        for (int i = 0; i < DaaGame.PLAYERS; i++) {
            if (i == seat || game.isFinished(i) || game.isMainASide(i) == game.isMainASide(seat)) {
                continue;
            }
            if (game.getPlayerHand(game.playerAt(i)).size() <= (skill == Skill.FIERCE ? 4 : 2)) {
                opponentClose = true;
            }
        }

        List<Card> plain = cheapest(options, game, target, false);
        if (plain != null && !opponentClose) {
            return plain;
        }
        if (plain != null && target.key() < 11 && skill != Skill.FIERCE) {
            // Nothing dangerous on the pile; saving the bombs is still fine.
            return plain;
        }

        List<Card> bomb = cheapest(options, game, target, true);
        if (bomb != null) {
            return bomb;
        }
        return plain != null ? plain : List.of();
    }

    /** The cheapest candidate, optionally restricted to bombs (or to non-bombs). */
    @Nullable
    private static List<Card> cheapest(List<List<Card>> options, DaaGame game, Combo target, boolean bombsOnly) {
        List<Card> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (List<Card> option : options) {
            Combo combo = Combo.of(option, game.getTrump());
            if (combo == null) {
                continue;
            }
            boolean isBomb = Combo.isBomb(combo.kind()) && !Combo.isRecycle(option, target);
            if (bombsOnly != isBomb) {
                continue;
            }
            int score = price(option, game, combo);
            if (score < bestScore) {
                bestScore = score;
                best = option;
            }
        }
        return best;
    }

    /**
     * What a set of cards is worth to spend: its own strength, plus a surcharge on the three kinds of
     * card that are worth more than their rank.
     */
    private static int price(List<Card> cards, DaaGame game, @Nullable Combo combo) {
        int score = combo == null ? 0 : combo.key() * 2 + combo.size();
        for (Card card : cards) {
            if (DaaCards.isBigA(card, game.getTrump())) {
                score += BIG_A_PRICE;
            } else if (DaaCards.isJoker(card)) {
                score += JOKER_PRICE;
            } else if (DaaCards.isFour(card)) {
                // The only card that can take a pile back; keep a pair of them together.
                score += 1;
            }
        }
        // A little noise keeps two bots at the same table from playing the same hand.
        return score + RANDOM.nextInt(2);
    }

    /** Sorts a hand for display, weakest first — used by the debug dump. */
    public static List<Card> displayOrder(List<Card> hand, DaaGame game) {
        List<Card> copy = new ArrayList<>(hand);
        copy.sort(Comparator.comparingInt(card -> DaaCards.value(card, game.getTrump())));
        return copy;
    }
}
