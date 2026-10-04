package dev.daa.network;

import dev.daa.DaaMod;
import dev.daa.game.DaaMenu;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.mixed.LivingEntityMixed;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * One click inside a Da A board, from the screen to the server.
 *
 * <p>Da A plays up to eight cards at once, which Charta's click-to-carry interaction cannot express: a
 * click on a hand slot there means "lift this card", and a second click means "put it back". So the
 * screen owns the interaction entirely and sends the intent instead of a card movement.
 *
 * <p>Everything else — whose turn it is, what is on the pile, what is selected, how many cards each
 * seat still holds — rides Charta's container-data sync. This payload is only the way back.
 *
 * <p>The server validates all of it, so a desynced or spamming client can never wedge a board: an
 * illegal play is rejected and the turn is re-armed, and a click from the wrong seat is ignored.
 */
public record DaaActionPayload(int containerId, int action, int index) implements CustomPacketPayload {

    /** Add or remove the card at {@code index} of the local hand from the local selection. */
    public static final int TOGGLE = 0;
    /** Play the current selection. */
    public static final int PLAY = 1;
    /** Pass. */
    public static final int PASS = 2;
    /** Drop the whole selection. */
    public static final int CLEAR = 3;

    public static final CustomPacketPayload.Type<DaaActionPayload> TYPE =
            new CustomPacketPayload.Type<>(DaaMod.id("action"));

    public static final StreamCodec<ByteBuf, DaaActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            DaaActionPayload::containerId,
            ByteBufCodecs.VAR_INT,
            DaaActionPayload::action,
            ByteBufCodecs.VAR_INT,
            DaaActionPayload::index,
            DaaActionPayload::new
    );

    public static void handleServer(DaaActionPayload payload, ServerPlayer player, Executor executor) {
        executor.execute(() -> {
            if (!(player.containerMenu instanceof DaaMenu menu) || menu.containerId != payload.containerId()) {
                return;
            }
            CardPlayer cardPlayer = ((LivingEntityMixed) player).charta_getCardPlayer();
            switch (payload.action()) {
                case TOGGLE -> menu.getGame().toggleSelection(cardPlayer, payload.index());
                case PLAY -> menu.getGame().submitPlay(cardPlayer, menu.getGame().selectedCards(cardPlayer));
                case PASS -> menu.getGame().submitPass(cardPlayer);
                case CLEAR -> menu.getGame().clearSelection(cardPlayer);
                default -> {
                }
            }
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
