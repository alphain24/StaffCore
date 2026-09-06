package io.github.alphain24.staffcore;

import net.minecraft.server.players.NameAndId;
import io.github.alphain24.staffcore.chat.ChatRouter;
import io.github.alphain24.staffcore.command.StaffCommands;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.ModuleManager;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.alerts.AlertsModule;
import io.github.alphain24.staffcore.modules.analytics.AnalyticsModule;
import io.github.alphain24.staffcore.modules.chat.StaffChatModule;
import io.github.alphain24.staffcore.modules.control.ControlModule;
import io.github.alphain24.staffcore.modules.discord.DiscordModule;
import io.github.alphain24.staffcore.modules.freeze.FreezeModule;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.modules.inventory.InventoryModule;
import io.github.alphain24.staffcore.modules.notes.NotesModule;
import io.github.alphain24.staffcore.modules.punish.PunishmentModule;
import io.github.alphain24.staffcore.modules.report.ReportModule;
import io.github.alphain24.staffcore.modules.security.SecurityModule;
import io.github.alphain24.staffcore.modules.staffmode.StaffModeModule;
import io.github.alphain24.staffcore.modules.teleport.TeleportModule;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.storage.Storage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point.
 * <p>
 * Order matters here: config, then modules, then the events that lean on both. Storage
 * opens on {@code SERVER_STARTING} rather than at init because the world directory does
 * not exist yet when mods initialise — every module that touches the database checks
 * {@link Storage#isReady()} and degrades quietly rather than throwing at boot.
 */
public class StaffCore implements ModInitializer {

	public static final String MOD_ID = "staffcore";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final ModuleManager MODULES = new ModuleManager();
	private static final Storage STORAGE = new Storage();
	private static final io.github.alphain24.staffcore.storage.StateStore STATE =
			new io.github.alphain24.staffcore.storage.StateStore();
	private static final io.github.alphain24.staffcore.storage.PendingActions PENDING =
			new io.github.alphain24.staffcore.storage.PendingActions();

	private static MinecraftServer server;
	private static long startedAt = System.currentTimeMillis();

	public static ModuleManager modules() {
		return MODULES;
	}

	public static Storage storage() {
		return STORAGE;
	}

	/**
	 * The anti-cheat bridge, for other mods to push findings into.
	 * <p>
	 * This is the supported integration point and the whole public surface of it. An
	 * anti-cheat, a bridge mod or a server owner's own code builds an
	 * {@link io.github.alphain24.staffcore.modules.anticheat.AntiCheatEvent} and calls
	 * {@code report} on it; everything downstream — the alert, the record, the player's file —
	 * follows from that one call. No reflection, no version coupling, nothing to keep in step
	 * with StaffCore's internals.
	 */
	public static io.github.alphain24.staffcore.modules.anticheat.AntiCheatModule antiCheat() {
		return io.github.alphain24.staffcore.module.Mods.antiCheat();
	}

	/** Vanish, freeze, staff mode and stashes that survive a restart. */
	public static io.github.alphain24.staffcore.storage.StateStore state() {
		return STATE;
	}

	/** Items owed to players who were offline when staff decided them. Settled on join. */
	public static io.github.alphain24.staffcore.storage.PendingActions pending() {
		return PENDING;
	}

	public static long startedAt() {
		return startedAt;
	}

	/** The running server, or null before start / after stop. */
	public static MinecraftServer server() {
		return server;
	}

	/**
	 * Op check that works without an online player — used by the login gate, which runs
	 * before a {@code ServerPlayer} exists for the connecting profile.
	 */
	public static boolean isOperator(NameAndId profile) {
		return server != null && server.getPlayerList().isOp(profile);
	}

	/** Drives {@link io.github.alphain24.staffcore.gui.Gui#autoRefresh(int)}; wraps harmlessly. */
	private int guiTick;

	@Override
	public void onInitialize() {
		LOGGER.info("[StaffCore] Starting up for Minecraft 26.2");

		StaffConfig.load();
		io.github.alphain24.staffcore.permission.PermissionGroups.load(Permissions.hasProvider());
		registerModules();
		registerLifecycle();
		registerPlayerEvents();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				StaffCommands.register(dispatcher));
		ChatRouter.register();

		MODULES.enableAll();
		LOGGER.info("[StaffCore] {} module(s) enabled.", MODULES.count());
	}

	/** Features whose hooks were missing at boot. Empty on a healthy server. */
	private static java.util.List<String> brokenFeatures = java.util.List.of();

	public static java.util.List<String> brokenFeatures() {
		return brokenFeatures;
	}

	// ------------------------------------------------------------------- modules

	private void registerModules() {
		// Core
		MODULES.register(new StaffModeModule());
		MODULES.register(new VanishModule());
		MODULES.register(new FreezeModule());
		MODULES.register(new PunishmentModule());
		MODULES.register(new NotesModule());
		MODULES.register(new StaffChatModule());
		MODULES.register(new AlertsModule());
		MODULES.register(new ReportModule());
		MODULES.register(new TeleportModule());
		MODULES.register(new io.github.alphain24.staffcore.modules.identity.IdentityModule());
		MODULES.register(new io.github.alphain24.staffcore.modules.identity.EvasionModule());
		MODULES.register(new io.github.alphain24.staffcore.modules.appeal.AppealModule());

		// Addons
		MODULES.register(new InventoryModule());
		MODULES.register(new SecurityModule());
		MODULES.register(new GriefModule());
		MODULES.register(new DiscordModule());
		MODULES.register(new AnalyticsModule());
		MODULES.register(new io.github.alphain24.staffcore.modules.anticheat.AntiCheatModule());
		MODULES.register(new ControlModule());
	}

	// ----------------------------------------------------------------- lifecycle

	private void registerLifecycle() {
		ServerLifecycleEvents.SERVER_STARTING.register(mc -> {
			server = mc;
			startedAt = System.currentTimeMillis();
			Path worldDir = mc.getWorldPath(LevelResource.ROOT);
			STORAGE.open(worldDir);
		});

		// After the world is up, so every class the check touches is loaded and the report
		// lands where an admin will actually see it rather than buried in early boot spam.
		// Live screens redraw themselves. Registered once here rather than per screen so
		// there is exactly one walk of the player list per tick however many staff are
		// looking at something.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(mc -> {
			guiTick++;
			for (ServerPlayer player : mc.getPlayerList().getPlayers()) {
				if (player.containerMenu instanceof io.github.alphain24.staffcore.gui.Gui gui) {
					gui.autoRefresh(guiTick);
				}
			}
		});

		// Continuous integration boots a real server and asks the mod to prove it works,
		// rather than only that it started. Off unless explicitly requested.
		ServerLifecycleEvents.SERVER_STARTED.register(mc -> {
			if (io.github.alphain24.staffcore.diagnostic.SelfTest.requestedAtBoot()) {
				io.github.alphain24.staffcore.diagnostic.SelfTest.runAndLog(mc);
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(mc ->
				brokenFeatures = io.github.alphain24.staffcore.diagnostic.StartupCheck.run());

		ServerLifecycleEvents.SERVER_STOPPING.register(mc -> {
			MODULES.disableAll();
			STORAGE.close();
			server = null;
		});
	}

	// ------------------------------------------------------------- player events

	private void registerPlayerEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			ServerPlayer player = handler.player;

			Mods.identity().recordJoin(player);
			Mods.evasion().screen(mc, player);

			// State restore runs before the greeting so a returning staff member is told
			// what StaffCore put back rather than discovering it themselves.
			Mods.staffMode().restoreOnJoin(mc, player);
			Mods.freeze().restoreOnJoin(player);
			Mods.vanish().onPlayerJoined(mc, player);

			// Anything staff decided while this player was offline — a vaulted item handed
			// back, a rollback debt — is settled here, then explained. Doing it silently is
			// how a moderation tool gets mistaken for an item-loss bug.
			PENDING.announce(player, PENDING.drainFor(player));

			greetStaff(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			ServerPlayer player = handler.player;

			// Before anything else: mark the leave line so a vanished staff member does not
			// announce themselves on the way out either.
			Mods.vanish().onPlayerLeaving(player);
			Mods.identity().recordLeave(player);

			// "I logged off with it" is the commonest inventory dispute there is, and this
			// is the only moment that can answer it. Ranked as routine, so it can never
			// evict a death or a deliberate capture.
			if (StaffConfig.get().autoSnapshotOnLogout) {
				Mods.inventory().capture(player, "On logout", "system",
						io.github.alphain24.staffcore.modules.inventory.InventoryModule.Kind.ROUTINE);
			}

			// Staff mode is persisted rather than unwound: a staff member who disconnects
			// on duty comes back on duty, with their stash still safely on disk.
			Mods.staffMode().onPlayerLeft(player);
			// Vanish is deliberately absent here. This event fires before vanilla has
			// broadcast "X left the game", so forgetting the vanish state now would delete
			// the very thing that suppresses that line. VanishLeaveMixin does it afterwards.
			Mods.freeze().onPlayerLeft(player.getUUID());
			Mods.teleport().forget(player.getUUID());
			Mods.control().forget(player.getUUID());
			Mods.security().forget(player.getUUID());
			Mods.grief().forget(player.getUUID());
			io.github.alphain24.staffcore.modules.staffmode.StaffToolset.forget(player.getUUID());
			ChatRouter.onPlayerLeft(player);
		});

		// A snapshot taken the instant before death is the one piece of evidence that
		// cannot be reconstructed afterwards.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
			if (entity instanceof ServerPlayer player) {
				Mods.inventory().capture(player, "On death", "system");

				String cause = damageSource.getLocalizedDeathMessage(player).getString();
				String killer = damageSource.getEntity() == null
						? null
						: damageSource.getEntity().getName().getString();
				Mods.identity().recordDeath(player, cause, killer);
			}
			return true;
		});
	}

	/** Tells staff what is waiting for them the moment they log in. */
	private void greetStaff(ServerPlayer player) {
		if (!Permissions.check(player, Nodes.STAFF_GUI)) return;

		int open = Mods.reports().openCount();
		player.sendSystemMessage(Theme.info("StaffCore ready — /staff opens the panel."));

		if (open > 0 && Permissions.check(player, Nodes.REPORT_VIEW)) {
			player.sendSystemMessage(Theme.warn(open + " report(s) are waiting."));
			Sfx.alertPing(player);
		}

		if (Mods.control().isMaintenance()) {
			player.sendSystemMessage(Theme.warn("Maintenance mode is on — nobody else can join."));
		}

		AlertsModule alerts = Mods.alerts();
		if (!alerts.isSubscribed(player)) {
			player.sendSystemMessage(Theme.info("Your alerts are silenced. /alerts turns them back on."));
		}

		LOGGER.debug("[StaffCore] Greeted staff member {}", Mc.name(player));
	}
}
