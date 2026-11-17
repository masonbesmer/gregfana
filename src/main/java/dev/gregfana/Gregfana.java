package dev.gregfana;

import java.io.IOException;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerUseItemEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.gregfana.core.Checkpoint;
import dev.gregfana.core.Exporter;
import dev.gregfana.core.Metrics;
import dev.gregfana.source.Items;
import dev.gregfana.source.Machines;
import dev.gregfana.source.Players;
import serverutils.lib.data.Universe;

@Mod(
    modid = "gregfana",
    name = "Gregfana",
    version = Tags.VERSION,
    acceptableRemoteVersions = "*",
    dependencies = "required-after:gregtech;required-after:serverutilities")
public final class Gregfana {

    public static final Logger LOG = LogManager.getLogger("Gregfana");
    public static Gregfana instance;
    public Config config;
    public Metrics metrics;
    public Checkpoint.State state;
    public Checkpoint checkpoint;
    public Players players;
    private Exporter exporter;
    public boolean ready;
    public String failure = "Starting";
    private int ticks;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        config = new Config(event.getSuggestedConfigurationFile());
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        event.registerServerCommand(new GregfanaCommand(this));
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        try {
            checkpoint = new Checkpoint(
                Universe.get()
                    .getWorldDirectory()
                    .toPath()
                    .resolve("gregfana/state.json"));
            state = checkpoint.load();
            metrics = new Metrics(config.seriesLimit);
            metrics.restore(state.counters);
            players = new Players(config, metrics, state.ledger);
            players.collect(true);
            ready = true;
            failure = "";
            try {
                exporter = new Exporter(config.bind, config.port);
            } catch (IOException e) {
                failure = "HTTP disabled: " + e;
                LOG.error(failure);
            }
            publish();
            save();
        } catch (Exception | LinkageError e) {
            ready = false;
            failure = "Collection disabled: " + e;
            LOG.error(failure, e);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (!ready || event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (ticks % 100 == 0) {
            long start = System.nanoTime();
            try {
                players.collect(false);
                Machines.collect(this);
                metrics
                    .gauge("gregfana_collection_seconds", Metrics.labels(), (System.nanoTime() - start) / 1E9, false);
                publish();
            } catch (Exception | LinkageError e) {
                failure = e.toString();
                LOG.error("Collection failed", e);
            }
        }
        if (ticks % 1200 == 0) save();
    }

    public void publish() {
        metrics.gauge("gregfana_checkpoint_healthy", Metrics.labels(), checkpoint.error.isEmpty() ? 1 : 0, false);
        metrics.gauge(
            "gregfana_checkpoint_last_success_timestamp_seconds",
            Metrics.labels(),
            checkpoint.lastSuccess,
            false);
        metrics
            .gauge("gregfana_snapshot_timestamp_seconds", Metrics.labels(), System.currentTimeMillis() / 1000D, false);
        metrics.gauge("gregfana_source_available", Metrics.labels("source", "player_stats"), 1, false);
        if (exporter != null) exporter.publish(metrics.render());
    }

    public void save() {
        state.counters = metrics.counters();
        checkpoint.save(state);
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        if (ready) {
            collectPlayersSafely(true);
            save();
        }
        ready = false;
        if (exporter != null) exporter.close();
        if (checkpoint != null) try {
            checkpoint.close();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        Machines.clear();
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (ready) collectPlayersSafely(false);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (ready) collectPlayersSafely(true);
    }

    private void collectPlayersSafely(boolean offline) {
        try {
            players.collect(offline);
        } catch (Exception | LinkageError e) {
            failure = "Player collection failed: " + e;
            LOG.error(failure, e);
        }
    }

    @SubscribeEvent
    public void consumed(PlayerUseItemEvent.Finish event) {
        if (!ready || !(event.entityPlayer instanceof EntityPlayerMP) || event.entityPlayer.capabilities.isCreativeMode)
            return;
        if (event.item.getItem() instanceof ItemFood || event.item.getItem() instanceof ItemPotion) action(
            event.entityPlayer.getUniqueID()
                .toString(),
            "items_consumed",
            event.item,
            1);
    }

    public void action(String player, String kind, ItemStack item, long amount) {
        if (!ready || amount <= 0) return;
        Map<String, String> total = Metrics.labels("player", player);
        metrics.add("gregfana_player_" + kind + "_total", total, amount, false);
        metrics.add(
            "gregfana_player_" + kind + "_by_item_total",
            Metrics.labels("player", player, "item", Items.id(item)),
            amount,
            true);
    }
}
