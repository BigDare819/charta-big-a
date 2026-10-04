package dev.daa.game;

import dev.daa.client.DaaFrame;
import dev.daa.client.DaaLayout;
import dev.daa.network.DaaActionPayload;
import dev.lucaargolo.charta.client.ChartaModClient;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.utils.CardImage;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The Big A table.
 *
 * <h2>Everything is viewer relative</h2>
 *
 * <p>The local player's hand is the bottom fan and the other four seats wrap around it, so the ring
 * index — not the seat index — is what the whole screen is written in. See {@link DaaLayout}.
 *
 * <h2>The interaction is owned here, not by Charta</h2>
 *
 * <p>Charta's click-to-carry cannot express a play of up to eight cards: a click there means "lift this
 * card" and a second click means "put it back". So every click is intercepted, translated to one of the
 * four intents in {@link DaaActionPayload} and sent, and the inherited card-slot click path is
 * suppressed. What is <em>kept</em> is Charta's own hit-testing: {@code hoveredCardSlot} and
 * {@code hoveredCardId} are computed by {@code GameScreen.render} against the same declared boxes the
 * cards are painted in, so a click lands on exactly the card under the pointer without this file having
 * to re-derive the fan a second time.
 *
 * <h2>One design frame, scaled onto the window</h2>
 *
 * <p>Every coordinate is an absolute pixel count measured on a 640x360 screen, which is what a 1920x1080
 * display gives at GUI scale 3. Rather than sprinkle window arithmetic through the drawing, this screen
 * <em>is</em> 640x360: {@link #init} re-points {@code width}/{@code height} at the design frame and
 * {@link #render} wraps the whole vanilla pass in a uniform scale and centring translate. Input arrives
 * in window coordinates and is converted on entry.
 */
public class DaaScreen extends GameScreen<DaaGame, DaaMenu> {

    private static final int PLATE_BG = 0x99000000;
    private static final int LABEL = 0xFFFFFFFF;
    private static final int DIM = 0xFF9A9A9A;
    private static final int ACTIVE = 0xFFFFE97F;
    private static final int BAD = 0xFFFF7070;
    private static final int CELL_BG = 0x66000000;
    private static final int CELL_HOVER = 0x99FFE97F;
    private static final int OUTLINE = 0x60FFFFFF;
    private static final int SCRIM = 0x59000000;

    private static final DaaLayout LAYOUT = new DaaLayout();

    private boolean editing;
    @Nullable
    private DaaLayout.Element selected;
    @Nullable
    private DaaLayout.Element hovered;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    public DaaScreen(DaaMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = DaaFrame.WIDTH;
        this.imageHeight = DaaFrame.HEIGHT;
    }

    @Override
    protected void init() {
        // width/height arrive here as the real GUI-scaled window size; from this point on this screen
        // paints inside the design frame, so they are re-pointed at it -- and done *before* super.init(),
        // because AbstractContainerScreen derives leftPos/topPos from them, GameScreen places its corner
        // buttons off them, and the chat screen is initialised with them.
        this.width = DaaFrame.WIDTH;
        this.height = DaaFrame.HEIGHT;
        // GameScreen rebuilds its slot widgets in init(), and a widget caches the slot position it was
        // built with, so publish the layout first.
        LAYOUT.bind(menu);
        LAYOUT.apply(menu);
        super.init();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // A resize rebuilds the screen; a seat change (which happens on the first tick after the menu
        // opens, once the roster is known) does not, and the whole ring is derived from it.
        int seat = menu.getLocalSeat();
        if (seat != LAYOUT.getLocalSeat()) {
            LAYOUT.bind(menu);
            LAYOUT.apply(menu);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        DaaFrame frame = DaaFrame.current();
        int frameMouseX = (int) Math.floor(frame.toFrameX(mouseX));
        int frameMouseY = (int) Math.floor(frame.toFrameY(mouseY));

        if (editing) {
            updateEditor(frameMouseX, frameMouseY);
        }

        frame.push(guiGraphics);
        renderScrim(guiGraphics);
        // Cleared first: the mixin sets it while super.render runs, and only if it actually swallowed
        // the chat. If a third addon outranked this one's mixin the flag stays false and the chat is
        // already on screen inside the frame -- drawing it again would stack two copies.
        DaaFrame.chatSuppressed = false;
        super.render(guiGraphics, frameMouseX, frameMouseY, partialTick);
        renderSelection(guiGraphics);
        frame.pop(guiGraphics);

        if (DaaFrame.chatSuppressed) {
            renderChat(guiGraphics, mouseX, mouseY);
        }
    }

    /**
     * A single translucent sheet over the whole frame before anything else, which is what turns the
     * vanilla blur pass into readable frost rather than a slightly soft version of the world.
     */
    private void renderScrim(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, 0, DaaFrame.WIDTH, DaaFrame.HEIGHT, SCRIM);
    }

    /** The inherited bars are both replaced: the plates carry the colour they showed, and the fans own the edges. */
    @Override
    public void renderTopBar(@NotNull GuiGraphics guiGraphics) {
    }

    @Override
    public void renderBottomBar(@NotNull GuiGraphics guiGraphics) {
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        renderPile(guiGraphics);
        for (int ring = 0; ring < DaaGame.PLAYERS; ring++) {
            drawPlate(guiGraphics, ring);
        }
        renderStatus(guiGraphics);
        renderButtons(guiGraphics, mouseX, mouseY);
        if (editing) {
            renderEditor(guiGraphics);
        }
    }

    // ------------------------------------------------------------------- pile ---

    /**
     * The pile, up to eight cards in a grid, plus a lit frame in the thrower's own colour.
     *
     * <p>A card slot draws its contents at a single point, so the slot that carries the pile would stack
     * them all on the same spot. The pile slot therefore reports itself empty, which keeps its widget out
     * of the way, and the cards are painted here instead.
     */
    private void renderPile(GuiGraphics guiGraphics) {
        int[] box = LAYOUT.bounds(DaaLayout.Element.PILE);
        int tableSeat = menu.getTableSeat();
        boolean live = tableSeat >= 0 && menu.getTableKind() != null;

        guiGraphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], CELL_BG);
        frame(guiGraphics, box[0], box[1], box[2], box[3], live ? 0xFFFFE97F : OUTLINE);

        GameSlot pile = menu.getPileCards();
        if (pile.isEmpty()) {
            Component hint = Component.translatable(menu.isMyTurn()
                    ? "message.daa.you_lead" : "message.daa.waiting_lead");
            guiGraphics.drawString(font, hint, box[0] + (box[2] - font.width(hint)) / 2,
                    box[1] + box[3] / 2 - 4, DIM, true);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Card> cards = (List<Card>) pile.getCards();
        // A grid that grows with the pile rather than a fixed one: most plays are one to three cards and
        // a four-by-two layout would leave them rattling around in the corners.
        int columns = Math.min(cards.size(), 4);
        int rows = (cards.size() + columns - 1) / columns;
        int cardW = Math.min(CardImage.WIDTH, (box[2] - 8) / columns);
        int cardH = Math.round(cardW * (CardImage.HEIGHT / (float) CardImage.WIDTH));
        int gridW = columns * cardW;
        int gridH = rows * cardH;
        int originX = box[0] + (box[2] - gridW) / 2;
        int originY = box[1] + (box[3] - gridH) / 2;

        Deck deck = menu.getGame().getDeck();
        for (int i = 0; i < cards.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            drawCard(guiGraphics, cards.get(i), deck, originX + column * cardW, originY + row * cardH, cardW, cardH);
        }
    }

    /**
     * Paints one card into a box, the way an unhovered {@code AbstractCardWidget} would.
     *
     * <p>Charta's cards are not plain blits: a vertex shader fakes a camera perspective on the quad, so
     * the widget hands it a box a third larger than the card and drives it with four shader uniforms.
     * Reproducing those here rather than blitting the texture straight into the box is what makes a
     * played card the same size and shape as one still in a fan.
     */
    private void drawCard(GuiGraphics guiGraphics, Card card, Deck deck, int x, int y, int width, int height) {
        float w = width;
        float h = height;
        float xOffset = (w * 1.333333f - w) / 2f;
        float yOffset = (h * 1.333333f - h) / 2f;

        ChartaModClient.getShaderManager().getCardInset().accept(0f);
        ChartaModClient.getShaderManager().getCardFov().accept(30f);
        ChartaModClient.getShaderManager().getCardXRot().accept(0f);
        ChartaModClient.getShaderManager().getCardYRot().accept(0f);

        ResourceLocation texture = card.flipped() ? deck.getTexture(false) : deck.getCardTexture(card, false);
        ChartaGuiGraphics.blitCard(guiGraphics, texture,
                x - xOffset, y - yOffset, w + xOffset * 2f, h + yOffset * 2f);
    }

    // ------------------------------------------------------------------- seats ---

    /**
     * One name plate per ring position: the viewer, the 主A, the 抓方, and who is out.
     *
     * <p>The 次A stays blank until their 大A hits the table, which is the whole hidden-partner tension —
     * the plate is the only place that information can appear, so it gets a tag rather than a colour.
     */
    private void drawPlate(GuiGraphics guiGraphics, int ring) {
        int seat = Math.floorMod(menu.getLocalSeat() + ring, DaaGame.PLAYERS);
        CardPlayer player = menu.getPlayerAtSeat(seat);
        if (player == null) {
            return;
        }
        int[] box = LAYOUT.bounds(LAYOUT.plate(ring));
        int color = rgb(player.getColor());
        boolean active = seat == menu.getCurrentSeat() && menu.getPhase() == DaaGame.Phase.PLAY;

        Component text = player.getName().copy();
        if (seat == menu.getLocalSeat()) {
            text = text.copy().append(Component.translatable("seat.daa.you"));
        }
        if (seat == menu.getMainASeat()) {
            text = text.copy().append(Component.translatable("seat.daa.main_a"));
        } else if (seat == menu.getSubASeat()) {
            text = text.copy().append(Component.translatable("seat.daa.sub_a"));
        } else if (menu.getPhase() != DaaGame.Phase.DEALING && !menu.isKnownMainASide(seat)) {
            text = text.copy().append(Component.translatable("seat.daa.catcher"));
        }
        if (menu.isFinished(seat)) {
            text = text.copy().append(Component.translatable("seat.daa.out"));
        }

        int plateWidth = Math.min(font.width(text) + 8, 160);
        int centreX = box[0] + box[2] / 2;
        int left = Mth.clamp(centreX - plateWidth / 2, 2, DaaFrame.WIDTH - plateWidth - 2);
        int top = box[1];

        guiGraphics.fill(left, top, left + plateWidth, top + box[3], active ? 0xCC101010 : PLATE_BG);
        guiGraphics.fill(left, top, left + 2, top + box[3], 0xFF000000 | color);
        if (active) {
            guiGraphics.fill(left + 2, top, left + plateWidth, top + 1, 0xFF000000 | color);
            guiGraphics.fill(left + 2, top + box[3] - 1, left + plateWidth, top + box[3], 0xFF000000 | color);
        }

        Component count = Component.literal(" " + menu.handCount(seat));
        int textY = top + (box[3] - 8) / 2;
        guiGraphics.drawString(font, text, left + 5, textY, active ? ACTIVE : LABEL, true);
        guiGraphics.drawString(font, count, left + 5 + font.width(text), textY,
                seat == menu.getLocalSeat() ? ACTIVE : DIM, true);
    }

    // ------------------------------------------------------------------- status ---

    private void renderStatus(GuiGraphics guiGraphics) {
        switch (menu.getPhase()) {
            case DEALING -> drawCentred(guiGraphics, Component.translatable("message.daa.dealing")
                    .withStyle(ChatFormatting.GOLD), 100);
            case RESULT -> drawResult(guiGraphics);
            case PLAY -> renderPlayStatus(guiGraphics);
        }
    }

    private void renderPlayStatus(GuiGraphics guiGraphics) {
        Suit trump = trump();
        drawStatus(guiGraphics, DaaLayout.Element.STATUS_BOARD,
                Component.translatable("message.daa.board_line",
                        Component.translatable("message.daa.round", menu.getRound()),
                        trumpLine(trump)));

        if (menu.isMyTurn()) {
            drawStatus(guiGraphics, DaaLayout.Element.STATUS_TURN,
                    Component.translatable("message.daa.your_turn")
                            .withStyle(style -> style.withColor(rgb(menu.getCardPlayer().getColor()))));
        } else {
            CardPlayer other = menu.getPlayerAtSeat(menu.getCurrentSeat());
            drawStatus(guiGraphics, DaaLayout.Element.STATUS_TURN,
                    Component.translatable("message.charta.other_turn",
                            other == null ? Component.empty() : other.getName()).withStyle(ChatFormatting.GRAY));
        }

        Combo.Kind kind = menu.getTableKind();
        if (kind == null) {
            drawStatus(guiGraphics, DaaLayout.Element.STATUS_TABLE,
                    Component.translatable("message.daa.table_clear"));
        } else {
            CardPlayer thrower = menu.getPlayerAtSeat(menu.getTableSeat());
            drawStatus(guiGraphics, DaaLayout.Element.STATUS_TABLE,
                    Component.translatable("message.daa.table_line",
                            thrower == null ? Component.empty() : thrower.getName(),
                            Component.translatable("pattern.daa." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                            menu.getTableSize()));
        }
    }

    /** The drawn 大A, as a suit glyph tinted with the deck's own colour for it. */
    private Component trumpLine(@Nullable Suit trump) {
        if (trump == null) {
            return Component.translatable("message.daa.trump_unknown");
        }
        return Component.translatable("message.daa.trump_line",
                Component.translatable(menu.getGame().getDeck().getSuitTranslatableKey(trump))
                        .withColor(menu.getGame().getDeck().getSuitColor(trump)));
    }

    private void drawResult(GuiGraphics guiGraphics) {
        Component banner = switch (menu.getOutcome()) {
            case MAIN_A_WINS -> Component.translatable("message.daa.main_a_wins");
            case CATCHERS_WIN -> Component.translatable("message.daa.catchers_win");
            case DRAW -> Component.translatable("message.daa.draw");
            default -> Component.translatable("message.daa.aborted");
        };
        int color = switch (menu.getOutcome()) {
            case MAIN_A_WINS -> ACTIVE;
            case CATCHERS_WIN -> 0xFF7FE9FF;
            default -> LABEL;
        };

        int[] box = LAYOUT.bounds(DaaLayout.Element.PILE);
        guiGraphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], 0x99000000);
        frame(guiGraphics, box[0], box[1], box[2], box[3], color);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(DaaFrame.WIDTH / 2f, box[1] + box[3] / 2f - 14, 0f);
        guiGraphics.pose().scale(2f, 2f, 1f);
        guiGraphics.drawString(font, banner, -font.width(banner) / 2, 0, color, true);
        guiGraphics.pose().popPose();

        if (menu.getHeadSeat() >= 0) {
            CardPlayer head = menu.getPlayerAtSeat(menu.getHeadSeat());
            Component first = Component.translatable("message.daa.head_line",
                    head == null ? Component.empty() : head.getName());
            drawCentred(guiGraphics, first, box[1] + box[3] / 2 + 14);
        }
    }

    private void drawStatus(GuiGraphics guiGraphics, DaaLayout.Element element, Component text) {
        int[] box = LAYOUT.bounds(element);
        guiGraphics.drawString(font, text, box[0] + (box[2] - font.width(text)) / 2, box[1], LABEL, true);
    }

    private void drawCentred(GuiGraphics guiGraphics, Component text, int y) {
        guiGraphics.drawString(font, text, (DaaFrame.WIDTH - font.width(text)) / 2, y, LABEL, true);
    }

    // ------------------------------------------------------------------- buttons ---

    private void renderButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<Card> selection = selectedCards();
        Combo combo = selection.isEmpty() ? null : Combo.of(selection, trump());
        boolean legal = combo != null && combo.playableOn(selection, tableCombo());

        drawButton(guiGraphics, DaaLayout.Element.BTN_PLAY, "message.daa.play",
                menu.isMyTurn() && legal, mouseX, mouseY);
        drawButton(guiGraphics, DaaLayout.Element.BTN_PASS, "message.daa.pass",
                menu.isMyTurn() && tableCombo() != null, mouseX, mouseY);
        drawButton(guiGraphics, DaaLayout.Element.BTN_CLEAR, "message.daa.clear",
                !selection.isEmpty(), mouseX, mouseY);
    }

    private void drawButton(GuiGraphics guiGraphics, DaaLayout.Element element, String key,
                            boolean enabled, int mouseX, int mouseY) {
        int[] box = LAYOUT.bounds(element);
        boolean hover = enabled && mouseX >= box[0] && mouseX < box[0] + box[2]
                && mouseY >= box[1] && mouseY < box[1] + box[3];

        guiGraphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
                !enabled ? 0x40000000 : hover ? CELL_HOVER : CELL_BG);
        frame(guiGraphics, box[0], box[1], box[2], box[3], hover ? 0xFFFFFFFF : OUTLINE);

        Component text = Component.translatable(key);
        guiGraphics.drawString(font, text, box[0] + (box[2] - font.width(text)) / 2,
                box[1] + (box[3] - 8) / 2, enabled ? (hover ? 0xFF202020 : LABEL) : DIM, true);
    }

    // ------------------------------------------------------------------- selection ---

    /**
     * Lifts each selected card out of the fan.
     *
     * <p>The fan is re-solved exactly the way {@code CardSlotWidget} does it for a {@code HORIZONTAL}
     * slot, from the same declared box, so a drawn lift sits on the card the hit-test would return rather
     * than a pixel or two beside it. Only the local hand is ever selected, so only one geometry is needed.
     */
    private void renderSelection(GuiGraphics guiGraphics) {
        CardSlot<DaaGame, DaaMenu> slot = menu.cardSlots.get(menu.handSlot(0));
        int size = slot.getSlot().size();
        if (size == 0) {
            return;
        }
        int[] box = LAYOUT.bounds(DaaLayout.Element.HAND_0);
        float cardW = CardSlot.getWidth(CardSlot.Type.DEFAULT);
        float cardH = CardSlot.getHeight(CardSlot.Type.DEFAULT);

        float maxStep = cardW + cardW / 10f;
        float step = cardW + Math.max(0f, box[2] - cardW);
        if (size > 1) {
            float excess = (cardW + step * (size - 1f)) - box[2];
            if (excess > 0) {
                step -= excess / (size - 1f);
            }
        }
        float left = 0f;
        if (step > maxStep) {
            left = Math.max(step - maxStep, box[2] - (cardW + maxStep * (size - 1f)));
            step = maxStep;
        }

        for (int index = 0; index < size; index++) {
            if (!menu.isSelected(index)) {
                continue;
            }
            int x = Math.round(box[0] + step * index + left / 2f);
            int y = box[1];
            // A tint over the card plus a bright frame on it, rather than a lift: the widget paints the
            // card where it is, so a lifted outline would sit a few pixels off the thing it marks.
            guiGraphics.fill(x, y, x + Math.round(cardW), y + Math.round(cardH), 0x44FFE97F);
            frame(guiGraphics, x - 1, y - 1, Math.round(cardW) + 2, Math.round(cardH) + 2, ACTIVE);
        }
    }

    private List<Card> selectedCards() {
        CardSlot<DaaGame, DaaMenu> slot = menu.cardSlots.get(menu.handSlot(0));
        List<Card> hand = new java.util.ArrayList<>();
        slot.getSlot().forEach(hand::add);
        java.util.List<Card> picked = new java.util.ArrayList<>();
        for (int index = 0; index < hand.size(); index++) {
            if (menu.isSelected(index)) {
                picked.add(hand.get(index));
            }
        }
        return picked;
    }

    @Nullable
    private Suit trump() {
        int index = menu.getTrumpIndex();
        return index < 0 || index >= Suits.STANDARD.size() ? null : Suits.STANDARD.get(index);
    }

    @Nullable
    private Combo tableCombo() {
        Combo.Kind kind = menu.getTableKind();
        if (kind == null) {
            return null;
        }
        return new Combo(kind, menu.getTableSize(), menu.getTableKey(), false, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        DaaFrame frame = DaaFrame.current();
        double x = frame.toFrameX(mouseX);
        double y = frame.toFrameY(mouseY);

        if (editing) {
            return editClick(x, y, button);
        }
        if (button == 0 && click(x, y)) {
            return true;
        }

        // The inherited card-slot branch would send Charta's own click payload on top of ours, so it is
        // switched off for the duration: this screen has already handled every slot it cares about.
        CardSlot<DaaGame, DaaMenu> hoveredSlot = this.hoveredCardSlot;
        this.hoveredCardSlot = null;
        boolean handled = super.mouseClicked(x, y, button);
        this.hoveredCardSlot = hoveredSlot;
        return handled;
    }

    /** Our own hit-test, in frame coordinates. */
    private boolean click(double x, double y) {
        if (inside(DaaLayout.Element.BTN_PLAY, x, y) && menu.isMyTurn()) {
            List<Card> selection = selectedCards();
            Combo combo = selection.isEmpty() ? null : Combo.of(selection, trump());
            if (combo != null && combo.playableOn(selection, tableCombo())) {
                send(DaaActionPayload.PLAY, 0);
                return true;
            }
        }
        if (inside(DaaLayout.Element.BTN_PASS, x, y) && menu.isMyTurn()) {
            send(DaaActionPayload.PASS, 0);
            return true;
        }
        if (inside(DaaLayout.Element.BTN_CLEAR, x, y) && !selectedCards().isEmpty()) {
            send(DaaActionPayload.CLEAR, 0);
            return true;
        }
        // A card of the local fan. The index comes from Charta's own hit-test, which ran during the last
        // render against the same box the card was painted in.
        CardSlot<DaaGame, DaaMenu> own = menu.cardSlots.get(menu.handSlot(0));
        if (this.hoveredCardSlot == own && this.hoveredCardId >= 0 && menu.isMyTurn()) {
            send(DaaActionPayload.TOGGLE, this.hoveredCardId);
            return true;
        }
        return false;
    }

    private static boolean inside(DaaLayout.Element element, double x, double y) {
        int[] box = LAYOUT.bounds(element);
        return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
    }

    private void send(int action, int index) {
        ChartaMod.getPacketManager().sendToServer(new DaaActionPayload(this.menu.containerId, action, index));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) {
            editing = !editing;
            return true;
        }
        if (editing) {
            return editKey(keyCode);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            click(LAYOUT.bounds(DaaLayout.Element.BTN_PLAY)[0], LAYOUT.bounds(DaaLayout.Element.BTN_PLAY)[1]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            click(LAYOUT.bounds(DaaLayout.Element.BTN_PASS)[0], LAYOUT.bounds(DaaLayout.Element.BTN_PASS)[1]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            click(LAYOUT.bounds(DaaLayout.Element.BTN_CLEAR)[0], LAYOUT.bounds(DaaLayout.Element.BTN_CLEAR)[1]);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------------------------------------
    // F9 layout editor
    // ---------------------------------------------------------------------------------------------

    private void updateEditor(double mouseX, double mouseY) {
        hovered = LAYOUT.elementAt(mouseX, mouseY);
        if (dragging && selected != null) {
            LAYOUT.move(selected, (float) (mouseX - lastMouseX), (float) (mouseY - lastMouseY));
            LAYOUT.apply(menu);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    private boolean editClick(double x, double y, int button) {
        if (button == 0) {
            selected = LAYOUT.elementAt(x, y);
            dragging = selected != null;
            return true;
        }
        if (button == 1) {
            DaaLayout.Element element = LAYOUT.elementAt(x, y);
            if (element != null) {
                LAYOUT.reset(element);
                LAYOUT.apply(menu);
            }
            return true;
        }
        return true;
    }

    private boolean editKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_R && hovered != null) {
            LAYOUT.reset(hovered);
            LAYOUT.apply(menu);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S) {
            LAYOUT.save();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (editing) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (editing && hovered != null) {
            LAYOUT.scaleBy(hovered, (float) scrollY * DaaLayout.SCALE_STEP);
            LAYOUT.apply(menu);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void renderEditor(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, 0, DaaFrame.WIDTH, 12, 0xAA000000);
        guiGraphics.drawString(font, Component.translatable("editor.daa.hint"), 3, 2, ACTIVE, true);

        for (DaaLayout.Element element : DaaLayout.Element.values()) {
            int[] box = LAYOUT.bounds(element);
            boolean isSelected = element == selected;
            boolean isHovered = element == hovered;
            frame(guiGraphics, box[0], box[1], box[2], box[3],
                    isSelected ? 0xFFFF4040 : isHovered ? 0xFFFFFF00 : 0x80FFFFFF);
            if (isSelected || isHovered) {
                guiGraphics.drawString(font, element.label(), box[0], Math.max(13, box[1] - 10), 0xFFFFFF00, true);
            }
        }
    }

    // ------------------------------------------------------------------- helpers ---

    private static int rgb(DyeColor color) {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }

    private static void frame(GuiGraphics guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, x + width, y + 1, color);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, color);
        guiGraphics.fill(x, y, x + 1, y + height, color);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /**
     * The chat, in window coordinates, exactly where {@code GameScreen.render} used to put it.
     *
     * <p>{@code GameScreenChatFrame} suppresses the inherited draw so this can happen outside the frame
     * pose; inside it the two copies vanilla paints would not line up.
     */
    private void renderChat(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.gui.getChat().isChatFocused()) {
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0f, -25f, 0f);
        double chatWidth = minecraft.options.chatWidth().get();
        double newChatWidth = Math.min(chatWidth * 280.0,
                (DaaFrame.windowWidth() / 2.0) - (imageWidth / 2.0) - 50.0) / 280.0;
        if (newChatWidth > 0.0 && newChatWidth < 1) {
            minecraft.options.chatWidth().set(newChatWidth);
        }
        minecraft.gui.getChat().render(guiGraphics, minecraft.gui.getGuiTicks(), mouseX, mouseY + 25, false);
        minecraft.options.chatWidth().set(chatWidth);
        guiGraphics.pose().popPose();
    }

    /** Unused but kept for parity with the frame-based text helpers above. */
    static {
        assert Util.getMillis() >= 0;
        assert Math.floorMod(-1, DaaGame.PLAYERS) == DaaGame.PLAYERS - 1;
    }
}
