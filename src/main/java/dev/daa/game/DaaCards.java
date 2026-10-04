package dev.daa.game;

import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import org.jetbrains.annotations.Nullable;

/**
 * Every rule about a single card: what it is worth, how it is sorted, and whether it is one of the two
 * special cards the whole game turns on.
 *
 * <h2>The ranking is not the deck's ranking</h2>
 *
 * <p>Da A is a climbing game with a deliberately surprising ladder. The whole point of it — and the
 * reason 4 is the lowest card while 3 is the second highest — is that the cards you would throw away in
 * any other game are the ones that win here:
 *
 * <pre>
 *   大A  >  大王  >  小王  >  3  >  2  >  A  >  K  >  Q  >  J  >  10  >  ...  >  5  >  4
 * </pre>
 *
 * <p>{@link #value} is that ladder as an integer, and it is the <em>only</em> comparison used anywhere
 * in the rules. Note that it is not a total order over card identity: the two 大A are an ordinary
 * {@code A} for every purpose except being played on their own, which is why {@link #groupValue} exists
 * beside it. A run of 4-5-6-7-8 that happens to contain the 大A still measures as an ace, and a pair of
 * aces where only one of them is the 大A is a plain pair.
 *
 * <h2>Runs wrap</h2>
 *
 * <p>Straights are read off {@link #ladderIndex} on the chain
 * {@code 4 5 6 7 8 9 10 J Q K A 2 3} — the same order as the ladder, so a straight's strength is the
 * strength of its lowest card and {@code 2 3} sit above the ace instead of below it.
 */
public final class DaaCards {

    /** Seats at a table. Da A is a five hand game, and bots pad the chairs out to five. */
    public static final int PLAYERS = 5;

    /** What a player is dealt. 108 cards over five hands is 21 or 22 each. */
    public static final int DECK_SIZE = 108;

    public static final int V_BIG_A = 16;
    public static final int V_BIG_JOKER = 15;
    public static final int V_SMALL_JOKER = 14;
    public static final int V_THREE = 13;
    public static final int V_TWO = 12;
    public static final int V_ACE = 11;

    /** The chain a straight is measured on, low to high: {@code 4 ... K A 2 3}. */
    private static final int[] LADDER = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1, 2, 3};

    private DaaCards() {
    }

    /** 大王: a joker in spades. The suit is meaningless, it is only how the two jokers are told apart. */
    public static boolean isBigJoker(@Nullable Card card) {
        return card != null && card.rank() == Ranks.JOKER && card.suit() == Suits.SPADES;
    }

    /** 小王: a joker in clubs. */
    public static boolean isSmallJoker(@Nullable Card card) {
        return card != null && card.rank() == Ranks.JOKER && card.suit() == Suits.CLUBS;
    }

    public static boolean isJoker(@Nullable Card card) {
        return card != null && card.rank() == Ranks.JOKER;
    }

    /** The one wild card of the game: it takes back whatever it is thrown at. */
    public static boolean isFour(@Nullable Card card) {
        return card != null && card.rank() == Ranks.FOUR;
    }

    /** The trump of the board: the two aces of the suit that was drawn at the deal. */
    public static boolean isBigA(@Nullable Card card, @Nullable Suit trump) {
        return card != null && trump != null && card.rank() == Ranks.ACE && card.suit() == trump;
    }

    /**
     * The card's strength, {@code 大A} being the highest on the board.
     *
     * <p>Only meaningful for a card standing on its own — see {@link #groupValue} for anything that
     * groups cards by rank.
     */
    public static int value(@Nullable Card card, @Nullable Suit trump) {
        if (card == null) {
            return 0;
        }
        if (isBigJoker(card)) {
            return V_BIG_JOKER;
        }
        if (isSmallJoker(card)) {
            return V_SMALL_JOKER;
        }
        return isBigA(card, trump) ? V_BIG_A : groupValue(card);
    }

    /**
     * The card's strength ignoring the 大A bonus, i.e. the value its <em>rank</em> has.
     *
     * <p>What pairs, triples, bombs and runs compare with: a pair of aces is a pair of aces whether or
     * not one of them happens to be the board's trump, and only the pair of two 大A is special.
     */
    public static int groupValue(@Nullable Card card) {
        if (card == null) {
            return 0;
        }
        return switch (card.rank() == Ranks.JOKER ? "joker" : "rank") {
            case "joker" -> isBigJoker(card) ? V_BIG_JOKER : V_SMALL_JOKER;
            default -> standardValue(card);
        };
    }

    private static int standardValue(Card card) {
        if (card.rank() == Ranks.THREE) {
            return V_THREE;
        }
        if (card.rank() == Ranks.TWO) {
            return V_TWO;
        }
        if (card.rank() == Ranks.ACE) {
            return V_ACE;
        }
        return card.rank().ordinal();
    }

    /**
     * Position of the card on the run chain, or {@code -1} for a joker.
     *
     * <p>{@code 4} is 0 and {@code 3} is 12, so two runs of the same length compare by subtracting these
     * and nothing else has to know that the chain wraps.
     */
    public static int ladderIndex(@Nullable Card card) {
        if (card == null || card.rank() == Ranks.JOKER) {
            return -1;
        }
        int rank = card.rank() == Ranks.ACE ? 1 : card.rank().ordinal();
        for (int i = 0; i < LADDER.length; i++) {
            if (LADDER[i] == rank) {
                return i;
            }
        }
        return -1;
    }

    /** The value of the lowest card of a run, which is what a run is compared by. */
    public static int runValue(int ladderIndex) {
        return ladderIndex + 1;
    }

    /**
     * How a hand is held: highest first, and inside a rank the deck's own suit order.
     *
     * <p>Sorting by strength rather than by suit matters more here than in most games, because the two
     * hands of a pair are adjacent in the fan and a 蛋子, a 大蛋子 and a run are all contiguous runs of
     * the sorted hand — which is what makes "click three cards in a row" a triple.
     */
    public static int sortKey(Card card, Suit trump) {
        return (value(card, trump) << 3) + Suits.STANDARD.indexOf(card.suit());
    }
}
