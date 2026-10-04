package dev.daa.game;

import dev.daa.DaaMod;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.menu.HandSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The menu behind the Big A table: one slot per ring position plus the pile, and the whole board state
 * riding Charta's existing container-data sync.
 *
 * <h2>Slot order is ring order, not seat order</h2>
 *
 * <p>Slot 0 is the pile and slots 1..5 are the ring — slot 1 is the local hand, then the seat one place
 * clockwise, and so on. The server and the client agree on it because both work it out from
 * {@code floorMod(seat - localSeat, PLAYERS)} over the same roster, and every menu belongs to exactly
 * one player, so "the local hand" is a fixed slot index on both sides.
 *
 * <p>{@link #handSlot(int)} is the only place that mapping lives.
 *
 * <h2>Why there is a second turn slot</h2>
 *
 * <p>{@code AbstractCardMenu} already ships a data slot for the current player, but its getter is
 * {@code players.indexOf(currentPlayer)} — and {@link DaaBot#equals} makes {@code indexOf} report the
 * first real seat for every bot, so a bot's turn would be drawn as the local player's. Registering our
 * own slot after it both fixes the value and wins, because the client applies data slots in order.
 */
public class DaaMenu extends AbstractCardMenu<DaaGame, DaaMenu> {

    /** Slots 1..5 are the ring; slot 0 is the pile. */
    public static final int PILE_SLOT = 0;
    public static final int HAND_SLOTS = 1 + DaaGame.PLAYERS;

    /** Values pushed by the server, indexed exactly like {@link DaaGame#syncValue(int)}. */
    private final int[] mirror = new int[DaaGame.SYNC_SIZE];
    private boolean mirrored;

    private final ContainerData turnData = new ContainerData() {
        @Override
        public int get(int index) {
            return game.getSeatOfCurrentPlayer();
        }

        @Override
        public void set(int index, int value) {
            game.setCurrentPlayer(value);
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    private final ContainerData daaData = new ContainerData() {
        @Override
        public int get(int index) {
            return mirrored ? mirror[index] : game.syncValue(index);
        }

        @Override
        public void set(int index, int value) {
            mirrored = true;
            mirror[index] = value;
        }

        @Override
        public int getCount() {
            return DaaGame.SYNC_SIZE;
        }
    };

    public DaaMenu(int containerId, Inventory inventory, Definition definition) {
        super(DaaMod.DAA_MENU, containerId, inventory, definition);

        // Slot 0: the pile, and the drop target. PREVIEW only so the screen draws it at an absolute y;
        // its own widget is suppressed by DaaGame.PileSlot, and DaaScreen paints the cards instead --
        // a play is up to eight cards and a slot's own widget would stack them on one point.
        addCardSlot(new CardSlot<>(this.game, game -> game.getPile(), 0, 0, CardSlot.Type.PREVIEW));

        for (int ring = 0; ring < DaaGame.PLAYERS; ring++) {
            addCardSlot(handSlotOf(ring));
        }

        addDataSlots(turnData);
        addDataSlots(daaData);
    }

    /**
     * A ring position's slot: the local player's own fan at ring 0, a face-down count everywhere else.
     *
     * <p>Ring 0 is the only one that gets Charta's {@link HandSlot}, purely so its {@code postUpdate}
     * keeps the local player's placeholder mirror in step.
     */
    private CardSlot<DaaGame, DaaMenu> handSlotOf(int ring) {
        CardSlot.Type type = DaaGame.handType(ring);
        if (ring == 0) {
            return new HandSlot<>(this.game, game -> true, this.getCardPlayer(), 0, 0, type);
        }
        CardPlayer owner = ownerOfRing(ring);
        if (owner == null) {
            return new CardSlot<>(this.game, game -> new GameSlot(), 0, 0, type);
        }
        CardPlayer viewer = this.getCardPlayer();
        return new CardSlot<>(this.game, game -> game.getCensoredHand(viewer, owner), 0, 0, type);
    }

    @Nullable
    private CardPlayer ownerOfRing(int ring) {
        int seat = Math.floorMod(getLocalSeat() + ring, this.game.getPlayers().size());
        return seat < this.game.getPlayers().size() ? this.game.getPlayers().get(seat) : null;
    }

    /** Slot index of a ring position: 0 is the local hand. */
    public int handSlot(int ring) {
        return HAND_SLOTS == 0 ? 0 : 1 + Math.floorMod(ring, DaaGame.PLAYERS);
    }

    @Override
    public dev.lucaargolo.charta.common.game.api.game.GameType<DaaGame, DaaMenu> getGameType() {
        return DaaMod.DAA;
    }

    @Override
    public @NotNull net.minecraft.world.item.ItemStack quickMoveStack(@NotNull net.minecraft.world.entity.player.Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull net.minecraft.world.entity.player.Player player) {
        return this.game != null && this.getCardPlayer() != null && !this.game.isGameOver();
    }

    /** Panel origin. The screen is exactly the design frame, so this is always zero. */
    public int panelLeft() {
        return 0;
    }

    public int panelTop() {
        return 0;
    }

    /** Seat of the local player, i.e. where they sit on the table. */
    public int getLocalSeat() {
        return Math.max(0, this.game.getSeat(this.getCardPlayer()));
    }

    /** Seat whose turn it is, as agreed with the server. */
    public int getCurrentSeat() {
        return turnData.get(0);
    }

    public boolean isMyTurn() {
        return getCurrentSeat() == getLocalSeat();
    }

    // ------------------------------------------------------------------ mirrored state ---

    public DaaGame.Phase getPhase() {
        return DaaGame.Phase.values()[clamp(daaData.get(DaaGame.SYNC_PHASE), DaaGame.Phase.values().length)];
    }

    public DaaGame.Outcome getOutcome() {
        return DaaGame.Outcome.values()[clamp(daaData.get(DaaGame.SYNC_OUTCOME), DaaGame.Outcome.values().length)];
    }

    public int getTableSeat() {
        return daaData.get(DaaGame.SYNC_TABLE_SEAT);
    }

    /** The kind of the pile, or {@code null} when the table is clear. */
    @Nullable
    public Combo.Kind getTableKind() {
        int ordinal = daaData.get(DaaGame.SYNC_TABLE_KIND) - 1;
        Combo.Kind[] kinds = Combo.Kind.values();
        return ordinal < 0 || ordinal >= kinds.length ? null : kinds[ordinal];
    }

    public int getTableSize() {
        return daaData.get(DaaGame.SYNC_TABLE_SIZE);
    }

    public int getTableKey() {
        return daaData.get(DaaGame.SYNC_TABLE_KEY);
    }

    public int getRevealedMask() {
        return daaData.get(DaaGame.SYNC_REVEALED);
    }

    public int getMainASeat() {
        return daaData.get(DaaGame.SYNC_MAIN_A);
    }

    /** The 次A once they have shown a 大A, or {@code -1} while they are still hidden. */
    public int getSubASeat() {
        return daaData.get(DaaGame.SYNC_SUB_A);
    }

    /** Suit index of the drawn 大A, or {@code -1} before the deal lands. */
    public int getTrumpIndex() {
        return daaData.get(DaaGame.SYNC_TRUMP);
    }

    public int getHeadSeat() {
        return daaData.get(DaaGame.SYNC_HEAD);
    }

    public int getFinishedMask() {
        return daaData.get(DaaGame.SYNC_FINISHED);
    }

    public int getPasses() {
        return daaData.get(DaaGame.SYNC_PASSES);
    }

    public int getRound() {
        return daaData.get(DaaGame.SYNC_ROUND);
    }

    /** Cards already dealt, for the dealing counter. */
    public int getDealt() {
        return daaData.get(DaaGame.SYNC_DEALT);
    }

    /** Whether the local player has already called their 大A this deal. */
    public boolean hasCalled() {
        return (daaData.get(DaaGame.SYNC_CALLED) & (1 << getLocalSeat())) != 0;
    }

    /**
     * Whether the 叫大A button should be live for the local player.
     *
     * <p>The local player's own fan is the one hand the client actually holds, so the eligibility is
     * decided here rather than mirrored: are we still dealing, is there a chair left to claim, and is
     * one of the two aces sitting in this fan.
     */
    public boolean canCallBigA() {
        if (getPhase() != DaaGame.Phase.DEALING || hasCalled()) {
            return false;
        }
        if (getMainASeat() >= 0 && getSubASeat() >= 0) {
            return false;
        }
        Suit trump = trumpSuit();
        if (trump == null || cardSlots.size() <= handSlot(0)) {
            return false;
        }
        for (Card card : cardSlots.get(handSlot(0)).getSlot().getCards()) {
            if (DaaCards.isBigA(card, trump)) {
                return true;
            }
        }
        return false;
    }

    /** The drawn 大A suit, or {@code null} before the deal has picked one. */
    @Nullable
    public Suit trumpSuit() {
        int index = getTrumpIndex();
        return index < 0 || index >= Suits.STANDARD.size() ? null : Suits.STANDARD.get(index);
    }

    public int handCount(int seat) {
        return daaData.get(DaaGame.SYNC_HAND_BASE + seat);
    }

    /** Bit 0..15 of the selection of {@code seat}, over the order of that seat's own hand. */
    public int selectionLow(int seat) {
        return daaData.get(DaaGame.SYNC_SELECT_LO + seat);
    }

    public int selectionHigh(int seat) {
        return daaData.get(DaaGame.SYNC_SELECT_HI + seat);
    }

    /** Whether the card at {@code index} of the local hand is selected. */
    public boolean isSelected(int index) {
        if (index < 0 || index >= 32) {
            return false;
        }
        int seat = getLocalSeat();
        int mask = index < 16 ? selectionLow(seat) : selectionHigh(seat);
        return (mask & (1 << (index & 15))) != 0;
    }

    public boolean isRevealed(int seat) {
        return seat >= 0 && seat < DaaGame.PLAYERS && (getRevealedMask() & (1 << seat)) != 0;
    }

    public boolean isFinished(int seat) {
        return seat >= 0 && seat < DaaGame.PLAYERS && (getFinishedMask() & (1 << seat)) != 0;
    }

    /** Whether {@code seat} is on the 大A side <em>as far as the local player is allowed to know</em>. */
    public boolean isKnownMainASide(int seat) {
        if (getMainASeat() == seat) {
            return true;
        }
        int sub = getSubASeat();
        return sub == seat;
    }

    public GameSlot getPileCards() {
        return this.game.getPile();
    }

    public CardPlayer getPlayerAtSeat(int seat) {
        return this.game.getPlayers().isEmpty() ? null
                : this.game.getPlayers().get(Math.floorMod(seat, this.game.getPlayers().size()));
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }
}
