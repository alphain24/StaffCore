package io.github.alphain24.staffcore.permission;

import io.github.alphain24.staffcore.compat.Mc;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Thin wrapper over a permissions provider.
 * <p>
 * If <a href="https://github.com/lucko/fabric-permissions-api">fabric-permissions-api</a>
 * is on the classpath it is asked first, reflectively, so StaffCore picks up LuckPerms the
 * moment you install it. The lookups happen once at class-load; a miss costs nothing at runtime.
 *
 * <h2>The API being there is not a permissions mod being there</h2>
 * The API is a library, and plenty of mods that are not permissions mods ship it inside their own
 * jar so they can ask it questions. With nothing listening, it answers every question with
 * {@link TriState#DEFAULT}. Its two-argument {@code check} turns that into "no", and StaffCore
 * used to call exactly that and treat the API's presence as a permissions mod being in charge — so
 * on a server with one such mod and no LuckPerms, every node was refused to everybody, operators
 * included, {@code /staff} vanished from the command tree, and the groups file was never written.
 * <p>
 * So the API's answer is taken only where it has one. {@code TRUE} and {@code FALSE} are a
 * permissions mod speaking, and win outright. {@code DEFAULT} is nobody having an opinion, and
 * falls through to {@link PermissionGroups}, and only then to the vanilla operator level — the
 * same order a server without the API gets, and the convention most Fabric mods follow.
 */
public final class Permissions {
	private Permissions() {}

	private static final String API = "me.lucko.fabric.api.permissions.v0.Permissions";

	/** {@code getPermissionValue(Entity, String)}: what a permissions mod says, or DEFAULT. */
	private static final Method ONLINE = findMethod("getPermissionValue", Entity.class, String.class);
	/** {@code getPermissionValue(UUID, String)}: the same, about an account that may be offline. */
	private static final Method OFFLINE = findMethod("getPermissionValue", UUID.class, String.class);
	/** {@code check(Entity, String, boolean)} — undefined nodes fall back to the default. */
	private static final Method WITH_DEFAULT = findMethod("check", Entity.class, String.class, boolean.class);

	private static Method findMethod(String name, Class<?>... signature) {
		try {
			return Class.forName(API).getMethod(name, signature);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	/**
	 * True when fabric-permissions-api is on the classpath.
	 * <p>
	 * Not the same as a permissions mod being installed: see the class comment. Only a
	 * {@code TRUE} or {@code FALSE} answer means somebody is listening.
	 */
	public static boolean apiPresent() {
		return ONLINE != null;
	}

	/**
	 * A staff node, resolved through whichever authority has an answer.
	 * <p>
	 * A permissions mod wins where it has an opinion. Failing that, the built-in groups in
	 * {@code config/staffcore/permissions.json} get a say — without them every node fell back to
	 * the vanilla moderator level, which made every staff member equally powerful and left no way
	 * to say that a trainee should not be able to revoke bans. Op level is the last resort rather
	 * than the only one.
	 */
	public static boolean check(ServerPlayer player, String node) {
		if (player == null) return true; // console
		return resolve(fromApi(player, node), PermissionGroups.get(), player.getUUID(), Mc.name(player),
				Mc.isModerator(player), node);
	}

	/**
	 * The whole order, given what the API said.
	 * <p>
	 * Pure, so the rule that {@code DEFAULT} is not a refusal is tested rather than remembered.
	 */
	static boolean resolve(TriState fromApi, PermissionGroups groups, UUID id, String name,
			boolean moderator, String node) {

		if (fromApi == TriState.TRUE) return true;
		if (fromApi == TriState.FALSE) return false;
		return withoutProvider(groups, id, name, moderator, node);
	}

	/** What a permissions mod says about a connected player: DEFAULT when nobody has an answer. */
	private static TriState fromApi(ServerPlayer player, String node) {
		if (ONLINE == null) return TriState.DEFAULT;
		try {
			return ONLINE.invoke(null, player, node) instanceof TriState state ? state : TriState.DEFAULT;
		} catch (ReflectiveOperationException | RuntimeException e) {
			// A provider that throws has no answer; the built-in groups, then op level, decide.
			return TriState.DEFAULT;
		}
	}

	/**
	 * The answer {@link #check(ServerPlayer, String)} would give for an account that may not be
	 * online, or empty when a permissions mod has not got that answer ready.
	 * <p>
	 * The API can be asked about an offline account, and a permissions mod may have to load the
	 * account before it knows — so the answer comes back as a future. It is never waited for:
	 * this runs on the server thread, and a lookup that has to reach a database is exactly the
	 * one that would stall it. A future that is not finished yet is "cannot say", which callers
	 * treat as a refusal; the permissions mod goes on loading, so asking again shortly works.
	 * With nothing listening, the API finishes the future straight away with {@code DEFAULT}.
	 */
	static Optional<Boolean> checkOffline(PermissionGroups groups, UUID id, String name,
			boolean moderator, String node) {

		Optional<TriState> fromApi = offlineFromApi(id, node);
		return fromApi.map(state -> resolve(state, groups, id, name, moderator, node));
	}

	/** What a permissions mod has ready to say about an account, online or not. */
	static Optional<TriState> offlineFromApi(UUID id, String node) {
		if (OFFLINE == null) return Optional.of(TriState.DEFAULT);
		try {
			if (!(OFFLINE.invoke(null, id, node) instanceof CompletableFuture<?> future)
					|| !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
				return Optional.empty();
			}
			return future.getNow(null) instanceof TriState state ? Optional.of(state) : Optional.empty();
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Unlike a live check, "cannot say" here stays that: the caller refuses rather than
			// guessing about somebody who is not there to be asked again.
			return Optional.empty();
		}
	}

	/**
	 * The answer when no permissions mod has one, worked out from identity alone.
	 * <p>
	 * Separate so an account that is not online — a staff member acting from Discord — is asked
	 * the identical question rather than a copy of it. A copy is how "cannot do anything from
	 * Discord that you could not do in game" quietly stops being true: the in-game rule changes
	 * and the copy does not.
	 *
	 * @param moderator whether the account holds vanilla's moderator level
	 */
	static boolean withoutProvider(PermissionGroups groups, UUID id, String name,
			boolean moderator, String node) {

		if (groups != null) {
			// Operators bypassing is on by default so writing this file cannot lock an
			// admin out before they have added themselves to it.
			if (groups.operatorsBypass && moderator) return true;

			Boolean answer = groups.check(id, name, node);
			if (answer != null) return answer;

			// The file is in charge and has nothing to say about this player. With the
			// bypass switched off that has to mean no.
			//
			// It used to fall through to the line below, which reopened the operator path
			// that operatorsBypass:false exists to close — so turning the setting off left
			// every op holding every node, exactly as if it were still on, and the only
			// players it actually restricted were the ones who were not operators. A
			// security setting that silently applies to everyone except the people it was
			// written for is worse than not having it.
			if (!groups.operatorsBypass) return false;
		}
		return moderator;
	}

	public static boolean check(CommandSourceStack src, String node) {
		ServerPlayer p = src.getPlayer();
		if (p == null) return true; // console / command block always allowed
		return check(p, node);
	}

	/**
	 * A node every player holds unless a permissions plugin explicitly revokes it.
	 * <p>
	 * {@code report.use} is the only such node in StaffCore, and it must not follow the
	 * staff fallback: defaulting it to op level 2 would silently make {@code /report} an
	 * admin-only command on any server without a permissions plugin, which is the exact
	 * opposite of what it is for.
	 */
	public static boolean checkOpen(ServerPlayer player, String node) {
		if (player == null) return true;
		if (WITH_DEFAULT != null) {
			try {
				return (boolean) WITH_DEFAULT.invoke(null, player, node, true);
			} catch (ReflectiveOperationException ignored) {
				// fall through
			}
		}
		return true;
	}

	public static boolean checkOpen(CommandSourceStack src, String node) {
		return checkOpen(src.getPlayer(), node);
	}

	/**
	 * True when the source holds at least one of these nodes.
	 * <p>
	 * Used to decide whether the {@code /staff} root is offered at all. Gating the root on
	 * a single node would hide the entire command tree from, say, someone who only has
	 * rollback rights — Brigadier requires the parent to pass before a child is reachable.
	 */
	public static boolean checkAny(CommandSourceStack src, String... nodes) {
		for (String node : nodes) {
			if (check(src, node)) return true;
		}
		return false;
	}
}
