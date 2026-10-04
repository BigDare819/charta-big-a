package dev.daa.client;

import dev.daa.game.DaaGame;
import dev.daa.game.DaaMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Every coordinate the Big A screen draws at, in one place.
 *
 * <h2>A ring, not a compass</h2>
 *
 * <p>Bridge lays its table out on real compass points, because four people sit one to a side and the
 * direction they face is a fact about the world. Big A seats five, and Charta's five chairs are not a
 * compass at all — so the screen is instead <em>viewer relative</em>: the local player's hand is always
 * the bottom fan and the other four wrap around it, picked by
 * {@code floorMod(seat - localSeat, PLAYERS)}.
 *
 * <p>The ring index is the whole of it:
 *
 * <pre>
 *   0 the local fan along the bottom, horizontal
 *   1 a column up the left edge
 *   2 a fan at the top left,      3 a fan at the top right
 *   4 a column down the right edge
 * </pre>
 *
 * <p>Everybody therefore reads their own hand at the bottom, which is the one thing that makes a
 * twenty-two card hand usable, at the price of two players disagreeing about who is "on the left".
 *
 * <h2>The design frame is fixed</h2>
 *
 * <p>Unlike Bridge's layout this one takes no width and height: {@link DaaFrame} guarantees the screen
 * is exactly {@code 640x360}, so a converted slot's position is just its box translated by the panel
 * origin, which {@code DaaMenu} pins to zero.
 *
 * <h2>F9</h2>
 *
 * <p>{@link #move}, {@link #scaleBy} and {@link #save} back the in-game editor. Only dx/dy/scale are
 * persisted, keyed by {@link Element#key}, in {@code config/daa-layout.properties}.
 */
public final class DaaLayout {

    /** A movable piece of the screen. Sizes are the <em>drawn</em> extent, not a bounding hint. */
    public enum Element {
        /** The local hand: a twenty-two card fan along the bottom edge. */
        HAND_0("hand_0", 440, 53),
        /** A three-card-wide column up the left edge; twenty-two of them is what sets its length. */
        HAND_1("hand_1", 38, 206),
        /** The upper-left fan. */
        HAND_2("hand_2", 186, 53),
        /** The upper-right fan. */
        HAND_3("hand_3", 186, 53),
        /** The right-hand column. */
        HAND_4("hand_4", 38, 206),
        PLATE_0("plate_0", 118, 13),
        PLATE_1("plate_1", 118, 13),
        PLATE_2("plate_2", 118, 13),
        PLATE_3("plate_3", 118, 13),
        PLATE_4("plate_4", 118, 13),
        /** The middle block: the current pile. */
        PILE("pile", 300, 96),
        BTN_PLAY("btn_play", 68, 18),
        BTN_PASS("btn_pass", 68, 18),
        BTN_CLEAR("btn_clear", 68, 18),
        /** The three status lines above the pile: board, turn, and what is on the table. */
        STATUS_BOARD("status_board", 240, 10),
        STATUS_TURN("status_turn", 240, 10),
        STATUS_TABLE("status_table", 240, 10);

        /** Config key, and the suffix of this element's {@code editor.daa.*} editor label. */
        public final String key;
        public final int baseWidth;
        public final int baseHeight;

        Element(String key, int baseWidth, int baseHeight) {
            this.key = key;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
        }

        /** Translated name, for the F9 overlay. */
        public String label() {
            return Component.translatable("editor.daa." + key).getString();
        }
    }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("daa-layout.properties");

    /**
     * Layout schema. Bumping it discards a saved file, which matters when an element's default box moves
     * far enough that an offset dragged against the old default lands somewhere meaningless.
     */
    private static final String VERSION = "1";

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    /** How much one scroll notch changes an element's scale. */
    public static final float SCALE_STEP = 0.05f;

    // Ring anchors. Absolute, measured on the design frame.
    private static final int MARGIN = 8;
    private static final int BOTTOM_GAP = 6;
    private static final int COLUMN_TOP = 40;
    private static final int TOP_ROW = 8;
    private static final int PLATE_GAP = 3;

    /** {@code {dx, dy, scale}} per {@link Element}, indexed by {@link Element#ordinal()}. */
    private final float[][] values = new float[Element.values().length][3];

    /** Seat of the local player, so the ring can be resolved. Refreshed by {@link #bind}. */
    private int localSeat;

    public DaaLayout() {
        resetAll();
        load();
    }

    // ------------------------------------------------------------------ values ---

    private static float[] defaults(Element element) {
        return switch (element) {
            default -> new float[]{0f, 0f, 0f};
        };
    }

    private float[] entry(Element element) {
        return values[element.ordinal()];
    }

    public float dx(Element element) {
        return entry(element)[0];
    }

    public float dy(Element element) {
        return entry(element)[1];
    }

    public float scale(Element element) {
        return entry(element)[2] == 0f ? 1f : entry(element)[2];
    }

    public void move(Element element, float ddx, float ddy) {
        float[] value = entry(element);
        value[0] += ddx;
        value[1] += ddy;
    }

    public void scaleBy(Element element, float amount) {
        float[] value = entry(element);
        value[2] = Mth.clamp(scale(element) + amount, MIN_SCALE, MAX_SCALE);
    }

    public void reset(Element element) {
        values[element.ordinal()] = defaults(element);
    }

    public void resetAll() {
        for (Element element : Element.values()) {
            reset(element);
        }
    }

    // ------------------------------------------------------------------ geometry ---

    public int[] bounds(Element element) {
        int[] anchor = anchor(element);
        return new int[]{
                anchor[0] + (int) dx(element),
                anchor[1] + (int) dy(element),
                Math.round(element.baseWidth * scale(element)),
                Math.round(element.baseHeight * scale(element))
        };
    }

    /** Where an element sits before the player's offset: the ring, resolved for the local seat. */
    private int[] anchor(Element element) {
        int width = Math.round(element.baseWidth * scale(element));
        int height = Math.round(element.baseHeight * scale(element));
        String key = element.key;

        if (key.startsWith("hand_")) {
            return ringAnchor(ring(key), width, height, false);
        }
        if (key.startsWith("plate_")) {
            return ringAnchor(ring(key), width, height, true);
        }
        return switch (element) {
            case PILE -> new int[]{(DaaFrame.WIDTH - width) / 2, 156};
            case BTN_PLAY -> new int[]{(DaaFrame.WIDTH - 3 * BTN_W - 2 * BTN_SPACING) / 2, 264};
            case BTN_PASS -> new int[]{(DaaFrame.WIDTH - 3 * BTN_W - 2 * BTN_SPACING) / 2 + BTN_W + BTN_SPACING, 264};
            case BTN_CLEAR -> new int[]{(DaaFrame.WIDTH - 3 * BTN_W - 2 * BTN_SPACING) / 2 + 2 * (BTN_W + BTN_SPACING), 264};
            // Status lines are centred and stacked; their own anchors are what the stack walks.
            case STATUS_BOARD -> new int[]{(DaaFrame.WIDTH - width) / 2, 116};
            case STATUS_TURN -> new int[]{(DaaFrame.WIDTH - width) / 2, 128};
            case STATUS_TABLE -> new int[]{(DaaFrame.WIDTH - width) / 2, 140};
            default -> new int[]{0, 0};
        };
    }

    /** Ring index of a {@code hand_N} / {@code plate_N} key. */
    private static int ring(String key) {
        int underscore = key.lastIndexOf('_');
        try {
            return Math.floorMod(Integer.parseInt(key.substring(underscore + 1)), DaaGame.PLAYERS);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static final int PLATE_H = 13;
    private static final int BTN_W = 68;
    private static final int BTN_SPACING = 6;
    /** Left edge of the upper-left fan; the upper-right one mirrors it. */
    private static final int TOP_ROW_X = 96;
    /** Vertical gap between the two upper fans and the middle of the screen. */
    private static final int TOP_ROW_GAP = 76;

    /**
     * Anchor of one ring slot, for the hand or its plate.
     *
     * <p>The ring is walked from the local seat, so ring 0 is always the bottom fan and the picture
     * rotates with the viewer. Both the hand and its plate come out of the same expression, which is
     * what keeps a name glued to its owner when the window is resized.
     */
    private int[] ringAnchor(int ring, int width, int height, boolean plate) {
        int plateH = Math.round(PLATE_H * (plate ? scale(Element.PLATE_0) : 1f));
        return switch (ring) {
            case 0 -> new int[]{
                    (DaaFrame.WIDTH - width) / 2,
                    plate ? DaaFrame.HEIGHT - BOTTOM_GAP - Element.HAND_0.baseHeight - PLATE_GAP - plateH
                          : DaaFrame.HEIGHT - BOTTOM_GAP - height};
            case 1 -> new int[]{
                    plate ? MARGIN + Element.HAND_1.baseWidth + PLATE_GAP : MARGIN,
                    COLUMN_TOP};
            case 2 -> new int[]{
                    plate ? TOP_ROW_X + Element.HAND_2.baseWidth - width : TOP_ROW_X,
                    plate ? TOP_ROW + Element.HAND_2.baseHeight + PLATE_GAP : TOP_ROW};
            case 3 -> new int[]{
                    plate ? DaaFrame.WIDTH - TOP_ROW_X - Element.HAND_3.baseWidth : DaaFrame.WIDTH - TOP_ROW_X - width,
                    plate ? TOP_ROW + Element.HAND_3.baseHeight + PLATE_GAP : TOP_ROW};
            default -> new int[]{
                    plate ? DaaFrame.WIDTH - MARGIN - Element.HAND_4.baseWidth - PLATE_GAP - width
                          : DaaFrame.WIDTH - MARGIN - width,
                    COLUMN_TOP};
        };
    }

    // ------------------------------------------------------------------ binding ---

    /** Notes which seat the viewer is in. Cheap; called before anything reads {@link #bounds}. */
    public void bind(DaaMenu menu) {
        localSeat = menu.getLocalSeat();
    }

    public int getLocalSeat() {
        return localSeat;
    }

    /**
     * Ring index of an absolute seat: 0 is the viewer, 1 the next seat clockwise, and so on.
     *
     * <p>The one function that turns a server-side seat into a place on this screen. Everything that
     * addresses a specific player — a hand, a plate, the anchor a collected pile flies to — goes through
     * it, so there is exactly one definition of where somebody is.
     */
    public int ringOf(int seat) {
        return Math.floorMod(seat - localSeat, DaaGame.PLAYERS);
    }

    public Element hand(int ring) {
        return switch (Math.floorMod(ring, DaaGame.PLAYERS)) {
            case 1 -> Element.HAND_1;
            case 2 -> Element.HAND_2;
            case 3 -> Element.HAND_3;
            case 4 -> Element.HAND_4;
            default -> Element.HAND_0;
        };
    }

    public Element plate(int ring) {
        return switch (Math.floorMod(ring, DaaGame.PLAYERS)) {
            case 1 -> Element.PLATE_1;
            case 2 -> Element.PLATE_2;
            case 3 -> Element.PLATE_3;
            case 4 -> Element.PLATE_4;
            default -> Element.PLATE_0;
        };
    }

    /** Slot type a ring's hand needs: the fans run across, the two side hands run down. */
    public static CardSlot.Type handType(int ring) {
        return DaaGame.handType(ring);
    }

    /**
     * Writes the whole layout into the menu's card slots.
     *
     * <p>Slot 0 is the pile and slots 1..5 are the ring in ring order, so a slot's index says nothing
     * about which seat it belongs to — {@link DaaMenu#handSlot(int)} is the one place that mapping lives.
     */
    public void apply(DaaMenu menu) {
        if (menu.cardSlots.size() < 1 + DaaGame.PLAYERS) {
            return;
        }
        setSlot(menu, 0, Element.PILE);
        for (int ring = 0; ring < DaaGame.PLAYERS; ring++) {
            setSlot(menu, menu.handSlot(ring), hand(ring));
        }
    }

    /**
     * The one place that knows how {@code GameScreen} stores a slot per type.
     *
     * <p>Its three transforms are not the same space, so each is undone to land the slot's <em>drawn</em>
     * top-left on the element's box:
     * <ul>
     *   <li>{@code PREVIEW} is read as an absolute screen y and drawn at {@code y}.</li>
     *   <li>{@code HORIZONTAL} is a bottom offset, drawn at {@code y + frameHeight - declaredHeight}.</li>
     *   <li>{@code VERTICAL} is relative to the panel origin, which {@code DaaMenu} keeps at zero.</li>
     * </ul>
     */
    private void setSlot(DaaMenu menu, int index, Element element) {
        if (index < 0 || index >= menu.cardSlots.size()) {
            return;
        }
        CardSlot<?, ?> slot = menu.cardSlots.get(index);
        CardSlotAccess access = (CardSlotAccess) slot;
        int[] box = bounds(element);

        access.daa$setX(box[0] - menu.panelLeft());
        switch (slot.getType()) {
            case PREVIEW -> access.daa$setY(box[1]);
            case HORIZONTAL -> access.daa$setY(box[1] - DaaFrame.HEIGHT + box[3]);
            default -> access.daa$setY(box[1] - menu.panelTop());
        }
        access.daa$setSize(box[2], box[3]);
    }

    // ------------------------------------------------------------------ persistence ---

    private void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            return;
        }
        for (Element element : Element.values()) {
            float[] value = entry(element);
            value[0] = parse(properties, element.key + ".dx", 0f);
            value[1] = parse(properties, element.key + ".dy", 0f);
            value[2] = parse(properties, element.key + ".scale", 0f);
        }
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        for (Element element : Element.values()) {
            float[] value = entry(element);
            properties.setProperty(element.key + ".dx", Float.toString(value[0]));
            properties.setProperty(element.key + ".dy", Float.toString(value[1]));
            properties.setProperty(element.key + ".scale", Float.toString(value[2]));
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                properties.store(out, "Big A UI layout - edited in game with F9");
            }
        } catch (IOException ignored) {
            // A read-only instance still plays fine, it just forgets the layout.
        }
    }

    private static float parse(Properties properties, String key, float fallback) {
        try {
            return Float.parseFloat(properties.getProperty(key, Float.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Element whose box contains {@code (x, y)}, topmost first, or {@code null}. */
    @Nullable
    public Element elementAt(double x, double y) {
        Element[] elements = Element.values();
        for (int i = elements.length - 1; i >= 0; i--) {
            int[] box = bounds(elements[i]);
            if (x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3]) {
                return elements[i];
            }
        }
        return null;
    }
}
