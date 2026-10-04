package dev.daa;

import dev.daa.game.DaaGame;
import dev.daa.game.DaaMenu;
import dev.daa.network.DaaActionPayload;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.FabricChartaMod;
import dev.lucaargolo.charta.common.game.Games;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Big A: a Charta addon implementing the Inner Mongolian climbing game 打大A.
 *
 * <p>A playable game needs two registrations — a {@link GameType} in Charta's {@code charta:game_type}
 * registry and a {@link MenuType} in {@code minecraft:menu} — and Charta's table screen enumerates
 * {@code Games.getRegistry()} when it opens, so nothing else is required to make it show up.
 *
 * <h2>Both registrations have to happen right here, and that needs Charta already up</h2>
 *
 * <p>Fabric freezes every registry, {@code minecraft:menu} and dynamically registered ones such as
 * {@code charta:game_type} alike, as soon as the {@code main} entrypoint stage ends. So neither can be
 * postponed to a lifecycle event.
 *
 * <p>But Charta's classes cannot be touched until Charta's own {@code main} entrypoint has run:
 * everything funnels through {@code ChartaMod.loadPlatformClass}, which dereferences
 * {@code ChartaMod.instance}, a field only assigned in Charta's {@code ModInitializer} constructor. Two
 * ordinary-looking references reach it:
 *
 * <pre>
 *   Games.getRegistry()                      -&gt; Games.&lt;clinit&gt;                      -&gt; ChartaMod.registry
 *   AbstractCardMenu.Definition.STREAM_CODEC -&gt; Deck.&lt;clinit&gt; -&gt; Suits.&lt;clinit&gt; -&gt; ChartaMod.registry
 * </pre>
 *
 * <p>Fabric Loader does <b>not</b> guarantee that a dependant's entrypoint runs after its dependency's,
 * and the order is not stable between launches either. {@link #ensureChartaInitialised()} removes the
 * requirement entirely: if Charta has not been constructed yet, construct it. That only assigns
 * {@code instance} and creates its packet manager; {@code init()} is left to Charta's own entrypoint, so
 * nothing is registered twice.
 */
public class DaaMod implements ModInitializer {

    public static final String MOD_ID = "daa";
    public static final String MOD_NAME = "Big A";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Registry id of the game, used for both {@code charta:game_type} and {@code minecraft:menu}.
     *
     * <p>The path matters beyond bookkeeping: Charta derives the table button label from
     * {@code gameId.toLanguageKey()} ({@code daa.big_a}), the button texture from
     * {@code daa:textures/gui/game/big_a.png} and the how-to-play page from
     * {@code daa.how_to_play_big_a}.
     */
    public static final ResourceLocation GAME_ID = id("big_a");

    /** Registered into {@code charta:game_type}. */
    public static GameType<DaaGame, DaaMenu> DAA;
    /** Registered into {@code minecraft:menu}. */
    public static MenuType<DaaMenu> DAA_MENU;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ensureChartaInitialised();

        DAA_MENU = Registry.register(
                BuiltInRegistries.MENU,
                GAME_ID,
                new ExtendedScreenHandlerType<DaaMenu, AbstractCardMenu.Definition>(
                        DaaMenu::new,
                        AbstractCardMenu.Definition.STREAM_CODEC
                )
        );

        DAA = Registry.register(Games.getRegistry(), GAME_ID, DaaGame::new);

        PayloadTypeRegistry.playC2S().register(DaaActionPayload.TYPE, DaaActionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DaaActionPayload.TYPE, (payload, context) ->
                DaaActionPayload.handleServer(payload, context.player(), context.server()));

        LOGGER.info("Registered {} as a playable charta game", GAME_ID);
    }

    /**
     * Makes {@code ChartaMod.instance} non-null so Charta's static initializers work, regardless of
     * whether Fabric decided to run Charta's entrypoint before ours.
     *
     * <p>Constructing a second {@code FabricChartaMod} is safe and deliberate: the constructor does
     * nothing but assign {@code instance} and create a packet manager, while all real registration lives
     * in {@code onInitialize()} -> {@code ChartaMod.init()}, which only Fabric's own instance receives.
     */
    private static void ensureChartaInitialised() {
        if (ChartaMod.getInstance() != null) {
            return;
        }
        LOGGER.info("Charta's entrypoint has not run yet; constructing it early to unlock its registries");
        new FabricChartaMod();
    }

}
