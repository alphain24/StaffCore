package dev.lebron.staffcore.modules.vanish;

import dev.lebron.staffcore.StaffCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single source of truth for who is hidden.
 * <p>
 * Vanish state used to live in four separate collections on the module — a set of hidden
 * ids, a map of names for the ones currently hidden, another map of names whose join or
 * leave line was being swallowed, and a set of people owed a greeting — plus the database,
 * plus whatever a mixin happened to ask. Nothing kept them in step. Every collection was
 * updated by hand at each of half a dozen call sites, and a path that updated three of the
 * four left vanish half-applied: hidden from the tab list but visible in the world, or the
 * reverse. That is the shape of "sometimes vanish bugs out".
 * <p>
 * One record per player fixes that by construction. There is nothing to keep in step,
 * because there is only one thing. The database write is folded into the same call that
 * changes memory, so a restart cannot disagree with a running server either.
 * <p>
 * Concurrency: reads come from the entity tracker, which runs on the server thread, and
 * from the status ping, which does not. A concurrent map costs nothing at this size and
 * removes the question.
 */
public final class VanishState {

	/**
	 * How far through the lifecycle a hidden player is.
	 * <p>
	 * The phase exists so teardown stops depending on which event fires first. Vanilla
	 * broadcasts the leave line from {@code removePlayerFromWorld} and then tears the player
	 * down in {@code PlayerList#remove}, while Fabric's disconnect event beats both — so
	 * "clear the state on disconnect" destroyed the very thing that suppresses the message.
	 * Marking {@link #LEAVING} instead of deleting means a late arrival finds what it needs
	 * and an early one cannot break anything.
	 */
	public enum Phase {
		/** Hidden and fully in the world. */
		ACTIVE,
		/** Hidden, mid-login: the join announcement is still to come and must be swallowed. */
		JOINING,
		/** Hidden, mid-logout: the leave announcement is still to come and must be swallowed. */
		LEAVING
	}

	/**
	 * Everything known about one hidden player.
	 *
	 * @param name        cached because suppression has to work at the two moments the
	 *                    player is <em>not</em> in the player list — the head of
	 *                    {@code placeNewPlayer}, before they are added, and the disconnect
	 *                    broadcast, after they are gone. Looking it up live returned null
	 *                    both times, which is exactly why join and leave lines leaked.
	 * @param greetOnJoin they reconnected still hidden and have not been told yet
	 * @param priorFlight what {@code mayfly} was before vanish touched it
	 * @param priorInvuln what {@code invulnerable} was before vanish touched it
	 */
	public record Hidden(UUID id, String name, Phase phase, boolean greetOnJoin,
			boolean priorFlight, boolean priorInvuln) {

		Hidden with(Phase next) {
			return new Hidden(id, name, next, greetOnJoin, priorFlight, priorInvuln);
		}

		Hidden greeted() {
			return new Hidden(id, name, phase, false, priorFlight, priorInvuln);
		}
	}

	private final Map<UUID, Hidden> hidden = new ConcurrentHashMap<>();

	// --------------------------------------------------------------------- reading

	public boolean isHidden(UUID player) {
		return hidden.containsKey(player);
	}

	public boolean isHidden(ServerPlayer player) {
		return player != null && hidden.containsKey(player.getUUID());
	}

	public Optional<Hidden> get(UUID player) {
		return Optional.ofNullable(hidden.get(player));
	}

	public Collection<Hidden> all() {
		return hidden.values();
	}

	public boolean isEmpty() {
		return hidden.isEmpty();
	}

	public int count() {
		return hidden.size();
	}

	/**
	 * True when an announcement naming this player should be swallowed.
	 * <p>
	 * Matching on the rendered text is not something to be proud of, but vanilla builds and
	 * broadcasts a join, leave, death or advancement line in one step, and there is no call
	 * site to flag. One text check covers all of them and keeps covering whatever the next
	 * version adds, where wrapping each call site would need a new injection point per
	 * message type and break silently when one moved.
	 */
	public boolean namesHiddenPlayer(String text, boolean includeActive, boolean includeTransitions) {
		if (hidden.isEmpty() || text == null) return false;

		for (Hidden entry : hidden.values()) {
			boolean transitioning = entry.phase() != Phase.ACTIVE;
			if (transitioning ? !includeTransitions : !includeActive) continue;
			if (text.contains(entry.name())) return true;
		}
		return false;
	}

	// --------------------------------------------------------------------- writing

	/**
	 * Marks a player hidden, remembering the abilities vanish is about to overwrite.
	 * <p>
	 * Capturing them here rather than reconstructing them on reveal is the fix for vanish
	 * fighting staff mode: the old code rebuilt {@code mayfly} and {@code invulnerable} from
	 * {@code isCreative()}, which is not where they came from when staff mode set them, so
	 * un-vanishing silently took a staff member's flight away.
	 */
	public Hidden conceal(ServerPlayer player, Phase phase) {
		Hidden entry = new Hidden(player.getUUID(), player.nameAndId().name(), phase, false,
				player.getAbilities().mayfly, player.getAbilities().invulnerable);

		hidden.put(entry.id(), entry);
		persist(entry.id(), true);
		return entry;
	}

	/** Marks a player hidden from stored state during login, before vanilla announces them. */
	public Hidden restoreOnLogin(UUID id, String name, boolean flight, boolean invulnerable) {
		Hidden entry = new Hidden(id, name, Phase.JOINING, true, flight, invulnerable);
		hidden.put(id, entry);
		// No persist: this is reading what is already on disk, not deciding anything.
		return entry;
	}

	/** Stops hiding a player and clears the stored flag. Returns what was there, if anything. */
	public Optional<Hidden> reveal(UUID player) {
		Hidden gone = hidden.remove(player);
		if (gone != null) persist(player, false);
		return Optional.ofNullable(gone);
	}

	/**
	 * Moves a player to a new lifecycle phase, if they are hidden at all.
	 * <p>
	 * Idempotent on purpose. Disconnect paths can fire more than once and in an order that
	 * is not guaranteed; none of them should be able to corrupt anything by arriving twice.
	 */
	public void phase(UUID player, Phase next) {
		hidden.computeIfPresent(player, (id, entry) -> entry.with(next));
	}

	/** Marks the greeting as delivered. Returns true when one was actually owed. */
	public boolean takeGreeting(UUID player) {
		Hidden entry = hidden.get(player);
		if (entry == null || !entry.greetOnJoin()) return false;
		hidden.put(player, entry.greeted());
		return true;
	}

	/**
	 * Drops all trace of a player who has left.
	 * <p>
	 * The stored flag is deliberately <em>not</em> cleared: vanish is meant to survive a
	 * reconnect, and forgetting it here is what would break that.
	 */
	public void forget(UUID player) {
		hidden.remove(player);
	}

	/** Called on shutdown. Memory only — the database keeps its own answer. */
	public void clear() {
		hidden.clear();
	}

	// -------------------------------------------------------------------- plumbing

	private void persist(UUID player, boolean vanished) {
		try {
			StaffCore.state().setVanished(player, vanished);
		} catch (RuntimeException e) {
			// A storage failure must not leave the in-memory state half-applied; the player
			// stays correctly hidden or visible for this session and loses only persistence.
			StaffCore.LOGGER.warn("[Vanish] Could not persist vanish state for {}: {}",
					player, e.getMessage());
		}
	}

	/** Whether stored state says this player was hidden when they last disconnected. */
	public static boolean storedVanished(UUID player) {
		try {
			return StaffCore.state().loadAll()
					.getOrDefault(player, dev.lebron.staffcore.storage.StateStore.State.empty())
					.vanished();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Flight and invulnerability as they should be with vanish switched off. */
	public static boolean naturalFlight(ServerPlayer player, GameType mode) {
		return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
	}
}
