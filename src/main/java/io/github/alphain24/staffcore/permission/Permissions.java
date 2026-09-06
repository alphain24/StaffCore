package io.github.alphain24.staffcore.permission;

import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

/**
 * Thin wrapper over a permissions provider.
 * <p>
 * If <a href="https://github.com/lucko/fabric-permissions-api">fabric-permissions-api</a>
 * is on the classpath it is used reflectively, so StaffCore picks up LuckPerms the moment
 * you install it. Both lookups happen once at class-load; a miss costs nothing at runtime.
 * <p>
 * Without one, {@link PermissionGroups} answers instead, and only then does it fall through
 * to the vanilla operator level. That middle layer is the difference between a server
 * without LuckPerms having a staff hierarchy and having one rank called "op".
 */
public final class Permissions {
	private Permissions() {}

	private static final String API = "me.lucko.fabric.api.permissions.v0.Permissions";

	/** {@code check(Entity, String)} — undefined nodes come back false. */
	private static final Method STRICT = findMethod(Entity.class, String.class);
	/** {@code check(Entity, String, boolean)} — undefined nodes fall back to the default. */
	private static final Method WITH_DEFAULT = findMethod(Entity.class, String.class, boolean.class);

	private static Method findMethod(Class<?>... signature) {
		try {
			return Class.forName(API).getMethod("check", signature);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	/** True when fabric-permissions-api is on the classpath and answering. */
	public static boolean hasProvider() {
		return STRICT != null;
	}

	/**
	 * A staff node, resolved through whichever authority is available.
	 * <p>
	 * A permissions API wins outright where one is installed. Failing that, the built-in
	 * groups in {@code staffcore-permissions.json} get a say — without them every node fell
	 * back to the vanilla moderator level, which made every staff member equally powerful
	 * and left no way to say that a trainee should not be able to revoke bans. Op level is
	 * the last resort rather than the only one.
	 */
	public static boolean check(ServerPlayer player, String node) {
		if (player == null) return true; // console
		if (STRICT != null) {
			try {
				return (boolean) STRICT.invoke(null, player, node);
			} catch (ReflectiveOperationException ignored) {
				// fall through to the built-in groups, then the vanilla permission set
			}
		}

		PermissionGroups groups = PermissionGroups.get();
		if (groups != null) {
			// Operators bypassing is on by default so writing this file cannot lock an
			// admin out before they have added themselves to it.
			if (groups.operatorsBypass && Mc.isModerator(player)) return true;

			Boolean answer = groups.check(player.getUUID(), Mc.name(player), node);
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
		return Mc.isModerator(player);
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
