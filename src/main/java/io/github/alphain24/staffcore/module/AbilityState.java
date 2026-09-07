package io.github.alphain24.staffcore.module;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.staffmode.StaffModeModule;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

/**
 * Decides what a player's flight and invulnerability should be, from everything that has a
 * claim on them.
 * <p>
 * Two features write these fields — staff mode and vanish — and for a while each tried to put
 * back "what was there before" when it finished. That cannot work when they overlap, and they
 * overlap by design: clocking on vanishes you. Staff mode set {@code invulnerable}, vanish then
 * recorded true as the value to restore, and clocking off handed it straight back after staff
 * mode had correctly cleared it. The player walked away permanently untouchable.
 * <p>
 * That was not a cosmetic leak. {@code Player.canBeSeenAsEnemy} is
 * {@code !getAbilities().invulnerable && super...}, so an invulnerable player is not merely
 * unhurt — no mob will acquire them as a target at all. The world simply stops noticing you.
 * It also hid itself well: rejoining fixed it, because vanilla's own
 * {@code GameType.updatePlayerAbilities} rebuilds these on login, so the only way to see it was
 * to clock off and stay connected.
 * <p>
 * <b>The rule here is that nobody restores anything.</b> Each feature changes the state that
 * says who it is, and then asks this class to work the abilities out again from scratch. There
 * is no saved copy to go stale, and no ordering to get wrong: two overlapping claims resolve
 * the same way whichever is released first, and releasing one twice changes nothing.
 * <p>
 * Every caller updates its own state <em>before</em> calling this. That is the one thing to
 * preserve when adding a claim.
 */
public final class AbilityState {
	private AbilityState() {}

	/**
	 * What the abilities should be, given who is currently claiming them.
	 *
	 * @param mayfly       may leave the ground
	 * @param invulnerable takes no damage — and, for a player, is invisible to mob targeting
	 */
	public record Resolved(boolean mayfly, boolean invulnerable) {}

	/**
	 * The decision, with no server attached, so it can be reasoned about and tested.
	 * <p>
	 * Creative and spectator are here because they are vanilla's own answer — see
	 * {@code GameType.updatePlayerAbilities} — and leaving them out is what made the first
	 * version of this wrong in the other direction: rebuilding from {@code isCreative()} alone
	 * took flight off a staff member the moment they un-vanished while still on duty.
	 */
	public static Resolved resolve(GameType mode, boolean onDuty, boolean hidden,
			boolean dutyIsInvulnerable) {

		boolean vanillaGrants = mode == GameType.CREATIVE || mode == GameType.SPECTATOR;

		return new Resolved(
				vanillaGrants || onDuty || hidden,
				vanillaGrants || hidden || (onDuty && dutyIsInvulnerable));
	}

	/** Works out this player's abilities from live state and applies them. */
	public static void reapply(ServerPlayer player) {
		boolean duty = onDuty(player);
		boolean dutyInvulnerable = StaffConfig.get().staffModeInvulnerable;
		Resolved want = resolve(player.gameMode(), duty, hidden(player), dutyInvulnerable);

		Abilities abilities = player.getAbilities();
		abilities.mayfly = want.mayfly();
		abilities.invulnerable = want.invulnerable();

		// Losing permission to fly while airborne leaves the client stuck flying until it is
		// told otherwise, so the two are cleared together.
		if (!want.mayfly()) abilities.flying = false;

		// The entity-level flag is separate from the ability and only staff mode ever wanted
		// it: it is what keeps somebody on duty out of an incident they are only inspecting.
		// Vanish does not set it, so a vanished player who is off duty is left alone.
		player.setInvulnerable(duty && dutyInvulnerable);

		player.onUpdateAbilities();
	}

	// -------------------------------------------------------------------- the claims

	/**
	 * Looked up rather than injected, and guarded, because either module can be switched off
	 * in the config — and because a failure to resolve one must not take flight away from a
	 * player the other is still holding.
	 */
	private static boolean onDuty(ServerPlayer player) {
		return StaffCore.modules().get("staff_mode", StaffModeModule.class)
				.map(mode -> mode.isActive(player))
				.orElse(false);
	}

	private static boolean hidden(ServerPlayer player) {
		return StaffCore.modules().get("vanish", VanishModule.class)
				.map(vanish -> vanish.isVanished(player))
				.orElse(false);
	}
}
