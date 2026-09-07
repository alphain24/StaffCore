package io.github.alphain24.staffcore.permission;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who is doing something, as data rather than as a player object.
 * <p>
 * Almost every decision this mod makes about a staff member — may they do this, have they done
 * too much of it lately, can they approve somebody else's action — depends on exactly three
 * things: who they are, what they hold, and where the instruction came from. None of that
 * needs a world, a position, an inventory or a network connection.
 * <p>
 * Taking a {@link ServerPlayer} for those decisions was a mistake with two costs. It made the
 * policy untestable, which is how a bypass test ended up reimplementing the rule it was
 * supposed to be checking. And it made every such check impossible to reach from anywhere
 * without a live player — which is precisely what a Discord-initiated action is, and what a
 * scheduled task is.
 *
 * <h2>A real boundary, not a wrapper</h2>
 * This deliberately holds <b>no reference</b> to a {@code ServerPlayer}, a server, or a level.
 * Permissions are resolved once at construction into a plain set of strings. If it kept the
 * player and asked it questions lazily, the coupling would have moved rather than gone, and
 * this would still be unconstructible in a test or from a bot thread.
 * <p>
 * Resolving eagerly is also the more correct reading for a single action: one decision made
 * against one snapshot of somebody's permissions, rather than a check that could answer
 * differently halfway through because a permissions plugin reloaded.
 *
 * @param nodes    every StaffCore node this actor holds, resolved at construction
 * @param operator the vanilla fallback, kept separately because it is not a node and some
 *                 checks care about the difference
 */
public record Actor(UUID id, String name, Source source, Set<String> nodes, boolean operator,
		long resolvedAt) {

	/**
	 * How old this resolution is.
	 * <p>
	 * Permissions are a snapshot, and a snapshot has an age. Every audit row records this so
	 * that "was this action authorised by a permission set read four seconds ago or forty
	 * minutes ago" is answerable after the fact rather than by reading the call graph.
	 * <p>
	 * The failure it makes visible: an actor resolved when a screen opened and used when it
	 * closed carries permissions from before whatever happened in between. That is fine while
	 * it is only attribution and quietly wrong the moment somebody adds a check.
	 */
	public long ageMillis() {
		return System.currentTimeMillis() - resolvedAt;
	}

	/** True when this resolution is old enough that a decision should not lean on it. */
	public boolean isStale(long maxAgeMillis) {
		return maxAgeMillis > 0 && ageMillis() > maxAgeMillis;
	}


	/**
	 * Where an instruction came from.
	 * <p>
	 * The distinction that matters is not "is this a player" but "is there a person answerable
	 * for this". A console command and a scheduled task are both nobody: they hold every
	 * permission, they are attributable to no account, and if something goes wrong there is no
	 * one to ask.
	 */
	public enum Source {
		/** Somebody in the world, typing. */
		PLAYER(true),
		/** The server console. Holds everything, answerable to nobody. */
		CONSOLE(false),
		/** A remote console. Same as CONSOLE, and reachable over the network. */
		RCON(false),
		/** A command block, a datapack function, or anything on a timer. */
		SCHEDULED(false),
		/** A Discord user whose account is linked, so the action ties back to a person. */
		DISCORD_LINKED(true),
		/** A Discord user with no linked account. Reading only. */
		DISCORD_UNLINKED(false),
		/** StaffCore acting on its own behalf. */
		SYSTEM(false);

		private final boolean accountable;

		Source(boolean accountable) {
			this.accountable = accountable;
		}

		/**
		 * Whether a real, identifiable person stands behind an instruction from here.
		 * <p>
		 * This is the property the two-person rule turns on, and stating it once on the
		 * source is what stops it being re-derived slightly differently every time somebody
		 * adds a new way in.
		 */
		public boolean isAccountable() {
			return accountable;
		}
	}

	/** True when a named person can be held responsible for what this actor did. */
	public boolean isAccountable() {
		return source.isAccountable() && id != null;
	}

	/** Whether this actor holds a node. A pure lookup — no server, no plugin, no I/O. */
	public boolean has(String node) {
		return nodes.contains(node) || operator;
	}

	// ------------------------------------------------------------------ building

	/**
	 * Resolves a live player into an actor.
	 * <p>
	 * The one place that touches a {@code ServerPlayer}. Everything downstream works from the
	 * result, which is why the rest of the mod's policy is testable.
	 */
	public static Actor of(ServerPlayer player) {
		if (player == null) return console();
		return new Actor(player.getUUID(), Mc.name(player), Source.PLAYER,
				resolve(player), Mc.isModerator(player), System.currentTimeMillis());
	}

	/**
	 * From a command source, which may or may not be a player.
	 * <p>
	 * A command source with no player is the console or something standing in for it. Vanilla
	 * gives those full permission, and that is right for what they do and wrong for deciding
	 * who is answerable — so they get {@link Source#CONSOLE} and the accountability rule takes
	 * it from there.
	 */
	public static Actor of(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player != null) return of(player);

		return new Actor(null, source.getTextName(), Source.CONSOLE, all(), true,
				System.currentTimeMillis());
	}

	/** The console: every permission, no identity. */
	public static Actor console() {
		return new Actor(null, "Console", Source.CONSOLE, all(), true,
				System.currentTimeMillis());
	}

	/** StaffCore itself, for things nobody asked for directly. */
	public static Actor system() {
		return new Actor(null, "SYSTEM", Source.SYSTEM, all(), true,
				System.currentTimeMillis());
	}

	/**
	 * An actor built from identity alone.
	 * <p>
	 * For tests, and for Phase 5's Discord path, where there is a UUID and a resolved set of
	 * nodes and no player object anywhere. That this compiles without a server is the whole
	 * point of the type.
	 */
	public static Actor of(UUID id, String name, Source source, Set<String> nodes) {
		return new Actor(id, name, source, Set.copyOf(nodes), false,
				System.currentTimeMillis());
	}

	/**
	 * An actor we know the name of and nothing else.
	 * <p>
	 * For paths that were carrying a bare name string before this type existed — a rollback
	 * that knows who ran it but not whether they are still online, an internal settlement
	 * attributed to "system". It is deliberately <b>not accountable</b>: a name with no
	 * verified identity behind it is exactly what somebody would supply if they wanted an
	 * action attributed to someone else, and the type should say so rather than quietly
	 * passing for a person.
	 * <p>
	 * Holds no permissions either, so anything gated will refuse it. That is the safe
	 * direction: a caller that needs one of these to pass a check has to resolve a real
	 * identity first.
	 */
	public static Actor named(String name) {
		return new Actor(null, name == null ? "unknown" : name, Source.SYSTEM, Set.of(),
				false, System.currentTimeMillis());
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * Every node this player holds, asked once.
	 * <p>
	 * Enumerated from {@link Nodes} by reflection rather than from a hand-written list, so a
	 * node added later is resolved without anybody remembering to add it here. A node this
	 * misses would be silently denied, which is the quiet kind of wrong.
	 */
	private static Set<String> resolve(ServerPlayer player) {
		Set<String> held = new LinkedHashSet<>();
		for (String node : all()) {
			if (Permissions.check(player, node)) held.add(node);
		}
		return Collections.unmodifiableSet(held);
	}

	private static Set<String> allNodes;

	/** Every node StaffCore defines. Cached: the reflection is the same answer every time. */
	public static synchronized Set<String> all() {
		if (allNodes != null) return allNodes;

		Set<String> found = new LinkedHashSet<>();
		for (Field field : Nodes.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
				continue;
			}
			try {
				Object value = field.get(null);
				if (value instanceof String node) found.add(node);
			} catch (IllegalAccessException ignored) {
				// A node we cannot read is one we cannot resolve; nothing to do about it here.
			}
		}
		allNodes = Collections.unmodifiableSet(found);
		return allNodes;
	}
}
