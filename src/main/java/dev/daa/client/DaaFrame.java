package dev.daa.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The single coordinate space the Big A screen is authored in, and how it is mapped onto a window of
 * any size.
 *
 * <h2>Why a fixed frame</h2>
 *
 * <p>Every number in {@link DaaLayout} is an absolute pixel count tuned on a 640x360 screen, which is
 * exactly what a 1920x1080 display yields at GUI scale 3. Written straight onto the window those
 * numbers only hold there: shrink the window and the south hand at y=300 walks off a 240 px screen.
 * "It fits" would be a property of one resolution, not of the layout.
 *
 * <p>So the screen paints into a 640x360 <em>design frame</em> and this class maps that frame onto the
 * window: one uniform scale (the smaller of the two axis ratios, so nothing can ever overflow either
 * axis) and a centring origin for the leftover space.
 */
public final class DaaFrame {

    /**
     * Design size. 640x360 is a full screen at 1920x1080 with GUI scale 3, i.e. the resolution the
     * whole layout was drawn on -- not a chosen "nice" number.
     */
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;

    /** Scale limits, so a 320x240 window or an 8K one cannot produce an absurd frame. */
    private static final float MIN_SCALE = 0.2f;
    private static final float MAX_SCALE = 4f;

    private final float scale;
    private final float originX;
    private final float originY;

    /** Frame for a null window, and the value a stale cache falls back to. */
    private static final DaaFrame UNIT = new DaaFrame(1f, 0f, 0f);

    @Nullable
    private static DaaFrame cached;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    private DaaFrame(float scale, float originX, float originY) {
        this.scale = scale;
        this.originX = originX;
        this.originY = originY;
    }

    /** The frame for the live window, recomputed only when the window size has changed. */
    public static DaaFrame current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return UNIT;
        }
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        if (cached == null || guiWidth != cachedWidth || guiHeight != cachedHeight) {
            cached = of(guiWidth, guiHeight);
            cachedWidth = guiWidth;
            cachedHeight = guiHeight;
        }
        return cached;
    }

    /** The frame that fits a GUI-scaled window of this size, centred. */
    public static DaaFrame of(int guiWidth, int guiHeight) {
        // The smaller ratio, never the larger: fitting both axes is the whole point, and the cost of
        // the choice is empty margin on one axis instead of content pushed off the other.
        float scale = Mth.clamp(Math.min(guiWidth / (float) WIDTH, guiHeight / (float) HEIGHT),
                MIN_SCALE, MAX_SCALE);
        return new DaaFrame(scale, (guiWidth - WIDTH * scale) / 2f, (guiHeight - HEIGHT * scale) / 2f);
    }

    // ------------------------------------------------------------------ frame <-> window ---

    /** Window coordinate to frame coordinate, for input the vanilla event system already scaled. */
    public double toFrameX(double guiX) {
        return (guiX - originX) / scale;
    }

    public double toFrameY(double guiY) {
        return (guiY - originY) / scale;
    }

    /** Frame coordinate back to window pixels, for the calls that ignore the pose. */
    public int toGuiX(float frameX) {
        return Math.round(originX + frameX * scale);
    }

    public int toGuiY(float frameY) {
        return Math.round(originY + frameY * scale);
    }

    /** Window length back to a frame length; for scale-invariant things like a drag delta. */
    public double toFrameLength(double guiLength) {
        return guiLength / scale;
    }

    /**
     * Rewrites a raw {@code MouseHandler} position into the value {@code GameScreen.containerTick}
     * needs.
     *
     * <p>That method computes its mouse as {@code xpos() * guiScaledWidth / screenWidth}, i.e. it
     * <em>assumes</em> the result is a window coordinate. The only way to make it produce a frame
     * coordinate without touching the method body is to hand it a different {@code xpos()}.
     */
    public static double tickX(double rawX) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return rawX;
        }
        return scaleInto(rawX, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getScreenWidth(), true);
    }

    /** See {@link #tickX}; the vertical twin. */
    public static double tickY(double rawY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return rawY;
        }
        return scaleInto(rawY, minecraft.getWindow().getGuiScaledHeight(), minecraft.getWindow().getScreenHeight(), false);
    }

    private static double scaleInto(double raw, int guiSize, int screenSize, boolean horizontal) {
        DaaFrame frame = current();
        if (guiSize == 0 || screenSize == 0 || frame.scale == 0f) {
            return raw;
        }
        double framePosition = (raw * guiSize / screenSize - (horizontal ? frame.originX : frame.originY)) / frame.scale;
        return framePosition * screenSize / guiSize;
    }

    // ------------------------------------------------------------------ pose ---

    /** Enters frame space: everything drawn until {@link #pop} uses the design coordinates. */
    public void push(GuiGraphics guiGraphics) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(originX, originY, 0f);
        guiGraphics.pose().scale(scale, scale, 1f);
    }

    public void pop(GuiGraphics guiGraphics) {
        guiGraphics.pose().popPose();
    }

    // ------------------------------------------------------------------ window size ---

    /** Live GUI-scaled window size, which is <em>not</em> the frame size. */
    public static int windowWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getWindow() == null
                ? WIDTH : minecraft.getWindow().getGuiScaledWidth();
    }

    public static int windowHeight() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getWindow() == null
                ? HEIGHT : minecraft.getWindow().getGuiScaledHeight();
    }
}
