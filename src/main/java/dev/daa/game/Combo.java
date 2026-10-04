package dev.daa.game;

import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A recognised play: what shape it is, how big, and how strong.
 *
 * <h2>Every pattern in the game, in one ladder</h2>
 *
 * <p>{@link Kind} lists the whole family, strongest first, and {@link #tier} is that order as an
 * integer. Almost the entire legality rule is then {@link #beats}: like beats like, and a bomb beats
 * anything it outranks.
 *
 * <pre>
 *   双大A &gt; 四王 &gt; 三王 &gt; 真八 &gt; 真七 &gt; 假八 &gt; 连珠蛋 &gt; 双王 &gt; 大蛋子 &gt; 蛋子 &gt; 双龙 &gt; 单龙 &gt; 对子 &gt; 单牌
 * </pre>
 *
 * <p>Everything from 蛋子 upwards is a <em>bomb</em>: it can be thrown at a completely different shape.
 * 双龙 and below cannot, which is the rule "蛋子 beats runs, pairs and singles, and nothing below a
 * 蛋子 ever breaks shape".
 *
 * <h2>Two shapes are stronger than their family</h2>
 *
 * <p>A single 王 may only be beaten by a 大蛋子 or better, and a single 大A only by a 大蛋子 of five or
 * more — or by any bomb above it. Both are handled in {@link #beats} rather than by giving them a family
 * of their own, because they <em>are</em> singles and 单王 only ever goes on a single.
 *
 * <h2>Where a pattern comes from</h2>
 *
 * <p>{@link #of} is the only constructor and it recognises whatever the cards happen to be, so the
 * server never has to trust a client's idea of what was played. {@link #isRecycle} is deliberately not
 * part of it: taking a 蛋子 back with a pair of 4s is a rule about the table, not a shape.
 */
public record Combo(Kind kind, int size, int key, boolean bigA, boolean joker) {

    /** Strongest first. {@link #tier} is the ordinal, so the declaration order <em>is</em> the ladder. */
    public enum Kind {
        DOUBLE_BIG_A,
        FOUR_JOKER,
        TRIPLE_JOKER,
        TRUE_EIGHT,
        TRUE_SEVEN,
        DOUBLE_QUAD,
        TRIPLE_STRAIGHT,
        DOUBLE_JOKER,
        BIG_BOMB,
        TRIPLE,
        DOUBLE_STRAIGHT,
        STRAIGHT,
        PAIR,
        SINGLE
    }

    /** A 蛋子 and everything above it may be played against an unrelated shape. */
    private static final Kind WEAKEST_BOMB = Kind.TRIPLE;

    /** A single 大A needs a 大蛋子 this tall before it can be taken at all. */
    private static final int MIN_BOMB_AGAINST_BIG_A = 5;

    public static int tier(Kind kind) {
        return kind.ordinal();
    }

    public static boolean isBomb(Kind kind) {
        return kind.ordinal() <= WEAKEST_BOMB.ordinal();
    }

    /**
     * Recognises {@code cards}, or returns {@code null} when they are not a legal play at all.
     *
     * <p>Checked longest shape first where it matters, and the all-one-rank families before the runs,
     * because four of a kind is a 大蛋子 and not a four card run.
     *
     * @param trump the suit drawn as 大A this board, needed to recognise the pair of 大A
     */
    @Nullable
    public static Combo of(List<Card> cards, @Nullable Suit trump) {
        int n = cards.size();
        if (n == 0) {
            return null;
        }

        // Jokers first: two jokers share the rank "joker" but are two different cards, and a pair of
        // them is a 双王 rather than a pair.
        boolean allJokers = true;
        for (Card card : cards) {
            allJokers &= DaaCards.isJoker(card);
        }
        if (allJokers) {
            int key = DaaCards.isBigJoker(cards.getFirst()) ? DaaCards.V_BIG_JOKER : DaaCards.V_SMALL_JOKER;
            return switch (n) {
                case 1 -> new Combo(Kind.SINGLE, 1, key, false, true);
                case 2 -> new Combo(Kind.DOUBLE_JOKER, 2, 0, false, true);
                case 3 -> new Combo(Kind.TRIPLE_JOKER, 3, 0, false, true);
                case 4 -> new Combo(Kind.FOUR_JOKER, 4, 0, false, true);
                default -> null;
            };
        }

        // The 4 recycle and the 三/四/五王 are the only shapes a joker may sit inside, and both are all
        // one rank, so anything else holding a joker is out.
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        boolean anyJoker = false;
        for (Card card : cards) {
            anyJoker |= DaaCards.isJoker(card);
            counts.merge(DaaCards.groupValue(card), 1, Integer::sum);
        }

        if (counts.size() == 1 && !anyJoker) {
            int key = counts.keySet().iterator().next();
            if (n == 2 && eachBigA(cards, trump)) {
                return new Combo(Kind.DOUBLE_BIG_A, 2, DaaCards.V_BIG_A, true, false);
            }
            return switch (n) {
                case 1 -> new Combo(Kind.SINGLE, 1, key, DaaCards.isBigA(cards.getFirst(), trump), false);
                case 2 -> new Combo(Kind.PAIR, 2, key, false, false);
                case 3 -> new Combo(Kind.TRIPLE, 3, key, false, false);
                // 真七 and 真八 are exactly seven or eight 4s -- the only hands the two decks make
                // possible, and the two strongest things on the board that are not made of jokers or
                // 大A. Seven or eight of anything else is a plain 大蛋子.
                case 7 -> new Combo(DaaCards.isFour(cards.getFirst()) ? Kind.TRUE_SEVEN : Kind.BIG_BOMB,
                        7, key, false, false);
                case 8 -> new Combo(DaaCards.isFour(cards.getFirst()) ? Kind.TRUE_EIGHT : Kind.BIG_BOMB,
                        8, key, false, false);
                default -> new Combo(Kind.BIG_BOMB, n, key, false, false);
            };
        }

        if (anyJoker) {
            return null;
        }
        return run(cards, counts);
    }

    /** A run of equal groups: {@code repeated} cards on each of {@code counts.size()} consecutive steps. */
    @Nullable
    private static Combo run(List<Card> cards, Map<Integer, Integer> counts) {
        int n = cards.size();
        int distinct = counts.size();
        if (distinct < 3 || n % distinct != 0) {
            return null;
        }
        int repeated = n / distinct;
        for (int count : counts.values()) {
            if (count != repeated) {
                return null;
            }
        }
        TreeSet<Integer> steps = new TreeSet<>();
        for (Card card : cards) {
            int step = DaaCards.ladderIndex(card);
            if (step < 0) {
                return null;
            }
            steps.add(step);
        }
        if (steps.size() != distinct || steps.getLast() - steps.getFirst() != distinct - 1) {
            return null;
        }
        int key = DaaCards.runValue(steps.getFirst());
        return switch (repeated) {
            case 1 -> new Combo(Kind.STRAIGHT, n, key, false, false);
            case 2 -> new Combo(Kind.DOUBLE_STRAIGHT, n, key, false, false);
            case 3 -> distinct == 3 ? new Combo(Kind.TRIPLE_STRAIGHT, n, key, false, false) : null;
            case 4 -> distinct == 2 ? new Combo(Kind.DOUBLE_QUAD, n, key, false, false) : null;
            default -> null;
        };
    }

    private static boolean eachBigA(List<Card> cards, @Nullable Suit trump) {
        for (Card card : cards) {
            if (!DaaCards.isBigA(card, trump)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code played} takes {@code target} back off the table instead of beating it.
     *
     * <p>The 4 is the lowest card in the game and the only one with a job beyond being played: a pair of
     * 4s lifts a 蛋子 into the thrower's hand, three 4s lift a four card 大蛋子, and so on — always one
     * 4 fewer than the pile is tall, so a five card 大蛋子 costs four 4s and the eight 4s in the two
     * decks can reach a seven card one.
     */
    public static boolean isRecycle(List<Card> played, @Nullable Combo target) {
        if (target == null || played.size() < 2) {
            return false;
        }
        if (target.kind() != Kind.TRIPLE && target.kind() != Kind.BIG_BOMB) {
            return false;
        }
        if (target.size() != played.size() + 1) {
            return false;
        }
        for (Card card : played) {
            if (!DaaCards.isFour(card)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this play is legal on top of {@code target}, or {@code target} is {@code null} for a lead.
     *
     * <p>Shape for shape the rule is always the same: equal length, and a higher lowest card — except
     * for the three families that are compared by how many cards they are first.
     */
    public boolean beats(@Nullable Combo target) {
        if (target == null) {
            return true;
        }
        if (kind == Kind.DOUBLE_BIG_A) {
            return true;
        }
        if (target.kind == Kind.DOUBLE_BIG_A) {
            return false;
        }

        if (kind == target.kind) {
            return switch (kind) {
                case TRIPLE, BIG_BOMB, TRUE_SEVEN, TRUE_EIGHT ->
                        size != target.size ? size > target.size : key > target.key;
                default -> size == target.size && key > target.key;
            };
        }

        if (!isBomb(kind)) {
            return false;
        }
        if (target.kind == Kind.SINGLE && target.bigA) {
            return (kind == Kind.BIG_BOMB && size >= MIN_BOMB_AGAINST_BIG_A)
                    || tier(kind) < tier(Kind.BIG_BOMB);
        }
        if (target.kind == Kind.SINGLE && target.joker) {
            return tier(kind) <= tier(Kind.BIG_BOMB);
        }
        return tier(kind) < tier(target.kind);
    }

    /** Whether this play is legal on the table, counting the 4 recycle as well as beating. */
    public boolean playableOn(List<Card> cards, @Nullable Combo target) {
        return beats(target) || isRecycle(cards, target);
    }

    /**
     * Every set of cards out of {@code hand} that could legally be played on top of {@code target}.
     *
     * <p>Enumerated by shape rather than by subset: a hand is up to 22 cards, and every legal shape is
     * either a group of equal cards or a run of consecutive ones, so both families can be walked
     * directly. The result is ordered smallest first, which is what a player wants to be shown first.
     */
    public static List<List<Card>> candidates(List<Card> hand, @Nullable Combo target, @Nullable Suit trump) {
        List<List<Card>> found = new ArrayList<>();

        // Equal rank groups: pairs, 蛋子, 大蛋子, 真七, and the pairs of 4s that recycle.
        Map<Integer, List<Card>> byRank = new LinkedHashMap<>();
        for (Card card : hand) {
            byRank.computeIfAbsent(DaaCards.groupValue(card), k -> new ArrayList<>()).add(card);
        }
        for (List<Card> group : byRank.values()) {
            for (int take = 1; take <= group.size(); take++) {
                add(found, new ArrayList<>(group.subList(0, take)), target, trump);
            }
        }

        // Runs of consecutive ladder steps, at every multiplicity the hand can supply.
        Map<Integer, List<Card>> byStep = new HashMap<>();
        for (Card card : hand) {
            int step = DaaCards.ladderIndex(card);
            if (step >= 0) {
                byStep.computeIfAbsent(step, k -> new ArrayList<>()).add(card);
            }
        }
        for (int multiplicity = 1; multiplicity <= 4; multiplicity++) {
            for (int start = 0; start < 13; start++) {
                List<Card> pick = new ArrayList<>();
                for (int step = start; step < 13; step++) {
                    List<Card> group = byStep.get(step);
                    if (group == null || group.size() < multiplicity) {
                        break;
                    }
                    pick.addAll(group.subList(0, multiplicity));
                    add(found, new ArrayList<>(pick), target, trump);
                }
            }
        }

        found.sort((a, b) -> Integer.compare(a.size(), b.size()));
        return found;
    }

    private static void add(List<List<Card>> found, List<Card> pick, @Nullable Combo target, @Nullable Suit trump) {
        Combo combo = of(pick, trump);
        if (combo == null || !combo.playableOn(pick, target)) {
            return;
        }
        for (List<Card> existing : found) {
            if (sameCards(existing, pick)) {
                return;
            }
        }
        found.add(pick);
    }

    private static boolean sameCards(List<Card> a, List<Card> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<Card> copy = new ArrayList<>(b);
        for (Card card : a) {
            if (!copy.remove(card)) {
                return false;
            }
        }
        return copy.isEmpty();
    }

    /** Short name of the shape, for the chat log and the table label. */
    public String translationKey() {
        return "pattern.daa." + kind.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return kind + "(" + size + ", key=" + key + ")";
    }
}
