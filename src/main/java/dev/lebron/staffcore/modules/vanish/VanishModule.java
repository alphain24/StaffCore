package dev.lebron.staffcore.modules.vanish;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.config.StaffConfig;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Module;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Full invisibility from players who lack {@link Nodes#VANISH}.
 * <p>
 * Three layers, because any one of them alone leaks:
 * <ol>
 *   <li>an infinite invisibility effect, so armour and held items do not render;</li>
 *   <li>removal from the tab list, so nobody counts heads;</li>
 *   <li>the entity itself never being tracked by unauthorised clients.</li>
 * </ol>
 * The third is the one that used to be wrong. StaffCore sent removal packets by hand and
 * cancelled the tracker's decision, which told every client to forget the player but left
 * the <em>server</em> believing they could still see them — so un-vanishing sent no spawn
 * packet and the staff member stayed invisible. {@code VanishBroadcastMixin} now answers
 * vanilla's own {@code broadcastToPlayer} question instead, so hiding and revealing both go
 * through the supported path and neither needs a packet from here.
 * <p>
 * All state lives in {@link VanishState}. It used to be spread across four collections plus
 * the database, updated by hand at every call site, which is precisely how vanish ended up
 * half-applied.
 */
public class VanishModule implements Module {

	@Override
	public String id() {
		return "vanish";
	}

	@Override
	public String displayName() {
		return "Vanish";
	}

	/** How often the "you are vanished" reminder is refreshed, in ticks. */
	private static final int REMINDER_INTERVAL = 20;

	private final VanishState state = new VanishState();
	private int tick;
	private boolean listenerRegistered;

	/** The single source of truth for who is hidden. */
	public VanishState state() {
		return state;
	}

	@Override
	public void onEnable() {
		if (listenerRegistered) return;
		listenerRegistered = true;

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (state.isEmpty()) return;
			tick++;

			// No re-hide sweep: the tracker refuses the pairing itself, so a vanished player
			// is never sent to an unauthorised client at all. What is left is the physical
			// tell — a mob swinging at thin air.
			for (ServerPlayer hidden : server.getPlayerList().getPlayers()) {
				if (!isVanished(hidden)) continue;
				shakeMobs(hidden);
				if (tick % REMINDER_INTERVAL == 0) {
					hidden.sendOverlayMessage(Icon.text("You are vanished", Theme.ACCENT));
				}
			}
		});
	}

	@Override
	public void onDisable() {
		state.clear();
	}

	public boolean isVanished(ServerPlayer player) {
		return state.isHidden(player);
	}

	public int vanishedCount() {
		return state.count();
	}

	/** Returns the new state: true = now invisible. */
	public boolean toggle(ServerPlayer player) {
		MinecraftServer server = Mc.server(player);
		if (server == null) return false;

		var wasHidden = state.get(player.getUUID());
		if (wasHidden.isPresent()) {
			state.reveal(player.getUUID());
			reveal(server, player, wasHidden.get());
			announceFake(server, player, true);
			player.sendSystemMessage(Theme.info("You are visible again."));
			Sfx.unvanish(player);
			return false;
		}

		// The fake leave goes out *before* concealment, so the message is broadcast while
		// the player is still a normal, visible participant — exactly as a real disconnect
		// would look to everyone else.
		announceFake(server, player, false);

		conceal(server, player, state.conceal(player, VanishState.Phase.ACTIVE));
		player.sendSystemMessage(Theme.good("You are vanished — everyone was told you left."));
		Sfx.vanish(player);
		return true;
	}

	/**
	 * Sends the vanilla join or leave line as though the player really had connected or
	 * disconnected.
	 * <p>
	 * This is what makes vanish believable rather than merely invisible. A staff member who
	 * simply blinks out of existence tells every observant player that vanish exists and
	 * that somebody is watching; one who "leaves the game" is indistinguishable from
	 * somebody who actually did. The same vanilla translation keys are used, so the text is
	 * byte-identical to a genuine event in every language.
	 * <p>
	 * Staff who can see through vanish are excluded — they get the truth instead.
	 */
	private void announceFake(MinecraftServer server, ServerPlayer player, boolean joining) {
		if (!StaffConfig.get().vanishFakeMessages) return;

		Component line = Component.translatable(
				joining ? "multiplayer.player.joined" : "multiplayer.player.left",
				player.getDisplayName()).withStyle(ChatFormatting.YELLOW);

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == player || canSeeVanished(viewer)) continue;
			viewer.sendSystemMessage(line);
		}

		// Staff see what actually happened, so nobody is confused by a colleague who is
		// still visibly standing there after "leaving".
		Component truth = Theme.prefix()
				.append(Icon.text(Mc.name(player), Theme.ACCENT))
				.append(Icon.text(joining ? " un-vanished" : " vanished", Theme.MUTED));
		for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
			if (staff != player && canSeeVanished(staff)) {
				staff.sendSystemMessage(truth);
			}
		}
	}

	// ------------------------------------------------------------- hide and reveal

	private void conceal(MinecraftServer server, ServerPlayer player, VanishState.Hidden entry) {
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,
				MobEffectInstance.INFINITE_DURATION, 0, false, false));

		// A vanished staff member should leave no trace in the world either: nothing can
		// hurt them, nothing targets them, and they stop colliding with anything.
		player.getAbilities().invulnerable = true;
		player.getAbilities().mayfly = true;
		player.onUpdateAbilities();
		player.setInvisible(true);

		// Vanilla's own "does this entity stop you building here" flag. Standing in a
		// doorway and silently refusing everybody's block placements is one of the loudest
		// tells vanish has, and this is the exact field the placement check reads — no
		// mixin needed, and nothing else in the game writes it for players.
		player.blocksBuilding = false;

		// The tab list is the one thing the tracker does not own, so it is still explicit.
		// The entity itself is not: refusing the pairing is now the tracker's job, and it
		// tears down and rebuilds cleanly on its own.
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == player || canSeeVanished(viewer)) continue;
			viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
		}

		refreshWaypoints(player);
		shakeMobs(player);
	}

	/**
	 * Puts back everything {@link #conceal} changed.
	 * <p>
	 * Abilities are restored from what was captured at conceal time rather than rebuilt from
	 * the current gamemode. Rebuilding them was wrong whenever something else had set them:
	 * a staff member flying on duty lost flight the moment they un-vanished, because
	 * {@code isCreative()} said they should not have it.
	 */
	private void reveal(MinecraftServer server, ServerPlayer player, VanishState.Hidden was) {
		player.removeEffect(MobEffects.INVISIBILITY);
		player.setInvisible(false);
		player.blocksBuilding = true;
		player.getAbilities().invulnerable = was.priorInvuln();
		player.getAbilities().mayfly = was.priorFlight();
		if (!was.priorFlight()) player.getAbilities().flying = false;
		player.onUpdateAbilities();

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == player) continue;
			viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
		}
		refreshWaypoints(player);
		// The entity comes back on its own: broadcastToPlayer now answers true, so the
		// tracker re-pairs and spawns it on the very next tick without a packet from here.
	}

	/**
	 * Rebuilds this player's locator-bar connections.
	 * <p>
	 * {@code VanishWaypointMixin} refuses new connections, but connections made before the
	 * player vanished are already open and keep transmitting. Remaking them runs every one
	 * back through that refusal, so existing viewers lose the waypoint at the same instant
	 * new ones are denied it. The same call un-hides on reveal, since the refusal no longer
	 * applies.
	 */
	private void refreshWaypoints(ServerPlayer player) {
		try {
			player.level().getWaypointManager().remakeConnections(player);
		} catch (RuntimeException e) {
			// The locator bar is cosmetic; a failure here must never take vanish with it.
			StaffCore.LOGGER.warn("[Vanish] Could not refresh waypoints: {}", e.getMessage());
		}
	}

	/**
	 * Clears any mob currently targeting a vanished player.
	 * <p>
	 * Invisibility alone does not stop hostile mobs tracking you — they keep pathing and
	 * swinging, which gives the position away far more loudly than a rendered skin would.
	 */
	private void shakeMobs(ServerPlayer hidden) {
		AABB around = hidden.getBoundingBox().inflate(24.0D);
		for (Mob mob : hidden.level().getEntitiesOfClass(Mob.class, around,
				m -> m.getTarget() == hidden)) {
			mob.setTarget(null);
		}
	}

	// ------------------------------------------------------------------ questions

	/** Consulted by the optional mixin that stops vanished staff hoovering up drops. */
	public boolean blocksItemPickup(ServerPlayer player) {
		return StaffConfig.get().vanishBlocksPickup && isVanished(player);
	}

	/**
	 * True when this broadcast is an announcement about somebody currently hidden.
	 * <p>
	 * Two different settings govern two different cases, so the phase matters: a join or
	 * leave line belongs to a player mid-transition and is covered by
	 * {@code vanishSilentJoin}, while a death or advancement belongs to somebody already
	 * hidden and is covered by {@code vanishHidesAnnouncements}.
	 */
	public boolean shouldSuppressBroadcast(Component message) {
		StaffConfig cfg = StaffConfig.get();
		if (!cfg.vanishHidesAnnouncements && !cfg.vanishSilentJoin) return false;

		return state.namesHiddenPlayer(message.getString(),
				cfg.vanishHidesAnnouncements, cfg.vanishSilentJoin);
	}

	/** Whether this viewer is cleared to see through vanish. Public for the join mixin. */
	public boolean canSeeVanished(ServerPlayer viewer) {
		return Permissions.check(viewer, Nodes.VANISH);
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Re-applies vanish from stored state at the very start of login, before vanilla has
	 * announced the join. Called by {@code VanishJoinMixin}.
	 */
	public void restoreBeforeJoin(ServerPlayer player) {
		if (!VanishState.storedVanished(player.getUUID())) return;

		// Abilities are captured as they are right now, before anything of ours touches
		// them, so un-vanishing later puts back what the player actually had.
		state.restoreOnLogin(player.getUUID(), player.nameAndId().name(),
				player.getAbilities().mayfly, player.getAbilities().invulnerable);
	}

	/**
	 * Runs once the joining player is fully in the world.
	 * <p>
	 * Two jobs. Hide anyone already vanished from a newcomer who should not see them — the
	 * tracker covers the entity, but the tab list is sent during login and needs clearing
	 * explicitly. And tell a staff member whose vanish survived the reconnect.
	 */
	public void onPlayerJoined(MinecraftServer server, ServerPlayer joiner) {
		// Deferred by a tick so this runs after placeNewPlayer has finished rather than
		// halfway through it.
		server.execute(() -> {
			if (joiner.hasDisconnected()) return;

			if (!canSeeVanished(joiner)) {
				for (ServerPlayer hidden : server.getPlayerList().getPlayers()) {
					if (hidden != joiner && isVanished(hidden)) {
						joiner.connection.send(
								new ClientboundPlayerInfoRemovePacket(List.of(hidden.getUUID())));
					}
				}
			}

			if (state.takeGreeting(joiner.getUUID())) {
				// Vanish held across the reconnect, and nobody was told they arrived.
				state.get(joiner.getUUID()).ifPresent(entry -> conceal(server, joiner, entry));
				joiner.sendSystemMessage(Theme.good("Still vanished — your join was not announced."));
				Sfx.vanish(joiner);
			}

			// The join is done; anything broadcast about them from here on is a normal
			// event that should be seen.
			state.phase(joiner.getUUID(), VanishState.Phase.ACTIVE);
		});
	}

	/**
	 * Marks the leave line for suppression before it is broadcast.
	 * <p>
	 * A phase change rather than a separate map. The old version cached the name into a
	 * second collection here and cleared it somewhere else, which meant the two could
	 * disagree about who was leaving; now there is one record and it simply moves.
	 */
	public void onPlayerLeaving(ServerPlayer player) {
		state.phase(player.getUUID(), VanishState.Phase.LEAVING);
	}

	/**
	 * Drops every trace of a departing player from memory.
	 * <p>
	 * Called from {@code VanishLeaveMixin} rather than from Fabric's disconnect event,
	 * because that event runs before vanilla broadcasts the leave line and this clears the
	 * state that suppresses it. Idempotent, so a second call from any other path is safe —
	 * and because the phase is what suppression reads, an <em>early</em> call now costs a
	 * leaked message at worst rather than corrupting anything.
	 * <p>
	 * The stored flag is left alone on purpose: vanish is meant to survive a reconnect.
	 */
	public void onPlayerLeft(UUID player) {
		state.forget(player);
	}
}
