package dev.thebluetaco.hostileskies;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.thebluetaco.hostileskies.command.NavDiagCommand;
import dev.thebluetaco.hostileskies.command.SpawnRaidCommand;
import dev.thebluetaco.hostileskies.raid.RaidConfig;
import dev.thebluetaco.hostileskies.raid.RaidManager;
import dev.thebluetaco.hostileskies.raid.RaidSavedData;
import dev.thebluetaco.hostileskies.raid.RaidSpawnSystem;
import dev.thebluetaco.hostileskies.registry.ModEntityTypes;
import dev.thebluetaco.hostileskies.registry.ModItems;
import dev.thebluetaco.hostileskies.ship.ShipRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(HostileSkies.MODID)
public class HostileSkies {
    public static final String MODID = "hostile_skies";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Debug logging toggle. Toggled at runtime via /hostileskies raidlog. */
    public static boolean debugLogging = false;
    private static MinecraftServer server;

    /** Logs to console. Additionally broadcasts to chat if the config is on. */
    public static void debug(String msg, Object... args) {
        String formatted = org.slf4j.helpers.MessageFormatter.arrayFormat(msg, args).getMessage();
        LOGGER.info(formatted);
        if (RaidConfig.debugChatMessages.get() && server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("\u00a77[Raid Debug] " + formatted));
            }
        }
    }

    public HostileSkies(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        ModEntityTypes.register(modEventBus);
        ModItems.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, RaidConfig.SPEC);
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
        LOGGER.info("Create Hostile Skies loading");
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ShipRegistry());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hostileskies");

        SpawnRaidCommand.register(root);
        NavDiagCommand.register(root);

        root.then(Commands.literal("killcount")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    RaidSavedData data = RaidSavedData.get(player.getServer());
                    int kills = data.getCaptainKills(player.getUUID());
                    int tier = data.getHighestUnlockedTier(kills);

                    StringBuilder msg = new StringBuilder(
                            "You have " + kills + " captain kills. ");
                    for (int next = tier + 1; next <= 4; next++) {
                        if (RaidConfig.isTierEnabled(next)) {
                            msg.append(RaidConfig.tierUnlockKills(next) - kills)
                                    .append(" more to unlock tier ").append(next);
                            break;
                        }
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(msg.toString()), false);
                    return kills;
                }));

        root.then(Commands.literal("raidlog")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    debugLogging = !debugLogging;
                    String state = debugLogging ? "ON" : "OFF";
                    LOGGER.info("Debug logging {}", state);
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("Hostile Skies debug logging: " + state), true);
                    return 1;
                }));

        event.getDispatcher().register(root);
        LOGGER.info("Registered /hostileskies commands (spawnraid, stopraid, shipnav, raidlog, killcount, ships)");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        server = event.getServer();
        RaidManager.tick(server);
        RaidSpawnSystem.tick(server);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RaidManager.onServerStopping(event.getServer());
        server = null;
    }

    @SubscribeEvent
    public void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof PathfinderMob captain
                && captain.getTags().contains(RaidManager.CAPTAIN_TAG)) {
            LOGGER.info("Captain hit! Source: {}", event.getSource().getEntity());
            if (event.getSource().getEntity() instanceof Player) {
                RaidManager.activateCaptain(captain);
            }
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob captain) {
            boolean wasActivated = captain.getTags().contains(RaidManager.ACTIVATED_CAPTAIN_TAG);
            boolean wasUnactivated = captain.getTags().contains(RaidManager.CAPTAIN_TAG);

            if (!wasActivated && !wasUnactivated) {
                // skip if not a raid captain
            }
            // Determine the responsible player. Covers a direct killer or just the last player to hit them
            // (captains tend to fall off of the smaller tier 1 ships during testing)
            else {
                Player killer = null;
                if (event.getSource().getEntity() instanceof Player p) {
                    killer = p;
                } else if (captain.getLastHurtByMob() instanceof Player p) {
                    killer = p;
                }

                if (wasActivated && killer != null
                        && killer.level() instanceof ServerLevel serverLevel
                        && RaidConfig.raidCaptainKillsCounted.get()) {
                    RaidSavedData data = RaidSavedData.get(serverLevel.getServer());
                    data.addCaptainKill(killer.getUUID());

                    int kills = data.getCaptainKills(killer.getUUID());
                    int tier = data.getHighestUnlockedTier(kills);
                    LOGGER.info("Captain killed by {}! Total kills: {}, highest tier: {}",
                            killer.getName().getString(), kills, tier);

                    ItemStack orders = new ItemStack(ModItems.CAPTAINS_ORDERS.get());
                    captain.spawnAtLocation(orders);
                    LOGGER.info("Captain dropped Captain's Orders at ({}, {}, {})",
                            (int) captain.getX(), (int) captain.getY(), (int) captain.getZ());
                } else {
                    LOGGER.info("Captain died to a non player cause. Triggering departure...");
                }

                RaidManager.onCaptainKilledForRaid(captain);
            }
        }
        // Player death aboard raid ship triggers mercy
        if (event.getEntity() instanceof ServerPlayer player) {
            RaidManager.onPlayerDeath(player);
        }
    }

    /** Server-side handler for locked steering wheel interactions.
     * The mixin handles client-side visuals (sound + message). */
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;

        BlockPos pos = event.getPos();
        if (!RaidManager.isWheelLocked(pos)) return;

        if (!(event.getLevel().getBlockState(pos).getBlock()
                instanceof dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock)) {
            return;
        }

        event.setCanceled(true);
        Player player = event.getEntity();

        // Check both hands, event fires per-hand
        boolean holdingOrders = player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.CAPTAINS_ORDERS.get())
                             || player.getItemInHand(InteractionHand.OFF_HAND).is(ModItems.CAPTAINS_ORDERS.get());

        if (holdingOrders && RaidConfig.enableShipCapture.get()) {
            InteractionHand ordersHand = player.getItemInHand(InteractionHand.MAIN_HAND)
                    .is(ModItems.CAPTAINS_ORDERS.get()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            player.getItemInHand(ordersHand).shrink(1);
            RaidManager.captureShipAtWheel(pos);
            player.level().playSound(null, pos,
                    SoundEvents.VAULT_OPEN_SHUTTER, SoundSource.BLOCKS, 1.0f, 1.0f);
            LOGGER.info("Player {} captured ship at wheel {}", player.getName().getString(), pos);
        } else {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(
                        Component.literal("\u00a75\u00a7l\u2618 \u00a75Helm is locked! \u00a7dBring the Captain's Orders!"),
                        true);
            }
        }
    }
}
