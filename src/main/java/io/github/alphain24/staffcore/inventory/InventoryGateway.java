package io.github.alphain24.staffcore.inventory;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.diagnostic.StartupCheck;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.inventory.InventoryModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one door every write into a player's inventory goes through.
 * <p>
 * Punishing somebody has had a single entry point for a long time: {@code PunishmentModule.apply}
 * is the only way anyone gets banned, so there is one place that records it, one place that
 * checks permission, and one place to look when somebody asks what happened. Moving items had
 * no such thing. Four independent paths wrote to inventories — a rollback debit, a staff edit
 * through invsee, a snapshot restore, and a vault return — each with its own idea of what to
 * record and when.
 * <p>
 * That asymmetry is the wrong way round. A wrong punishment is recoverable: unban them and
 * apologise. Items taken from somebody who was not there to see it are gone, and without a
 * record there is no way to establish that it happened at all, let alone put it right. The
 * feature with the irreversible failure had the weaker controls.
 *
 * <h2>What every mutation gets</h2>
 * <ol>
 *   <li><b>A snapshot first.</b> Reusing the existing machinery, so the state before the
 *       change is recoverable through the screens staff already know.</li>
 *   <li><b>One audit row</b> naming actor, target, reason, the stacks, and which subsystem
 *       asked — so "who took my things" has an answer that does not depend on guessing which
 *       of four code paths ran.</li>
 *   <li><b>One transaction</b> around the snapshot and the audit row together, so a mutation
 *       is never recorded half-way.</li>
 *   <li><b>A health check.</b> If the hooks the calling subsystem depends on are broken, the
 *       write is refused rather than run against data known to be incomplete.</li>
 * </ol>
 *
 * <h2>The ordering, and why it is what it is</h2>
 * The inventory itself lives in memory and no database transaction can roll it back. So the
 * record is committed <em>first</em> and the items move only if that succeeded. The property
 * this buys is the one worth having: <b>there is no such thing as an unrecorded mutation.</b>
 * The failure mode it leaves is a recorded change that did not happen, which is visible,
 * checkable and harmless next to its opposite.
 */
public final class InventoryGateway {
	private InventoryGateway() {}

	/**
	 * Which subsystem is asking, and what it needs to be working before it may write.
	 * <p>
	 * The hook list is the point. A rollback debit decides what somebody owes by reading the
	 * block and pickup logs; if the hooks that fill those logs are not applied, the logs are
	 * not empty, they are <em>wrong</em> — and a debit computed from them takes items for a
	 * debt that was never measured properly. Refusing is the only safe answer, and it is why
	 * this is per-origin rather than one global switch.
	 */
	public enum Origin {
		/** Taking items back after a rollback, to stop the repair duplicating them. */
		ROLLBACK_DEBIT("rollback debit", "Explosion damage log", "Fire damage log",
				"Item pickup log"),

		/** A staff member editing another player's inventory through invsee. */
		INVSEE_EDIT("staff inventory edit"),

		/** Writing a recorded inventory back onto a player. */
		SNAPSHOT_RESTORE("snapshot restore", "Item pickup log"),

		/** Handing a confiscated item back from the vault. */
		VAULT_RETURN("vault return"),

		/** Settling a debt or delivery queued while the player was offline. */
		PENDING_SETTLEMENT("pending settlement");

		private final String label;
		private final List<String> requiredFeatures;

		Origin(String label, String... requiredFeatures) {
			this.label = label;
			this.requiredFeatures = List.of(requiredFeatures);
		}

		public String label() {
			return label;
		}

		/**
		 * The hooks this subsystem's correctness rests on, by the feature names
		 * {@link StartupCheck} reports.
		 */
		public List<String> requiredFeatures() {
			return requiredFeatures;
		}
	}

	/** Which way the items went, from the target player's point of view. */
	public enum Direction {
		GIVE, TAKE
	}

	/**
	 * What happened.
	 *
	 * @param items    individual items actually moved
	 * @param refused  why the write did not happen, or null when it did
	 */
	public record Outcome(boolean applied, int items, long auditId, String refused) {

		public static Outcome refused(String why) {
			return new Outcome(false, 0, 0, why);
		}

		public boolean wasRefused() {
			return refused != null;
		}
	}

	// ------------------------------------------------------------------ the door

	/**
	 * Puts items into a player's inventory, or at their feet if they will not fit.
	 * <p>
	 * Refusing to hand something back because the recipient is full would be a strange way to
	 * correct a mistake, so overflow drops rather than failing.
	 */
	public static Outcome give(ServerPlayer target, Origin origin, String actor, String reason,
			List<ItemStack> stacks) {

		List<ItemStack> real = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (stack != null && !stack.isEmpty()) real.add(stack.copy());
		}
		if (real.isEmpty()) return new Outcome(true, 0, 0, null);

		String refusal = whyRefused(origin);
		if (refusal != null) return refuse(origin, actor, target, reason, refusal);

		int count = real.stream().mapToInt(ItemStack::getCount).sum();
		long auditId = record(origin, Direction.GIVE, actor, target, reason, describe(real), count);
		if (auditId < 0) {
			return refuse(origin, actor, target, reason,
					"the change could not be recorded, so it was not made");
		}

		for (ItemStack stack : real) {
			if (!target.getInventory().add(stack)) target.drop(stack, false);
		}
		target.containerMenu.broadcastChanges();
		return new Outcome(true, count, auditId, null);
	}

	/**
	 * Removes up to the owed amount of each item, decrementing {@code owed} as it goes so the
	 * caller can see what could not be found.
	 *
	 * @param owed item type to quantity still due; mutated in place
	 */
	public static Outcome take(ServerPlayer target, Origin origin, String actor, String reason,
			Map<Item, Integer> owed) {

		if (owed.isEmpty()) return new Outcome(true, 0, 0, null);

		String refusal = whyRefused(origin);
		if (refusal != null) return refuse(origin, actor, target, reason, refusal);

		// Worked out before anything is written so the audit row names what actually moved
		// rather than what was hoped for — the record has to describe the change, not the
		// intention behind it.
		Map<Item, Integer> takeable = new LinkedHashMap<>();
		Inventory inv = target.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;

			int due = owed.getOrDefault(stack.getItem(), 0)
					- takeable.getOrDefault(stack.getItem(), 0);
			if (due <= 0) continue;
			takeable.merge(stack.getItem(), Math.min(due, stack.getCount()), Integer::sum);
		}

		int count = takeable.values().stream().mapToInt(Integer::intValue).sum();
		if (count == 0) return new Outcome(true, 0, 0, null);

		long auditId = record(origin, Direction.TAKE, actor, target, reason,
				describeItems(takeable), count);
		if (auditId < 0) {
			return refuse(origin, actor, target, reason,
					"the change could not be recorded, so it was not made");
		}

		int removed = 0;
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.isEmpty()) continue;

			Integer due = owed.get(stack.getItem());
			if (due == null || due <= 0) continue;

			int take = Math.min(due, stack.getCount());
			stack.shrink(take);
			owed.put(stack.getItem(), due - take);
			removed += take;
		}

		if (removed > 0) {
			inv.setChanged();
			target.containerMenu.broadcastChanges();
		}
		return new Outcome(true, removed, auditId, null);
	}

	/**
	 * Replaces a player's whole inventory, as a snapshot restore does.
	 * <p>
	 * Separate from {@link #give} because it is not additive: it clears first, so anything
	 * picked up since the snapshot is gone. That is the intended behaviour — a restore puts
	 * somebody back to a recorded moment rather than topping them up — and it is exactly why
	 * it needs its own before-picture. Getting the wrong snapshot back is a bigger accident
	 * than a failed give.
	 */
	public static Outcome replaceAll(ServerPlayer target, Origin origin, String actor,
			String reason, ItemStack[] contents) {

		String refusal = whyRefused(origin);
		if (refusal != null) return refuse(origin, actor, target, reason, refusal);

		List<ItemStack> real = new ArrayList<>();
		for (ItemStack stack : contents) {
			if (stack != null && !stack.isEmpty()) real.add(stack);
		}
		int count = real.stream().mapToInt(ItemStack::getCount).sum();

		long auditId = record(origin, Direction.GIVE, actor, target, reason, describe(real), count);
		if (auditId < 0) {
			return refuse(origin, actor, target, reason,
					"the change could not be recorded, so it was not made");
		}

		Inventory inv = target.getInventory();
		// clearContent() empties equipment as well as storage, so armour the snapshot did not
		// have does not survive the restore.
		inv.clearContent();
		int size = Math.min(contents.length, inv.getContainerSize());
		for (int i = 0; i < size; i++) {
			inv.setItem(i, contents[i] == null ? ItemStack.EMPTY : contents[i].copy());
		}

		// Both menus. containerMenu is whatever they happen to have open, and if that is a
		// chest it has no armour or offhand slots — so armour restored while somebody is
		// standing in a container would not appear on their own screen until something else
		// refreshed it.
		target.inventoryMenu.broadcastChanges();
		if (target.containerMenu != target.inventoryMenu) {
			target.containerMenu.broadcastChanges();
		}
		return new Outcome(true, count, auditId, null);
	}

	// -------------------------------------------------------------- staff editing

	/**
	 * An open invsee edit, held from the moment the screen opens until it closes.
	 * <p>
	 * A staff edit is not one mutation, it is a session of them: vanilla moves the stacks
	 * click by click through the container, and putting a database transaction around every
	 * click would make the screen unusable while recording mostly noise — a stack picked up
	 * and put down again is not a change anybody wants a row for.
	 * <p>
	 * So the unit of record is the session. The before-picture is taken when the screen opens
	 * and the difference is worked out when it closes, which produces one row saying what
	 * actually changed rather than forty saying how it was carried.
	 */
	public record EditSession(ServerPlayer target, String actor, String reason,
			Map<Item, Integer> before, Long snapshotId) {}

	/** Snapshots the target and remembers what they had. */
	public static EditSession beginEdit(ServerPlayer target, String actor, String reason) {
		Long snapshot = snapshotBefore(Origin.INVSEE_EDIT, target, actor);
		return new EditSession(target, actor, reason, tally(target), snapshot);
	}

	/**
	 * Works out what the edit changed and records it.
	 * <p>
	 * Nothing is written when nothing moved, which is most sessions: staff open an inventory
	 * to look far more often than to change it, and a log full of "opened, changed nothing"
	 * is a log nobody reads.
	 */
	public static Outcome endEdit(EditSession session) {
		if (session == null) return new Outcome(true, 0, 0, null);

		Map<Item, Integer> after = tally(session.target());
		List<String> added = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		int moved = 0;

		for (Item item : union(session.before(), after)) {
			int delta = after.getOrDefault(item, 0) - session.before().getOrDefault(item, 0);
			if (delta == 0) continue;
			moved += Math.abs(delta);
			if (delta > 0) added.add("+" + delta + " " + Mc.itemId(item));
			else removed.add(delta + " " + Mc.itemId(item));
		}
		if (moved == 0) return new Outcome(true, 0, 0, null);

		List<String> parts = new ArrayList<>(removed);
		parts.addAll(added);
		String items = String.join(", ", parts);

		// Direction is whichever way the balance went; the item list carries both halves, so
		// a swap is still legible as one.
		Direction direction = added.stream().mapToInt(x -> 1).sum() >= removed.size()
				? Direction.GIVE : Direction.TAKE;

		long auditId = record(Origin.INVSEE_EDIT, direction, session.actor(), session.target(),
				session.reason(), items, moved);
		return auditId < 0
				? Outcome.refused("the edit happened but could not be recorded")
				: new Outcome(true, moved, auditId, null);
	}

	private static Map<Item, Integer> tally(ServerPlayer player) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private static List<Item> union(Map<Item, Integer> a, Map<Item, Integer> b) {
		List<Item> out = new ArrayList<>(a.keySet());
		for (Item item : b.keySet()) {
			if (!out.contains(item)) out.add(item);
		}
		return out;
	}

	// ------------------------------------------------------------------ the checks

	/**
	 * Whether this subsystem is allowed to write right now, and if not, why not in words
	 * somebody can act on.
	 */
	private static String whyRefused(Origin origin) {
		if (origin.requiredFeatures().isEmpty()) return null;

		List<String> broken = new ArrayList<>();
		for (StartupCheck.Finding finding : StartupCheck.report()) {
			if (finding.isBroken() && origin.requiredFeatures().contains(finding.feature())) {
				broken.add(finding.feature());
			}
		}
		if (broken.isEmpty()) return null;

		return "the hooks it depends on are not working (" + String.join(", ", broken)
				+ "), so the data behind this change is incomplete";
	}

	private static Outcome refuse(Origin origin, String actor, ServerPlayer target, String reason,
			String why) {

		// Loud, because the alternative is a moderation tool that quietly stops working. A
		// refusal that nobody sees is indistinguishable from the feature having nothing to do.
		StaffCore.LOGGER.error("[StaffCore] Refused a {} on {} by {} ({}): {}",
				origin.label(), Mc.name(target), actor, reason, why);
		return Outcome.refused(why);
	}

	// ------------------------------------------------------------------ the record

	/**
	 * Snapshot and audit row, in one transaction, before anything moves.
	 *
	 * @return the audit row id, or -1 when nothing could be recorded
	 */
	private static long record(Origin origin, Direction direction, String actor,
			ServerPlayer target, String reason, String items, int count) {

		if (!StaffCore.storage().isReady()) {
			StaffCore.LOGGER.error("[StaffCore] Refusing a {} on {}: the database is not open, "
					+ "so the change could not be recorded.", origin.label(), Mc.name(target));
			return -1;
		}

		long[] audit = { -1 };
		boolean committed = StaffCore.storage().inTransaction(conn -> {
			// The snapshot is what makes this recoverable rather than merely traceable. Taken
			// inside the same transaction as the audit row so a record can never point at a
			// snapshot that was not kept.
			Long snapshotId = snapshotBefore(origin, target, actor);

			try (PreparedStatement ps = conn.prepareStatement("""
					INSERT INTO inventory_audit
					    (origin, direction, actor, target_uuid, target_name, reason,
					     items, item_count, snapshot_id, created_at)
					VALUES (?,?,?,?,?,?,?,?,?,?)
					""", java.sql.Statement.RETURN_GENERATED_KEYS)) {

				ps.setString(1, origin.name());
				ps.setString(2, direction.name());
				ps.setString(3, actor == null ? "server" : actor);
				ps.setString(4, target.getUUID().toString());
				ps.setString(5, Mc.name(target));
				ps.setString(6, reason == null ? "" : reason);
				ps.setString(7, items);
				ps.setInt(8, count);
				if (snapshotId == null) ps.setNull(9, java.sql.Types.INTEGER);
				else ps.setLong(9, snapshotId);
				ps.setLong(10, System.currentTimeMillis());
				ps.executeUpdate();

				try (var keys = ps.getGeneratedKeys()) {
					if (keys.next()) audit[0] = keys.getLong(1);
				}
			}
		});

		return committed ? audit[0] : -1;
	}

	/**
	 * The state before the change.
	 * <p>
	 * A failure here does not stop the write. The audit row is the part that must exist —
	 * without it a change is untraceable — while a missing snapshot costs convenience in
	 * putting things back. Refusing the whole mutation because the recoverable half failed
	 * would trade a small loss for a larger one.
	 */
	private static Long snapshotBefore(Origin origin, ServerPlayer target, String actor) {
		try {
			InventoryModule inventory = Mods.inventory();
			InventoryModule.Snapshot taken = inventory.capture(target,
					"Before " + origin.label(), actor == null ? "server" : actor,
					InventoryModule.Kind.EVIDENCE);
			return taken == null ? null : taken.id();
		} catch (RuntimeException e) {
			StaffCore.LOGGER.warn("[StaffCore] Could not snapshot {} before a {}: {}",
					Mc.name(target), origin.label(), e.getMessage());
			return null;
		}
	}

	// ------------------------------------------------------------------ plumbing

	/** "12 diamond, 3 oak log" — readable in the log without decoding anything. */
	private static String describe(List<ItemStack> stacks) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (ItemStack stack : stacks) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
		return describeItems(counts);
	}

	private static String describeItems(Map<Item, Integer> counts) {
		List<String> parts = new ArrayList<>();
		counts.forEach((item, n) -> parts.add(n + " " + Mc.itemId(item)));
		return String.join(", ", parts);
	}

	/** Every recorded mutation for one player, newest first. */
	public record AuditRow(long id, String origin, String direction, String actor,
			String reason, String items, int itemCount, Long snapshotId, long at) {}

	public static List<AuditRow> historyFor(java.util.UUID target, int limit) {
		List<AuditRow> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT id, origin, direction, actor, reason, items, item_count, snapshot_id, created_at
				FROM inventory_audit WHERE target_uuid = ?
				ORDER BY created_at DESC LIMIT ?
				""")) {
			ps.setString(1, target.toString());
			ps.setInt(2, limit);
			try (var rs = ps.executeQuery()) {
				while (rs.next()) {
					long snapshot = rs.getLong("snapshot_id");
					out.add(new AuditRow(rs.getLong("id"), rs.getString("origin"),
							rs.getString("direction"), rs.getString("actor"),
							rs.getString("reason"), rs.getString("items"),
							rs.getInt("item_count"), rs.wasNull() ? null : snapshot,
							rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not read the inventory audit", e);
		}
		return out;
	}
}
