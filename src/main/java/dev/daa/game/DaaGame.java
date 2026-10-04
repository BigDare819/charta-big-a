package dev.daa.game;

import dev.daa.DaaMod;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.block.entity.CardTableBlockEntity;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.DrawSlot;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.PlaySlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameOption;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.sound.ModSounds;
import dev.lucaargolo.charta.common.utils.CardImage;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Da A: five seats, two decks, and a hidden partner.
 *
 * <h2>The board</h2>
 *
 * <p>The deal draws a suit and the two aces of that suit — the 大A — become the trump of the board. One
 * holder is announced as the 主A and leads; the other is the 次A and stays face down in the roster until
 * their 大A actually hits the table, which is the whole tension of the game and the reason the chat
 * shouts a name the moment a second 大A appears. The other three players are 抓方 and have to find them
 * both.
 *
 * <h2>Phases</h2>
 *
 * <p>{@code DEALING -> PLAY -> RESULT}. {@link #runGame()} is the single entry point and dispatches on
 * the phase; every transition re-enters it.
 *
 * <h2>Why the pile is rewritten rather than appended to</h2>
 *
 * <p>Three separate things put a card on the table — a play, a bomb, and a 4 recycle handing cards back
 * — and they arrive from three different directions. {@link #tableCards} is the one record and
 * {@link #syncPile()} rebuilds the visible slot from it, so the painting can never disagree with the
 * rules about what is actually lying there.
 *
 * <h2>Winning</h2>
 *
 * <p>The first side to get all of its members out wins, but only if the very first player out — the
 * 头贡 — was on that side. If the other side produced the 头贡 the board is a draw, which is what stops
 * a 主A from farming a table by dumping their hand and letting the 抓方 race each other.
 */
public class DaaGame extends Game<DaaGame, DaaMenu> {

    public static final int PLAYERS = DaaCards.PLAYERS;

    /** How long the finished board is held on screen before the result banner appears. */
    private static final int RESULT_LINGER = 60;

    /** How long a completed round is held before the next lead, so the table can be read. */
    private static final int ROUND_LINGER = 22;

    public enum Phase {
        DEALING,
        PLAY,
        RESULT
    }

    public enum Outcome {
        IN_PROGRESS,
        MAIN_A_WINS,
        CATCHERS_WIN,
        DRAW
    }

    // ------------------------------------------------------------------ mirrored state ---

    public static final int SYNC_PHASE = 0;
    public static final int SYNC_SEAT = 1;
    public static final int SYNC_TABLE_SEAT = 2;
    public static final int SYNC_TABLE_KIND = 3;
    public static final int SYNC_TABLE_SIZE = 4;
    public static final int SYNC_TABLE_KEY = 5;
    public static final int SYNC_REVEALED = 6;
    public static final int SYNC_MAIN_A = 7;
    public static final int SYNC_SUB_A = 8;
    public static final int SYNC_TRUMP = 9;
    public static final int SYNC_HEAD = 10;
    public static final int SYNC_FINISHED = 11;
    public static final int SYNC_HAND_BASE = 12;
    public static final int SYNC_SELECT_LO = SYNC_HAND_BASE + PLAYERS;
    public static final int SYNC_SELECT_HI = SYNC_SELECT_LO + PLAYERS;
    public static final int SYNC_OUTCOME = SYNC_SELECT_HI + PLAYERS;
    public static final int SYNC_PASSES = SYNC_OUTCOME + 1;
    public static final int SYNC_ROUND = SYNC_PASSES + 1;
    public static final int SYNC_SIZE = SYNC_ROUND + 1;

    // ------------------------------------------------------------------ options ---

    private final GameOption.Bool BOT_MODE = new GameOption.Bool(
            true,
            Component.translatable("rule.daa.bot_mode"),
            Component.translatable("rule.daa.bot_mode.description"));

    private final GameOption.Number BOT_SKILL = new GameOption.Number(
            1, 0, 2,
            Component.translatable("rule.daa.bot_skill"),
            Component.translatable("rule.daa.bot_skill.description"));

    private final GameOption.Bool REVEAL_SUB_A = new GameOption.Bool(
            false,
            Component.translatable("rule.daa.reveal_sub_a"),
            Component.translatable("rule.daa.reveal_sub_a.description"));

    private final GameOption.Bool SHOW_HINTS = new GameOption.Bool(
            true,
            Component.translatable("rule.daa.show_hints"),
            Component.translatable("rule.daa.show_hints.description"));

    // ------------------------------------------------------------------ state ---

    private final GameSlot dealPile = new GameSlot();
    private final PileSlot pile;
    private final Random random = new Random();

    private final List<Card> tableCards = new ArrayList<>();
    private final Map<CardPlayer, List<Card>> selection = new HashMap<>();

    private Phase phase = Phase.DEALING;
    private Outcome outcome = Outcome.IN_PROGRESS;

    private final boolean[] finished = new boolean[PLAYERS];
    private int headSeat = -1;
    private int seat;

    @Nullable
    private Suit trump;
    private int mainASeat = -1;
    private int subASeat = -1;
    private boolean subARevealed;
    private int revealedMask;

    @Nullable
    private Combo tableCombo;
    private int tableSeat = -1;
    private int passes;
    private int round = 1;

    private boolean awaitingPlay;
    private int lingerTicks;
    @Nullable
    private Runnable afterLinger;

    public DaaGame(List<CardPlayer> players, Deck deck) {
        // A mutable copy: rebuildRoster() adds and removes bot seats in place.
        super(new ArrayList<>(players), deck);

        float centerX = CardTableBlockEntity.TABLE_WIDTH / 2f - CardImage.WIDTH / 2f;
        float centerY = CardTableBlockEntity.TABLE_HEIGHT / 2f - CardImage.HEIGHT / 2f;
        this.pile = addSlot(new PileSlot(this, new LinkedList<>(), centerX, centerY, 0f, 0f, null));
    }

    // ---------------------------------------------------------------------------------------------
    // Seating
    // ---------------------------------------------------------------------------------------------

    /** Seat of {@code player}, by identity; see {@link DaaBot#equals} for why not {@code indexOf}. */
    public int getSeat(@Nullable CardPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i) == player) {
                return i;
            }
        }
        return -1;
    }

    public int seatFrom(int from, int seatOffset) {
        int size = players.size();
        return size == 0 ? 0 : Math.floorMod(from + seatOffset, size);
    }

    public CardPlayer playerAt(int seat) {
        return players.get(Math.floorMod(seat, players.size()));
    }

    /** Seat that owes the next card. */
    public int getSeatOfCurrentPlayer() {
        return seat;
    }

    @Override
    public void setCurrentPlayer(int index) {
        if (players.isEmpty()) {
            return;
        }
        this.seat = Math.floorMod(index, players.size());
        this.currentPlayer = playerAt(this.seat);
    }

    /**
     * Slot type a ring position's hand needs: the fans run across, the two side hands run down.
     *
     * <p>Static and free of client classes so the menu can ask without loading {@code DaaLayout}, which
     * lives in a client package.
     */
    public static CardSlot.Type handType(int ring) {
        return switch (Math.floorMod(ring, PLAYERS)) {
            case 1, 4 -> CardSlot.Type.VERTICAL;
            default -> CardSlot.Type.HORIZONTAL;
        };
    }

    /** Whether {@code seat} has played out. Finished seats are skipped and take no more turns. */
    public boolean isFinished(int seat) {
        return seat >= 0 && seat < finished.length && finished[seat];
    }

    private int activeCount() {
        int count = 0;
        for (int i = 0; i < finished.length; i++) {
            if (!finished[i]) {
                count++;
            }
        }
        return count;
    }

    private static boolean isBot(@Nullable CardPlayer player) {
        return player instanceof DaaBot || (player != null && player.getEntity() == null);
    }

    private void rebuildRoster() {
        if (players.isEmpty()) {
            return;
        }
        while (!players.isEmpty() && isBot(players.getLast())) {
            players.removeLast();
        }
        if (BOT_MODE.get()) {
            DaaAi.Skill skill = DaaAi.Skill.of(BOT_SKILL.get());
            int number = 1;
            while (players.size() < PLAYERS) {
                players.add(new DaaBot(number++, skill));
            }
        }
    }

    @Override
    public void setRawOptions(byte[] options) {
        super.setRawOptions(options);
        rebuildRoster();
    }

    // ---------------------------------------------------------------------------------------------
    // Deck / player validation
    // ---------------------------------------------------------------------------------------------

    @Override
    public Predicate<Deck> getDeckPredicate() {
        // The game needs both decks. Anything with a hundred cards or more and the four standard suits
        // plus the jokers will do, so a renamed or restyled double deck still works.
        return deck -> deck.getCards().size() >= 100
                && deck.getSuits().containsAll(Suits.STANDARD)
                && deck.getCards().stream().anyMatch(DaaCards::isJoker);
    }

    @Override
    public Predicate<Card> getCardPredicate() {
        // Called from the super constructor, so it must not touch instance state.
        return card -> (Suits.STANDARD.contains(card.suit()) && Ranks.STANDARD.contains(card.rank()))
                || DaaCards.isJoker(card);
    }

    @Override
    public int getMinPlayers() {
        return BOT_MODE.get() ? 1 : PLAYERS;
    }

    @Override
    public int getMaxPlayers() {
        return PLAYERS;
    }

    @Override
    public Optional<Component> playerPredicate(List<CardPlayer> players) {
        if (!BOT_MODE.get() && players.size() != PLAYERS) {
            return Optional.of(Component.translatable("message.daa.needs_five"));
        }
        return Optional.empty();
    }

    @Override
    public DaaMenu createMenu(int containerId, Inventory playerInventory, AbstractCardMenu.Definition definition) {
        return new DaaMenu(containerId, playerInventory, definition);
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of(BOT_MODE, BOT_SKILL, REVEAL_SUB_A, SHOW_HINTS);
    }

    @Override
    protected GameSlot createPlayerHand(CardPlayer player) {
        return new HandSlot(this, player, player.hand());
    }

    /** The deck this board was built on, for the suit and card colours the messages are written in. */
    public Deck getDeck() {
        return deck;
    }

    // ---------------------------------------------------------------------------------------------
    // Game flow
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (lingerTicks > 0) {
            // Hold the finished round or the final result on the table; freeze the bots meanwhile so
            // they cannot start the next thing early.
            if (--lingerTicks == 0 && afterLinger != null) {
                Runnable action = afterLinger;
                afterLinger = null;
                action.run();
            }
            return;
        }
        super.tick();
    }

    private void linger(int ticks, Runnable action) {
        this.afterLinger = action;
        this.lingerTicks = ticks;
    }

    @Override
    public void startGame() {
        dealPile.clear();
        pile.clear();
        tableCards.clear();
        selection.clear();
        Arrays.fill(finished, false);
        headSeat = -1;
        mainASeat = -1;
        subASeat = -1;
        subARevealed = false;
        revealedMask = 0;
        trump = null;
        tableCombo = null;
        tableSeat = -1;
        passes = 0;
        round = 1;
        awaitingPlay = false;
        lingerTicks = 0;
        afterLinger = null;
        outcome = Outcome.IN_PROGRESS;
        phase = Phase.DEALING;

        for (CardPlayer player : players) {
            player.resetPlay();
            getPlayerHand(player).clear();
            syncCensored(player);
        }

        // A re-deal hands back cards that are already face up; put them face down first.
        for (Card card : gameDeck) {
            if (!card.flipped()) {
                card.flip();
            }
        }
        dealPile.addAll(gameDeck);
        dealPile.shuffle();

        // Deal round-robin, one card per scheduled tick, so the table animates. 108 cards is 108 ticks
        // of dealing, which is the one part of the game worth watching in full.
        int start = players.isEmpty() ? 0 : random.nextInt(players.size());
        for (int i = 0; i < gameDeck.size(); i++) {
            CardPlayer receiver = playerAt(start + i);
            scheduledActions.add(() -> {
                receiver.playSound(ModSounds.CARD_DRAW.get());
                dealCards(dealPile, receiver, 1);
            });
        }

        isGameReady = false;
        isGameOver = false;
        setCurrentPlayer(start);

        table(Component.translatable("message.daa.game_started"));
    }

    @Override
    public void runGame() {
        if (!isGameReady || isGameOver || awaitingPlay || currentPlayer == null || players.isEmpty()) {
            return;
        }
        switch (phase) {
            case DEALING -> beginPlay();
            case PLAY -> awaitPlay();
            case RESULT -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The 大A draw
    // ---------------------------------------------------------------------------------------------

    private void beginPlay() {
        for (CardPlayer player : players) {
            sortHand(player);
        }

        // The board's trump: a suit is drawn and its two aces are the 大A.
        List<Suit> suits = new ArrayList<>(Suits.STANDARD);
        trump = suits.get(random.nextInt(suits.size()));

        List<Integer> holders = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            CardPlayer player = players.get(i);
            for (Card card : getPlayerHand(player).getCards()) {
                if (DaaCards.isBigA(card, trump)) {
                    holders.add(i);
                    break;
                }
            }
        }

        if (holders.isEmpty()) {
            // Only reachable if a custom deck dropped the aces; fall back to the whole table.
            mainASeat = 0;
        } else if (holders.size() == 1) {
            mainASeat = holders.getFirst();
        } else {
            // Two holders: the one nearer the dealer is the 主A, the other hides as the 次A.
            int dealer = seat;
            holders.sort(Comparator.comparingInt(s -> Math.floorMod(s - dealer, players.size())));
            mainASeat = holders.get(0);
            subASeat = holders.get(1);
        }

        reveal(mainASeat);
        phase = Phase.PLAY;
        round = 1;

        table(Component.translatable("message.daa.trump_is", Component.translatable(deck.getSuitTranslatableKey(trump))));
        table(Component.translatable("message.daa.main_a_is", playerAt(mainASeat).getColoredName()));
        if (subASeat < 0) {
            table(Component.translatable("message.daa.double_a"));
        } else if (REVEAL_SUB_A.get()) {
            subARevealed = true;
            reveal(subASeat);
            table(Component.translatable("message.daa.sub_a_is", playerAt(subASeat).getColoredName()));
        } else {
            table(Component.translatable("message.daa.sub_a_hidden"));
        }

        setCurrentPlayer(mainASeat);
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
        awaitPlay();
    }

    private void reveal(int seat) {
        if (seat >= 0 && seat < PLAYERS) {
            revealedMask |= 1 << seat;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Play
    // ---------------------------------------------------------------------------------------------

    private void awaitPlay() {
        CardPlayer actor = currentPlayer;
        if (actor == null || phase != Phase.PLAY) {
            return;
        }
        awaitingPlay = true;
        actor.afterPlay(play -> {
            awaitingPlay = false;
            actor.resetPlay();
            if (phase != Phase.PLAY || isGameOver) {
                return;
            }
            if (play == null || play.cards().isEmpty()) {
                applyPass(actor);
            } else {
                applyPlay(actor, new ArrayList<>(play.cards()));
            }
        });
    }

    /** Hands a play to the server, from the screen's play button. */
    public void submitPlay(CardPlayer player, List<Card> cards) {
        if (phase != Phase.PLAY || isGameOver || !awaitingPlay || player != currentPlayer) {
            return;
        }
        player.play(new GamePlay(new ArrayList<>(cards), pile.getIndex()));
    }

    /** Hands a pass to the server, from the screen's pass button. */
    public void submitPass(CardPlayer player) {
        if (phase != Phase.PLAY || isGameOver || !awaitingPlay || player != currentPlayer) {
            return;
        }
        // An empty play is the wire format for a pass; the AI-independent path means a leading player
        // who passes simply hands the lead on.
        player.play(new GamePlay(List.of(), pile.getIndex()));
    }

    private void applyPass(CardPlayer actor) {
        passes++;
        table(Component.translatable("message.daa.passed", actor.getColoredName()));
        if (tableCombo == null) {
            // Nobody led: hand the lead on rather than stalling.
            table(Component.translatable("message.daa.lead_skipped", actor.getColoredName()));
            advanceTo(nextActive(seat));
            return;
        }
        afterTurn();
    }

    private void applyPlay(CardPlayer actor, List<Card> cards) {
        GameSlot hand = getPlayerHand(actor);
        if (cards.stream().anyMatch(card -> !hand.contains(card))) {
            table(Component.translatable("message.daa.not_in_hand", actor.getColoredName()));
            actor.resetPlay();
            awaitPlay();
            return;
        }

        Combo combo = Combo.of(cards, trump);
        boolean recycle = Combo.isRecycle(cards, tableCombo);
        if (combo == null || (!combo.beats(tableCombo) && !recycle)) {
            table(Component.translatable("message.daa.illegal_play", actor.getColoredName()));
            actor.resetPlay();
            awaitPlay();
            return;
        }

        // Take the cards out by value: the deck carries two of everything, so identity is not unique.
        for (Card card : cards) {
            hand.remove(card);
        }
        // A 大A on the table has just outed its holder.
        if (!subARevealed && subASeat >= 0 && cards.stream().anyMatch(card -> DaaCards.isBigA(card, trump))) {
            if (seat == subASeat) {
                subARevealed = true;
                reveal(subASeat);
                table(Component.translatable("message.daa.sub_a_revealed", actor.getColoredName()));
            }
        }

        if (recycle) {
            List<Card> taken = new ArrayList<>(tableCards);
            getPlayerHand(actor).addAll(taken);
            table(Component.translatable("message.daa.recycled", actor.getColoredName(), taken.size()));
        } else {
            tableCards.clear();
        }
        tableCards.addAll(cards);
        tableCombo = combo;
        tableSeat = seat;
        passes = 0;
        syncPile();
        syncCensored(actor);

        selection.getOrDefault(actor, new ArrayList<>()).clear();
        actor.playSound(ModSounds.CARD_PLAY.get());
        table(Component.translatable("message.daa.played", actor.getColoredName(),
                cardList(cards), Component.translatable(combo.translationKey())));

        if (getPlayerHand(actor).isEmpty()) {
            finished[seat] = true;
            if (headSeat < 0) {
                headSeat = seat;
                table(Component.translatable("message.daa.head", actor.getColoredName()));
            } else {
                table(Component.translatable("message.daa.went_out", actor.getColoredName()));
            }
            if (checkOver()) {
                return;
            }
            // Whoever is left carries on, led by the next player after the one who just went out.
            if (tableCombo != null && tableSeat == seat) {
                // Their last throw still has to be answered before the lead moves on.
                afterTurn();
                return;
            }
            advanceTo(nextActive(seat));
            return;
        }

        afterTurn();
    }

    /** Moves the turn on, or closes the round when everybody else has passed. */
    private void afterTurn() {
        int active = activeCount();
        if (tableCombo != null && passes >= active - 1) {
            closeRound();
            return;
        }
        advanceTo(nextActive(seat));
    }

    private void closeRound() {
        // The last player to have thrown leads again -- unless they went out on that throw, in which case
        // the lead closes over them like any other turn.
        int leader = tableSeat >= 0 && !finished[tableSeat] ? tableSeat : nextActive(Math.max(0, tableSeat));
        tableCombo = null;
        tableCards.clear();
        passes = 0;
        round++;
        syncPile();
        linger(ROUND_LINGER, () -> {
            setCurrentPlayer(leader);
            table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
            runGame();
        });
    }

    /** The next seat after {@code from} that still holds cards. */
    private int nextActive(int from) {
        int size = players.size();
        for (int step = 1; step <= size; step++) {
            int candidate = Math.floorMod(from + step, size);
            if (!finished[candidate]) {
                return candidate;
            }
        }
        return Math.floorMod(from, size);
    }

    private void advanceTo(int nextSeat) {
        setCurrentPlayer(nextSeat);
        table(Component.translatable("message.charta.its_player_turn", currentPlayer.getColoredName()));
        runGame();
    }

    private boolean checkOver() {
        boolean anyMainLeft = mainASeat >= 0 && !finished[mainASeat];
        boolean anySubLeft = subASeat >= 0 && !finished[subASeat];
        boolean anyCatcherLeft = false;
        for (int i = 0; i < players.size(); i++) {
            if (i != mainASeat && i != subASeat && !finished[i]) {
                anyCatcherLeft = true;
                break;
            }
        }

        boolean mainOut = !anyMainLeft && !anySubLeft;
        boolean catchersOut = !anyCatcherLeft;
        if (!mainOut && !catchersOut) {
            return false;
        }

        boolean headIsMain = headSeat == mainASeat || (subASeat >= 0 && headSeat == subASeat);
        if (mainOut) {
            outcome = headIsMain ? Outcome.MAIN_A_WINS : Outcome.DRAW;
        } else {
            outcome = headIsMain ? Outcome.DRAW : Outcome.CATCHERS_WIN;
        }
        endGame();
        return true;
    }

    @Override
    public void endGame() {
        if (isGameOver) {
            return;
        }
        isGameOver = true;
        phase = Phase.RESULT;
        awaitingPlay = false;
        syncPile();

        Component banner = switch (outcome) {
            case MAIN_A_WINS -> Component.translatable("message.daa.main_a_wins");
            case CATCHERS_WIN -> Component.translatable("message.daa.catchers_win");
            case DRAW -> Component.translatable("message.daa.draw");
            default -> Component.translatable("message.daa.aborted");
        };
        table(banner);
        for (CardPlayer player : players) {
            player.sendTitle(banner, Component.translatable("message.daa.result_subtitle",
                    playerAt(Math.max(0, headSeat)).getColoredName()));
        }
        linger(RESULT_LINGER, () -> {
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean canPlay(CardPlayer player, GamePlay play) {
        if (phase != Phase.PLAY || isGameOver || player != currentPlayer) {
            return false;
        }
        List<Card> cards = play.cards();
        if (cards.isEmpty()) {
            return false;
        }
        GameSlot hand = getPlayerHand(player);
        if (cards.stream().anyMatch(card -> !hand.contains(card))) {
            return false;
        }
        Combo combo = Combo.of(cards, trump);
        return combo != null && (combo.beats(tableCombo) || Combo.isRecycle(cards, tableCombo));
    }

    @Override
    public @Nullable GamePlay getBestPlay(CardPlayer player) {
        if (phase != Phase.PLAY || player != currentPlayer) {
            return null;
        }
        // A real player driven by Charta's own auto-play falls back to the middle difficulty; a bot
        // carries the strength it was built with, which is the option as it stood when the roster was
        // built.
        DaaAi.Skill skill = player instanceof DaaBot bot ? bot.getSkill() : DaaAi.Skill.NORMAL;
        List<Card> chosen = DaaAi.choose(this, player, skill);
        if (chosen.isEmpty()) {
            return tableCombo == null ? null : new GamePlay(List.of(), pile.getIndex());
        }
        return new GamePlay(chosen, pile.getIndex());
    }

    // ---------------------------------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------------------------------

    /** Toggles the card at {@code index} of {@code player}'s own hand in their selection. */
    public void toggleSelection(CardPlayer player, int index) {
        if (phase != Phase.PLAY || isGameOver || player != currentPlayer) {
            return;
        }
        List<Card> hand = new ArrayList<>();
        getPlayerHand(player).forEach(hand::add);
        if (index < 0 || index >= hand.size()) {
            return;
        }
        Card card = hand.get(index);
        List<Card> selected = selection.computeIfAbsent(player, p -> new ArrayList<>());
        if (!selected.remove(card)) {
            selected.add(card);
        }
    }

    public void clearSelection(CardPlayer player) {
        selection.computeIfAbsent(player, p -> new ArrayList<>()).clear();
    }

    /** The selection of {@code player}, in the order the cards sit in their hand. */
    public List<Card> selectedCards(CardPlayer player) {
        List<Card> selected = selection.get(player);
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<Card> pool = new ArrayList<>(selected);
        List<Card> ordered = new ArrayList<>();
        for (Card card : getPlayerHand(player).getCards()) {
            if (pool.remove(card)) {
                ordered.add(card);
            }
        }
        return ordered;
    }

    /** Bitmask of the selected cards of {@code seat}, over the order of that seat's hand. */
    public int selectionMask(int seat, boolean high) {
        if (seat < 0 || seat >= players.size()) {
            return 0;
        }
        List<Card> pool = new ArrayList<>(selection.getOrDefault(players.get(seat), List.of()));
        int mask = 0;
        int index = 0;
        for (Card card : getPlayerHand(players.get(seat)).getCards()) {
            if (pool.remove(card)) {
                int bit = index - (high ? 16 : 0);
                if (bit >= 0 && bit < 16) {
                    mask |= 1 << bit;
                }
            }
            index++;
        }
        return mask;
    }

    // ---------------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------------

    private void syncPile() {
        pile.setCards(new ArrayList<>(tableCards));
    }

    /** Keeps the face-down hand-size mirror in step after a programmatic hand change. */
    private void syncCensored(CardPlayer player) {
        GameSlot placeholders = censoredHands.get(player);
        if (placeholders == null || placeholders == getPlayerHand(player)) {
            return;
        }
        List<Card> blanks = new ArrayList<>();
        for (int i = 0; i < getPlayerHand(player).size(); i++) {
            blanks.add(new Card());
        }
        placeholders.setCards(blanks);
    }

    private Component cardList(List<Card> cards) {
        Component line = Component.empty();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                line = line.copy().append(Component.literal(" "));
            }
            Card card = cards.get(i);
            line = line.copy().append(Component.translatable(deck.getCardTranslatableKey(card))
                    .withColor(deck.getCardColor(card)));
        }
        return line;
    }

    /**
     * Sorts a hand the way a Da A player holds it: strongest first.
     *
     * <p>Mutates the same list the entity handed to the slot, so a click's position in the fan means the
     * same thing on both sides. Sorting by strength is what makes a pair, a 蛋子 or a run a contiguous
     * run of the fan, which is what the whole click-to-select interaction rests on.
     */
    private void sortHand(CardPlayer player) {
        @SuppressWarnings("unchecked")
        List<Card> hand = (List<Card>) getPlayerHand(player).getCards();
        hand.sort(Comparator.comparingInt((Card card) -> DaaCards.sortKey(card, trump)).reversed());
    }

    // ---------------------------------------------------------------------------------------------
    // Queries used by the menu and the screen
    // ---------------------------------------------------------------------------------------------

    public Phase getPhase() {
        return phase;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    @Nullable
    public Suit getTrump() {
        return trump;
    }

    public int getMainASeat() {
        return mainASeat;
    }

    public int getSubASeat() {
        return subASeat;
    }

    public boolean isRevealed(int seat) {
        return seat >= 0 && seat < PLAYERS && (revealedMask & (1 << seat)) != 0;
    }

    public boolean isMainASide(int seat) {
        return seat == mainASeat || (subASeat >= 0 && seat == subASeat);
    }

    public int getHeadSeat() {
        return headSeat;
    }

    public int getTableSeat() {
        return tableSeat;
    }

    @Nullable
    public Combo getTableCombo() {
        return tableCombo;
    }

    public GameSlot getPile() {
        return pile;
    }

    public int getPasses() {
        return passes;
    }

    public int getRound() {
        return round;
    }

    public boolean isShowingHints() {
        return SHOW_HINTS.get();
    }

    /**
     * Value of one mirrored data slot.
     *
     * <p>Called on the server for every slot on every tick, so it must stay cheap and side effect free.
     */
    public int syncValue(int index) {
        if (index >= SYNC_SELECT_HI) {
            int seat = index - SYNC_SELECT_HI;
            return seat < PLAYERS ? selectionMask(seat, true) : 0;
        }
        if (index >= SYNC_SELECT_LO) {
            int seat = index - SYNC_SELECT_LO;
            return seat < PLAYERS ? selectionMask(seat, false) : 0;
        }
        if (index >= SYNC_HAND_BASE) {
            int seat = index - SYNC_HAND_BASE;
            return seat < players.size() ? getPlayerHand(players.get(seat)).size() : 0;
        }
        return switch (index) {
            case SYNC_PHASE -> phase.ordinal();
            case SYNC_SEAT -> seat;
            case SYNC_TABLE_SEAT -> tableSeat;
            case SYNC_TABLE_KIND -> tableCombo == null ? 0 : tableCombo.kind().ordinal() + 1;
            case SYNC_TABLE_SIZE -> tableCombo == null ? 0 : tableCombo.size();
            case SYNC_TABLE_KEY -> tableCombo == null ? 0 : tableCombo.key();
            case SYNC_REVEALED -> revealedMask;
            case SYNC_MAIN_A -> mainASeat;
            case SYNC_SUB_A -> subARevealed ? subASeat : -1;
            case SYNC_TRUMP -> trump == null ? -1 : Suits.STANDARD.indexOf(trump);
            case SYNC_HEAD -> headSeat;
            case SYNC_FINISHED -> mask(finished);
            case SYNC_OUTCOME -> outcome.ordinal();
            case SYNC_PASSES -> passes;
            case SYNC_ROUND -> round;
            default -> 0;
        };
    }

    private static int mask(boolean[] values) {
        int mask = 0;
        for (int i = 0; i < values.length && i < 16; i++) {
            if (values[i]) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** A hand: remembers where the carried card came from, and refuses it everywhere else. */
    private static final class HandSlot extends GameSlot {

        private final DaaGame game;
        private final CardPlayer owner;

        private HandSlot(DaaGame game, CardPlayer owner, List<Card> cards) {
            super(cards);
            this.game = game;
            this.owner = owner;
        }

        @Override
        public boolean canInsertCard(CardPlayer player, List<Card> cards, int index) {
            return player == owner;
        }

        @Override
        public boolean canRemoveCard(CardPlayer player, int index) {
            return !isEmpty() && player == owner;
        }

        /** A click takes the whole selection, not the rest of the hand. */
        @Override
        public boolean removeAll() {
            return false;
        }
    }

    /**
     * The table pile.
     *
     * <p>Reports itself empty so {@code GameScreen} keeps its own single-card widget out of the way:
     * a play is up to eight cards and needs one cell each, so {@code DaaScreen} paints it.
     */
    private static final class PileSlot extends PlaySlot {

        private PileSlot(Game<?, ?> game, List<Card> cards, float x, float y, float z, float angle,
                         @Nullable DrawSlot drawSlot) {
            super(game, cards, x, y, z, angle, drawSlot);
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }

    /** Unused, but keeps the imports honest for the helper methods above. */
    static {
        assert ChartaMod.MISSING_CARD != null : DaaMod.MOD_ID;
        assert new HashSet<>(Suits.STANDARD).size() == 4;
        assert Ranks.ACE != null;
    }
}
