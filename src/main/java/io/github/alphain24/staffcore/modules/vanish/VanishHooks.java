package io.github.alphain24.staffcore.modules.vanish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The single entry point every vanish mixin calls.
 * <p>
 * Vanish leaks through a couple of dozen unrelated systems — sound, sleep counting, mob
 * targeting, the server list, pressure plates — and each of those needs its own injection.
 * Routing them all through one class keeps the mixins to two lines each, and means the
 * "is this player hidden" question is answered identically everywhere rather than
 * re-derived slightly differently in twenty places.
 * <p>
 * Every method here is a cheap lookup guarded against the module being absent, because
 * these are called from hot paths — sound playback, entity collision, spawn checks — and
 * from mixins that must not explode if StaffCore failed to load.
 */
public final class VanishHooks {
	private VanishHooks() {}

	private static VanishModule module() {
		return StaffCore.modules().get("vanish", VanishModule.class).orElse(null);
	}

	/** The shared state, or null when the module is not loaded. */
	private static VanishState state() {
		VanishModule vanish = module();
		return vanish == null ? null : vanish.state();
	}

	/**
	 * True when nobody is hidden at all.
	 * <p>
	 * Worth having as its own question: most servers, most of the time, have no vanished
	 * players, and this lets a caller skip building a filtered copy of a list for nothing.
	 */
	public static boolean noneHidden() {
		VanishState state = state();
		return state == null || state.isEmpty();
	}

	/** True when this entity is a vanished player. Safe to call for any entity. */
	public static boolean isVanished(Entity entity) {
		if (!(entity instanceof ServerPlayer player)) return false;
		VanishState state = state();
		return state != null && state.isHidden(player);
	}

	/**
	 * The one question the entity tracker asks: should this viewer be told {@code hidden}
	 * exists at all?
	 * <p>
	 * Kept deliberately cheap — it runs for every tracked pair, every tick — and ordered so
	 * the common case (nobody is vanished) costs one map check.
	 */
	public static boolean hiddenFrom(ServerPlayer hidden, ServerPlayer viewer) {
		VanishState state = state();
		if (state == null || state.isEmpty()) return false;
		return state.isHidden(hidden) && !canSeeVanished(viewer);
	}

	/** Whether this viewer is cleared to see through vanish. */
	public static boolean canSeeVanished(ServerPlayer viewer) {
		VanishModule vanish = module();
		return vanish != null && vanish.canSeeVanished(viewer);
	}

	/** True when vanished players should be left out of counts, lists and selectors. */
	public static boolean hiddenFromLists(Entity entity) {
		return StaffConfig.get().vanishHidesFromLists && isVanished(entity);
	}

	/** True when this player's actions should make no sound. */
	public static boolean silent(Entity entity) {
		return StaffConfig.get().vanishSilent && isVanished(entity);
	}

	/** True when the world should behave as though this player is not physically there. */
	public static boolean intangible(Entity entity) {
		return StaffConfig.get().vanishIntangible && isVanished(entity);
	}

	/** True when mobs and spawners should not notice this player at all. */
	public static boolean ignoredByMobs(Entity entity) {
		return StaffConfig.get().vanishIgnoredByMobs && isVanished(entity);
	}

	/**
	 * True when this player should not hold chunks open around them.
	 * <p>
	 * Off by default, and deliberately separate from {@link #ignoredByMobs}. Refusing the
	 * spawn counter costs a vanished admin nothing — farms simply behave as though nobody
	 * is there. Refusing the loading ticket costs them the world: chunks nobody else is
	 * holding open will not load, and flying through your own server becomes flying through
	 * void. That is a real trade some servers want and most do not, so it is a decision
	 * rather than a default.
	 */
	public static boolean blocksChunkLoading(Entity entity) {
		return !StaffConfig.get().vanishLoadsChunks && isVanished(entity);
	}
}
