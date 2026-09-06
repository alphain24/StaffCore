package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.util.ItemDebit;
import io.github.alphain24.staffcore.util.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Map;

/**
 * Takes back what somebody owes, wherever they put it.
 * <p>
 * Putting items back where they belong is only half a repair. If the person who took them
 * still has a copy, the repair has printed items — and the more reliable the restore gets,
 * the more attractive stealing becomes, because the victim is made whole <em>and</em> the
 * thief keeps the loot. Every route below exists because it was a way to keep a copy:
 * <ol>
 *   <li><b>The ground.</b> Dropping the haul used to beat an inventory-only check outright.
 *       Swept at the scene and again around the offender, who may have walked.</li>
 *   <li><b>Their inventory.</b> The obvious one, armour and offhand included.</li>
 *   <li><b>Their ender chest.</b> Previously unreachable and therefore the safest place on
 *       the server to put stolen goods.</li>
 *   <li><b>Chests they filled.</b> Read out of the container log, so somebody else's chest
 *       is never touched.</li>
 *   <li><b>Their next login.</b> Whatever is still missing is booked as a debt rather than
 *       written off, so spending the loot only delays paying for it.</li>
 * </ol>
 * Both the block rollback and the container theft undo run through here, because a thief
 * does not care which screen staff used and the two must not disagree about what is owed.
 */
public final class LootRecovery {
	private LootRecovery() {}

	/** How much came back, and by which route. */
	public record Result(int fromGround, int fromInventory, int fromEnderChest,
			int fromChests, int fromStaff, int fromPickers, int queued) {

		public static final Result NOTHING = new Result(0, 0, 0, 0, 0, 0, 0);

		/** Everything actually taken back off them, wherever it was found. */
		public int recovered() {
			return fromGround + fromInventory + fromEnderChest + fromChests + fromStaff
					+ fromPickers;
		}
	}

	/**
	 * How many chunks a sweep will pull off disk before giving up and using what is loaded.
	 * <p>
	 * Two hundred and fifty-six covers a 128-block radius, which is past anything a sane
	 * rollback uses. Beyond that the stall would be worse than the miss, and the pickup log
	 * covers the miss anyway.
	 */
	private static final int MAX_CHUNKS_TO_LOAD = 256;

	/** How far around the offender to sweep for a haul they dropped after walking off. */
	private static final double OFFENDER_SWEEP = 16.0D;

	/**
	 * Collects {@code owed} from one player by every route available.
	 *
	 * @param owed     mutated in place; whatever remains afterwards is what nobody could find
	 * @param scene    where the items were put back, or null to skip the scene sweep
	 * @param radius   how far around {@code scene} to look
	 * @param snapshot whether to record the offender's inventory before touching it
	 */
	public static Result collect(ServerLevel level, String playerName, Map<Item, Integer> owed,
			BlockPos scene, int radius, long windowMs, ContainerWatch containers,
			String reason, boolean snapshot) {

		return collect(level, playerName, owed, scene, radius, windowMs, containers, reason,
				snapshot, java.util.Set.of());
	}

	/**
	 * As above, protecting containers this operation has just refilled.
	 *
	 * @param keepFilled positions the banked-loot sweep must not empty again
	 */
	public static Result collect(ServerLevel level, String playerName, Map<Item, Integer> owed,
			BlockPos scene, int radius, long windowMs, ContainerWatch containers,
			String reason, boolean snapshot, java.util.Set<BlockPos> keepFilled) {

		return collect(level, playerName, owed, scene, radius, windowMs, containers, reason,
				snapshot, keepFilled, null, null);
	}

	/**
	 * As above, recording what raised the debt.
	 *
	 * @param refKind and {@code refId} tie any leftover debt to its cause, so undoing that
	 *                cause can cancel it. A rollback that is undone must not leave somebody
	 *                owing for a repair that no longer exists.
	 */
	public static Result collect(ServerLevel level, String playerName, Map<Item, Integer> owed,
			BlockPos scene, int radius, long windowMs, ContainerWatch containers,
			String reason, boolean snapshot, java.util.Set<BlockPos> keepFilled,
			String refKind, Long refId) {

		return collect(level, playerName, owed, scene, radius, windowMs, containers, reason,
				snapshot, keepFilled, refKind, refId, null);
	}

	/**
	 * As above, also checking whoever is carrying out the operation.
	 *
	 * @param staffName the staff member running this, checked last and only for the shortfall
	 */
	public static Result collect(ServerLevel level, String playerName, Map<Item, Integer> owed,
			BlockPos scene, int radius, long windowMs, ContainerWatch containers,
			String reason, boolean snapshot, java.util.Set<BlockPos> keepFilled,
			String refKind, Long refId, String staffName) {

		if (owed == null || owed.isEmpty() || level == null) return Result.NOTHING;

		// The chunks have to be resident before their entities can be seen at all.
		if (scene != null) Mc.ensureLoaded(level, scene, radius, MAX_CHUNKS_TO_LOAD);

		int fromGround = scene == null ? 0 : sweepGround(level, owed,
				new AABB(scene.getX() - radius, level.getMinY(), scene.getZ() - radius,
						scene.getX() + radius, level.getMaxY(), scene.getZ() + radius));

		// A creeper has no inventory, no ender chest and no next login, so every route that
		// charges somebody is meaningless here. Two are not: the ground still has to be swept,
		// because an explosion scatters drops a restore would otherwise duplicate, and the
		// pickup log still has to be asked, because a person may well have pocketed what the
		// creeper scattered. Those items have an owner even when the damage does not.
		if (playerName == null || !GriefModule.isPlayerSource(playerName)) {
			int staffOnly = sweepStaff(level, staffName, owed);
			int pickedUp = sweepPickers(level, owed, scene, radius, windowMs, null, reason,
					refKind, refId);
			return new Result(fromGround, 0, 0, 0, staffOnly, pickedUp, 0);
		}

		ServerPlayer offender = level.getServer().getPlayerList().getPlayerByName(playerName);

		int fromInventory = 0;
		int fromEnderChest = 0;
		if (offender != null) {
			// Taking items out of somebody's inventory on the strength of a log query is
			// exactly the kind of action that should leave a before-picture, whether or not
			// the query turns out to have been right.
			if (snapshot && StaffConfig.get().autoSnapshotBeforeDebit) {
				Mods.inventory().capture(offender, "Before rollback debit", "system",
						io.github.alphain24.staffcore.modules.inventory.InventoryModule.Kind.ROUTINE);
			}

			// Around them as well as around the scene. Walking away with the haul and
			// dropping it was otherwise a complete defence against the sweep above.
			fromGround += sweepGround(level, owed,
					offender.getBoundingBox().inflate(OFFENDER_SWEEP));

			fromInventory = ItemDebit.debit(offender, owed);
			fromEnderChest = debitContainer(offender.getEnderChestInventory(), owed);
		}

		int fromChests = 0;
		if (StaffConfig.get().rollbackChasesBankedLoot && containers != null) {
			fromChests = containers.reclaimBanked(level, playerName, windowMs, owed, keepFilled);
		}

		// Anything still missing is owed, whether they are standing here or not. An online
		// offender who already spent the loot used to get away with it outright: the debit
		// found nothing, the chest was refilled anyway, and the difference was printed.
		// Last, and only for what is still missing. Anything the offender could pay has
		// already been taken off them, so what reaches here is a genuine shortfall.
		int fromStaff = sweepStaff(level, staffName, owed);

		// Whoever else pocketed something at this scene. This is the route that works at any
		// distance and through an unloaded chunk, because it asks the log rather than the
		// world — and the only one that can reach items a third party walked off with.
		int fromPickers = sweepPickers(level, owed, scene, radius, windowMs, playerName, reason,
				refKind, refId);

		int queued = queue(level, playerName, owed, reason, refKind, refId);

		return new Result(fromGround, fromInventory, fromEnderChest, fromChests, fromStaff,
				fromPickers, queued);
	}

	/**
	 * Takes back anything the staff member running this picked up at the scene.
	 * <p>
	 * Arriving at a grief site and hoovering up the floor is a reflex, and it quietly breaks
	 * the arithmetic: those drops came out of the container being restored, so once it is
	 * refilled they exist twice. The ground sweep cannot find them — they are in a pocket, not
	 * on the floor — and the offender cannot be charged for them either, because they no
	 * longer have them. The shortfall was being written off as a debt against the wrong
	 * person while the items sat in staff inventory.
	 * <p>
	 * Only the shortfall, only the item types being restored, and only from the person who
	 * chose to run the rollback — never a bystander. A snapshot is taken first, so staff who
	 * lose something they were legitimately carrying can get it straight back.
	 */
	private static int sweepStaff(ServerLevel level, String staffName, Map<Item, Integer> owed) {
		if (staffName == null || !StaffConfig.get().rollbackReclaimsFromStaff) return 0;
		if (owed.values().stream().noneMatch(due -> due != null && due > 0)) return 0;

		ServerPlayer staff = level.getServer().getPlayerList().getPlayerByName(staffName);
		if (staff == null) return 0;

		if (StaffConfig.get().autoSnapshotBeforeDebit) {
			Mods.inventory().capture(staff, "Before rollback reclaim", "system",
					io.github.alphain24.staffcore.modules.inventory.InventoryModule.Kind.ROUTINE);
		}

		int taken = ItemDebit.debit(staff, owed);
		taken += debitContainer(staff.getEnderChestInventory(), owed);

		if (taken > 0) {
			staff.sendSystemMessage(io.github.alphain24.staffcore.gui.Theme.info(
					taken + " item(s) you had picked up went back into the rollback. "
							+ "A snapshot was taken first if you need them returned."));
		}
		return taken;
	}

	/**
	 * Reclaims owed items from whoever picked them up, with no offender involved.
	 * <p>
	 * Used when restoring a death snapshot. There is no griefer to charge — the items belong
	 * to the person being restored — so the only question is who else walked off with them.
	 *
	 * @param owner the player the items belong to, excluded from the sweep
	 * @return how many items were taken back
	 */
	public static int reclaimFromPickers(ServerLevel level, Map<Item, Integer> owed,
			BlockPos scene, int radius, long windowMs, String owner, String reason) {

		return sweepPickers(level, owed, scene, radius, windowMs, owner, reason, null, null);
	}

	/**
	 * Takes back what other players picked up at the scene.
	 * <p>
	 * The gap this closes: somebody dies, a passer-by pockets half the pile, staff restore the
	 * inventory, and the passer-by keeps their half. Nothing in a ground scan can find that —
	 * the items are in a pocket — and the dead player is not the one to charge for it.
	 * <p>
	 * It is also the only route that survives distance. Every other sweep here reads the
	 * world, and the world only answers for chunks that happen to be loaded; this reads the
	 * log, so a scene ten thousand blocks away with nobody near it works exactly as well as
	 * one underfoot.
	 * <p>
	 * The owner is excluded by name. Picking your own death drops back up is not theft, and
	 * billing somebody for recovering their own belongings would be a strange way to help.
	 *
	 * @param owner the player the items belong to, left alone; null when there is no owner
	 */
	private static int sweepPickers(ServerLevel level, Map<Item, Integer> owed, BlockPos scene,
			int radius, long windowMs, String owner, String reason, String refKind, Long refId) {

		if (scene == null || !StaffConfig.get().logItemPickups) return 0;
		if (owed.values().stream().noneMatch(due -> due != null && due > 0)) return 0;

		PickupWatch pickups = Mods.grief().pickups();
		String world = Mc.dimensionId(level);

		// A wider window than the rollback's own: somebody can loot a scene minutes after the
		// break that created it, and the pickup is what matters rather than when the damage
		// was done.
		long lookback = Math.max(windowMs, 30 * 60_000L);
		var byPlayer = pickups.whoTook(world, scene, radius, lookback, owner, owed);
		if (byPlayer.isEmpty()) return 0;

		int taken = 0;
		for (var entry : byPlayer.entrySet()) {
			String name = entry.getKey();
			Map<Item, Integer> theirs = entry.getValue();

			ServerPlayer picker = level.getServer().getPlayerList().getPlayerByName(name);
			if (picker != null) {
				if (StaffConfig.get().autoSnapshotBeforeDebit) {
					Mods.inventory().capture(picker, "Before rollback reclaim", "system",
							io.github.alphain24.staffcore.modules.inventory.InventoryModule.Kind.ROUTINE);
				}

				int got = ItemDebit.debit(picker, theirs);
				got += debitContainer(picker.getEnderChestInventory(), theirs);
				taken += got;

				if (got > 0) {
					picker.sendSystemMessage(io.github.alphain24.staffcore.gui.Theme.info(
							got + " item(s) you picked up were returned to their owner."));
				}
			}

			// Whatever they no longer have is still theirs to settle, online or not.
			int stillOwed = theirs.values().stream().mapToInt(v -> v == null ? 0 : v).sum();
			if (stillOwed > 0) {
				queue(level, name, theirs, reason, refKind, refId);
			}

			// The debt has moved from the offender to the person holding the goods, so it
			// must come off the original bill or it would be collected twice.
			for (var due : entry.getValue().entrySet()) {
				owed.computeIfPresent(due.getKey(), (item, left) -> Math.max(0, left - due.getValue()));
			}
		}

		// Settled, so a second rollback over the same ground cannot charge for it again.
		pickups.retire(world, scene, radius, lookback, owner);
		return taken;
	}

	/** Removes owed items from item entities inside an area. */
	private static int sweepGround(ServerLevel level, Map<Item, Integer> owed, AABB area) {
		int taken = 0;
		for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) {
			Integer due = owed.get(drop.getItem().getItem());
			if (due == null || due <= 0) continue;

			int take = Math.min(due, drop.getItem().getCount());
			owed.put(drop.getItem().getItem(), due - take);
			taken += take;

			if (take >= drop.getItem().getCount()) {
				drop.remove(Entity.RemovalReason.DISCARDED);
			} else {
				drop.getItem().shrink(take);
			}
		}
		return taken;
	}

	/** Removes owed items from any container — used for the ender chest. */
	private static int debitContainer(Container container, Map<Item, Integer> owed) {
		if (container == null) return 0;

		int taken = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;

			Integer due = owed.get(stack.getItem());
			if (due == null || due <= 0) continue;

			int take = Math.min(due, stack.getCount());
			stack.shrink(take);
			owed.put(stack.getItem(), due - take);
			taken += take;
		}
		if (taken > 0) container.setChanged();
		return taken;
	}

	/** Books whatever is left against the offender's next login. */
	private static int queue(ServerLevel level, String playerName, Map<Item, Integer> owed,
			String reason, String refKind, Long refId) {

		if (owed.values().stream().noneMatch(due -> due != null && due > 0)) return 0;

		var profile = PlayerLookup.profile(level.getServer(), playerName);
		if (profile.isEmpty()) {
			StaffCore.LOGGER.warn("[Grief] {} still owes items but has no known profile", playerName);
			return 0;
		}
		return StaffCore.pending().queueDebit(profile.get().id(), profile.get().name(), owed,
				reason, "system", refKind, refId);
	}
}
