package io.github.alphain24.staffcore.permission;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who outranks whom, worked out from what they hold rather than from a list of titles.
 * <p>
 * A staff member banning another staff member is either the most necessary action on the
 * server or the most damaging, and which one it is depends entirely on the direction. A
 * moderator removing a helper who has gone bad is the system working; a helper removing the
 * admin who was about to remove them is the system being used against itself, and by the time
 * anybody notices the person who could have fixed it is banned.
 *
 * <h2>Rank is the node set</h2>
 * There is no rank field anywhere in this mod, and adding one would create a second source of
 * truth that drifts from the permissions actually granted — a "senior moderator" who was
 * quietly given every admin node, or an "admin" whose group was emptied. What somebody can
 * actually do is what they hold, so that is what is compared.
 * <p>
 * A strictly outranks B when A holds every StaffCore node B holds, and at least one more.
 * Anything else — equal sets, or two sets neither of which contains the other — is <b>not</b>
 * outranking, and the punishment is refused. Incomparable is treated as equal on purpose: two
 * staff members with different specialities have no ordering between them, and inventing one
 * would be inventing exactly the authority this exists to check.
 *
 * <h2>What this cannot see</h2>
 * With a permissions API installed, an offline player's nodes cannot be read — the API answers
 * about a live player. For an offline target the answer is therefore drawn from StaffCore's
 * own group file and the vanilla operator list, and {@link Ranking#complete} says whether that
 * was the whole picture. A caller that cares should refuse rather than assume; refusing to ban
 * an offline account for a few seconds is recoverable, and banning the admin is not.
 */
public final class Rank {
	private Rank() {}

	/**
	 * What one person holds, for comparison.
	 *
	 * @param complete false when this could not be resolved fully — an offline player on a
	 *                 server whose permissions live in a plugin we cannot ask about them
	 */
	public record Held(Set<String> nodes, boolean operator, boolean complete) {

		/** Somebody with nothing. An ordinary player, and the commonest target by far. */
		public boolean isStaff() {
			return operator || !nodes.isEmpty();
		}
	}

	/** The verdict, and the sentence to show when it is no. */
	public record Ranking(boolean allowed, String refusal, boolean complete) {

		static final Ranking OK = new Ranking(true, null, true);

		static Ranking no(String refusal, boolean complete) {
			return new Ranking(false, refusal, complete);
		}
	}

	/** What an actor holds. Already resolved — this is the whole point of the type. */
	public static Held of(Actor actor) {
		if (actor == null) return new Held(Set.of(), false, true);
		return new Held(actor.nodes(), actor.operator(), true);
	}

	/**
	 * What a target holds, online or not.
	 * <p>
	 * An online target is resolved the same way anybody else is. An offline one is resolved
	 * from the group file and the operator list, which is everything StaffCore itself knows
	 * and less than a permissions plugin knows.
	 */
	public static Held of(MinecraftServer server, UUID id, String name) {
		if (server == null || id == null) return new Held(Set.of(), false, false);

		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return of(Actor.of(online));

		boolean operator = isListedOperator(server, id, name);
		PermissionGroups groups = PermissionGroups.get();
		if (groups == null) {
			return new Held(Set.of(), operator, !Permissions.hasProvider());
		}

		Set<String> held = new LinkedHashSet<>();
		for (String node : Actor.all()) {
			Boolean answer = groups.check(id, name, node);
			if (Boolean.TRUE.equals(answer)) held.add(node);
		}
		if (operator && groups.operatorsBypass) held.addAll(Actor.all());

		// A permissions plugin is the authority where one exists, and it cannot be asked
		// about somebody who is not connected. Saying so is the only honest answer.
		return new Held(Collections.unmodifiableSet(held), operator, !Permissions.hasProvider());
	}

	/** Convenience for the punishment path, which deals in profiles. */
	public static Held of(MinecraftServer server, NameAndId target) {
		return target == null ? new Held(Set.of(), false, false)
				: of(server, target.id(), target.name());
	}

	/**
	 * Whether this actor may punish this target.
	 * <p>
	 * The console and anything else without an identity is exempt. It is the server owner's
	 * own hand, it already holds everything, and a rule that stopped it would leave a server
	 * whose two admins had fallen out with no way to resolve it at all. That exemption is the
	 * escape hatch for the case this rule otherwise makes unfixable, and it is audited like
	 * everything else.
	 */
	public static Ranking mayPunish(Actor actor, MinecraftServer server, UUID targetId,
			String targetName) {

		// The console is exempt; a name nobody could resolve is not. Both arrive here with no
		// UUID, and telling them apart matters: the console holds every node and is the
		// server owner's own hand, while an unresolvable name is an action attributed to
		// somebody we cannot confirm was there. Treating the second as the first would make
		// the guard skippable by acting under a name that is not online.
		if (actor == null) return Ranking.OK;
		if (actor.id() == null) {
			return actor.operator() ? Ranking.OK
					: Ranking.no("This action is attributed to \"" + actor.name() + "\", who "
							+ "could not be identified. Refusing rather than acting on an "
							+ "unverified name.", true);
		}

		if (actor.id().equals(targetId)) {
			return Ranking.no("You cannot punish yourself. If you meant to demonstrate "
					+ "something, ask somebody else to do it — a self-issued punishment is "
					+ "indistinguishable in the record from one you were talked into.", true);
		}

		Held target = of(server, targetId, targetName);
		if (!target.isStaff()) return Ranking.OK;

		Held mine = of(actor);
		if (!target.complete()) {
			return Ranking.no(targetName + " is staff, and their permissions live in a plugin "
					+ "that cannot be asked about somebody who is offline. Refusing rather than "
					+ "guessing — wait until they are online, or do it from the console.", false);
		}
		if (outranks(mine, target)) return Ranking.OK;

		return Ranking.no(targetName + " holds at least as much as you do. Punishing sideways "
				+ "or upwards is refused: the case where it matters is somebody removing the "
				+ "person who was about to remove them, and by the time anybody notices, the "
				+ "one who could have fixed it is gone. Ask an admin, or use the console.",
				true);
	}

	/**
	 * Strict containment, in both directions.
	 * <p>
	 * Equal sets are not outranking, and neither are two sets that merely differ. The second
	 * is the case worth being deliberate about: a staff member who holds rollback but not
	 * bans, and one who holds bans but not rollback, have no ordering between them, and
	 * picking one would be inventing the authority this is here to check.
	 */
	static boolean outranks(Held mine, Held theirs) {
		if (theirs.operator() && !mine.operator()) return false;
		if (!mine.nodes().containsAll(theirs.nodes())) return false;

		boolean strictlyMore = mine.nodes().size() > theirs.nodes().size()
				|| (mine.operator() && !theirs.operator());
		return strictlyMore;
	}

	private static boolean isListedOperator(MinecraftServer server, UUID id, String name) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return Mc.isModerator(online);

		try {
			return server.getPlayerList().getOps().get(new NameAndId(id, name)) != null;
		} catch (RuntimeException e) {
			// An op list we cannot read is one we must not assume is empty: assuming empty
			// would turn "cannot tell" into "not staff", which is the unsafe direction.
			return true;
		}
	}
}
