package dev.lebron.staffcore.modules.inventory;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.module.Module;
import dev.lebron.staffcore.util.ItemCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Backs the live {@code /invsee} view and keeps a rolling set of inventory snapshots.
 * <p>
 * Snapshots exist for the argument that always follows an inventory wipe: "I had it
 * before you teleported me." One is taken automatically on death and on being punished,
 * and staff can force one from the GUI.
 * <p>
 * They live in the database, not in memory. The moments this evidence is most wanted —
 * after a crash, after a restart, the morning after — were precisely the moments the old
 * in-memory version had already thrown it away.
 */
public class InventoryModule implements Module {

	@Override
	public String id() {
		return "inventory";
	}

	@Override
	public String displayName() {
		return "Inventory";
	}

	private boolean purgeRegistered;

	/**
	 * Retention is applied once the world is open.
	 * <p>
	 * Modules are enabled during mod init, which is before {@code SERVER_STARTING} has told
	 * us where the world directory is — so at {@code onEnable} time there is no database to
	 * purge from yet. Hooking server start is the first moment the question can be answered.
	 */
	@Override
	public void onEnable() {
		if (purgeRegistered) return;
		purgeRegistered = true;

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
				server -> purgeOlderThan(dev.lebron.staffcore.config.StaffConfig.get().snapshotRetentionDays));
	}

	/**
	 * One recorded inventory. Metadata only — {@link #contentsOf} fetches the stacks.
	 * <p>
	 * Splitting them matters because the list screen draws ten of these and needs none of
	 * their contents. Carrying four hundred item stacks around to render ten lines of text
	 * would be paying the entire cost of the feature to display none of it.
	 */
	/**
	 * Why a snapshot was taken, which decides what gets thrown away first.
	 * <p>
	 * The distinction exists because the per-player cap evicts oldest-first regardless, and
	 * automatic snapshots are far more frequent than deliberate ones. Without a way to rank
	 * them, switching automation on would quietly push out the death snapshot from two hours
	 * ago — replacing the evidence somebody is actually asking about with a routine capture
	 * of an ordinary logout.
	 */
	public enum Kind {
		/** Somebody asked for it, or something happened that people argue about afterwards. */
		EVIDENCE,
		/** Taken on a schedule or an ordinary event. Discarded first when space runs out. */
		ROUTINE
	}

	public record Snapshot(long id, String label, long takenAt, String takenBy, int itemCount,
			Kind kind) {

		public boolean isRoutine() {
			return kind == Kind.ROUTINE;
		}
	}

	// ------------------------------------------------------------------ snapshots

	/**
	 * Records a player's inventory, and returns what was stored.
	 * <p>
	 * Synchronous, but batched into a single transaction: at most forty-one small rows
	 * against a WAL-mode file, on an event that happens when somebody dies rather than every
	 * tick. If storage is unavailable the call still returns a usable record — losing a
	 * snapshot must never be able to interrupt the death or punishment that triggered it.
	 */
	public Snapshot capture(ServerPlayer target, String label, String takenBy) {
		return capture(target, label, takenBy, Kind.EVIDENCE);
	}

	/** As above, but says whether this is evidence or a routine capture. */
	public Snapshot capture(ServerPlayer target, String label, String takenBy, Kind kind) {
		Inventory inv = target.getInventory();
		int size = inv.getContainerSize();
		ItemStack[] copy = new ItemStack[size];
		int stacks = 0;
		for (int i = 0; i < size; i++) {
			copy[i] = inv.getItem(i).copy();
			if (!copy[i].isEmpty()) stacks++;
		}

		long now = System.currentTimeMillis();
		Connection c = conn();
		if (c == null) return new Snapshot(0L, label, now, takenBy, stacks, kind);

		MinecraftServer server = Mc.server(target);
		try {
			long id;
			boolean autoCommit = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				try (PreparedStatement ps = c.prepareStatement(
						"INSERT INTO snapshots (uuid, label, taken_by, stacks, taken_at, kind, world, x, y, z) "
								+ "VALUES (?,?,?,?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					ps.setString(1, target.getUUID().toString());
					ps.setString(2, label);
					ps.setString(3, takenBy);
					ps.setInt(4, stacks);
					ps.setLong(5, now);
					ps.setString(6, kind.name());
					// Where they were standing, so a restore can clear the drops it would
					// otherwise duplicate.
					ps.setString(7, Mc.dimensionId(target.level()));
					ps.setInt(8, target.getBlockX());
					ps.setInt(9, target.getBlockY());
					ps.setInt(10, target.getBlockZ());
					ps.executeUpdate();
					try (ResultSet keys = ps.getGeneratedKeys()) {
						id = keys.next() ? keys.getLong(1) : 0L;
					}
				}

				// Empty slots are skipped rather than written as "{}". A mostly-empty
				// inventory is the common case and the slot index is stored per row, so
				// nothing is lost by leaving the gaps out.
				try (PreparedStatement ps = c.prepareStatement(
						"INSERT INTO snapshot_items (snapshot_id, slot, item) VALUES (?,?,?)")) {
					for (int i = 0; i < size; i++) {
						if (copy[i].isEmpty()) continue;
						ps.setLong(1, id);
						ps.setInt(2, i);
						ps.setString(3, ItemCodec.encode(server, copy[i]));
						ps.addBatch();
					}
					ps.executeBatch();
				}
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(autoCommit);
			}

			prune(target.getUUID());
			return new Snapshot(id, label, now, takenBy, stacks, kind);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Inventory] Could not store a snapshot for {}", Mc.name(target), e);
			return new Snapshot(0L, label, now, takenBy, stacks, kind);
		}
	}

	/** Newest first. */
	public List<Snapshot> snapshotsFor(UUID player) {
		List<Snapshot> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT id, label, taken_by, stacks, taken_at, kind FROM snapshots "
						+ "WHERE uuid = ? ORDER BY taken_at DESC")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Snapshot(rs.getLong("id"), rs.getString("label"),
							rs.getLong("taken_at"), rs.getString("taken_by"), rs.getInt("stacks"),
							kindOf(rs.getString("kind"))));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Inventory] Could not read snapshots", e);
		}
		return out;
	}

	/** Unknown or missing values read as evidence, which is the safe way to be wrong. */
	private static Kind kindOf(String stored) {
		try {
			return stored == null ? Kind.EVIDENCE : Kind.valueOf(stored);
		} catch (IllegalArgumentException e) {
			return Kind.EVIDENCE;
		}
	}

	/**
	 * One page of a player's snapshots, newest first.
	 * <p>
	 * {@link #snapshotsFor} loads every row, which is fine at the default cap of ten and not
	 * fine at all once {@code maxSnapshotsPerPlayer} is set to 0 — that is a documented
	 * setting meaning "keep everything", and on a long-lived server it means a screen drawing
	 * twenty-eight rows pulls thousands into memory to do it.
	 */
	public List<Snapshot> snapshotPage(UUID player, int offset, int limit) {
		List<Snapshot> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT id, label, taken_by, stacks, taken_at, kind FROM snapshots "
						+ "WHERE uuid = ? ORDER BY taken_at DESC LIMIT ? OFFSET ?")) {
			ps.setString(1, player.toString());
			ps.setInt(2, limit);
			ps.setInt(3, offset);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Snapshot(rs.getLong("id"), rs.getString("label"),
							rs.getLong("taken_at"), rs.getString("taken_by"), rs.getInt("stacks"),
							kindOf(rs.getString("kind"))));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Inventory] Could not page snapshots", e);
		}
		return out;
	}

	public int snapshotCount(UUID player) {
		Connection c = conn();
		if (c == null) return 0;

		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM snapshots WHERE uuid = ?")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/**
	 * The stacks a snapshot recorded, indexed by slot.
	 * <p>
	 * An unreadable row decodes to empty rather than aborting the load: an item from a mod
	 * since removed should cost one slot, not the whole restore.
	 */
	public ItemStack[] contentsOf(Snapshot snapshot) {
		// Every slot, not just the 36 storage ones. Capture has always written armour and
		// offhand — they are slots 36-42 and getItem() reaches them — but this array used to
		// be INVENTORY_SIZE long, so the bounds check below threw every one of those rows
		// away on the way back out. The items were in the database the whole time; nothing
		// could ever read them, so a snapshot showed bare armour slots and restoring one
		// left the player with none.
		ItemStack[] out = new ItemStack[Mc.PLAYER_SLOTS];
		Arrays.fill(out, ItemStack.EMPTY);

		Connection c = conn();
		if (c == null || snapshot.id() == 0L) return out;

		MinecraftServer server = StaffCore.server();
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT slot, item FROM snapshot_items WHERE snapshot_id = ?")) {
			ps.setLong(1, snapshot.id());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int slot = rs.getInt("slot");
					if (slot < 0 || slot >= out.length) continue;
					out[slot] = ItemCodec.decode(server, rs.getString("item"));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Inventory] Could not read snapshot contents", e);
		}
		return out;
	}

	/**
	 * Writes a snapshot back onto a player. Used for the "they lost items to a bug"
	 * case; every restore is announced to the target so it is never done invisibly.
	 */
	public int restore(ServerPlayer target, Snapshot snapshot) {
		ItemStack[] contents = contentsOf(snapshot);
		Inventory inv = target.getInventory();

		// Clearing first means anything they picked up since is gone, which is the point:
		// this puts them back to a recorded moment rather than adding to what they have.
		// clearContent() empties equipment as well as storage, so armour the snapshot did not
		// have does not survive the restore.
		inv.clearContent();
		int size = Math.min(contents.length, inv.getContainerSize());
		for (int i = 0; i < size; i++) {
			inv.setItem(i, contents[i].copy());
		}

		// Both menus. containerMenu is whatever they happen to have open, and if that is a
		// chest it has no armour or offhand slots in it — so armour restored while somebody
		// is standing in a container would not appear on their own screen until something
		// else refreshed it. Other players are fine either way: LivingEntity re-checks
		// equipment against its last known set every tick.
		target.inventoryMenu.broadcastChanges();
		if (target.containerMenu != target.inventoryMenu) {
			target.containerMenu.broadcastChanges();
		}

		int cleared = reclaimDrops(target, snapshot, contents);

		target.sendSystemMessage(dev.lebron.staffcore.gui.Theme.info(
				"Staff restored your inventory from a snapshot taken "
						+ snapshot.label().toLowerCase(java.util.Locale.ROOT) + "."));
		return cleared;
	}

	/** How far around the recorded position to sweep for the originals. */
	private static final double DROP_SWEEP_RADIUS = 12.0D;

	/**
	 * Removes the drops the restore has just duplicated.
	 * <p>
	 * The commonest restore by far is a death snapshot, and a death leaves the items lying
	 * where the player fell. Handing back a copy while the originals are still on the ground
	 * doubles everything — so the staff member fixing an item loss quietly creates an item
	 * duplication, which is a worse problem and a much less obvious one.
	 * <p>
	 * Only stacks the snapshot actually contains are taken, and only up to the amount it
	 * recorded, within a short distance of where it was taken. A lava death where most of it
	 * burned leaves nothing to find, and the few survivors floating there are removed — which
	 * is exactly right: the player has been given a 1:1 copy of all of it, components and
	 * custom name included, because {@link ItemCodec} stores the whole stack rather than an
	 * id and a count.
	 */
	private int reclaimDrops(ServerPlayer target, Snapshot snapshot, ItemStack[] contents) {
		var origin = positionOf(snapshot);
		if (origin == null) return 0;

		net.minecraft.server.MinecraftServer server = Mc.server(target);
		if (server == null) return 0;

		net.minecraft.server.level.ServerLevel level = null;
		for (net.minecraft.server.level.ServerLevel candidate : server.getAllLevels()) {
			if (Mc.dimensionId(candidate).equals(origin.world())) {
				level = candidate;
				break;
			}
		}
		if (level == null) return 0;

		// What the snapshot holds, by item type, so a partial survival is matched fairly.
		java.util.Map<net.minecraft.world.item.Item, Integer> owed = new java.util.HashMap<>();
		for (ItemStack stack : contents) {
			if (!stack.isEmpty()) owed.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}
		if (owed.isEmpty()) return 0;

		net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
				origin.pos()).inflate(DROP_SWEEP_RADIUS);

		int removed = 0;
		for (net.minecraft.world.entity.item.ItemEntity drop
				: level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area)) {

			Integer due = owed.get(drop.getItem().getItem());
			if (due == null || due <= 0) continue;

			int take = Math.min(due, drop.getItem().getCount());
			owed.put(drop.getItem().getItem(), due - take);
			removed += take;

			if (take >= drop.getItem().getCount()) {
				drop.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			} else {
				drop.getItem().shrink(take);
			}
		}
		return removed;
	}

	/** Where a snapshot was taken, or null for one recorded before positions were kept. */
	private record Origin(String world, net.minecraft.core.BlockPos pos) {}

	private Origin positionOf(Snapshot snapshot) {
		Connection c = conn();
		if (c == null || snapshot.id() == 0L) return null;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT world, x, y, z FROM snapshots WHERE id = ?")) {
			ps.setLong(1, snapshot.id());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				String world = rs.getString("world");
				if (world == null || rs.getObject("x") == null) return null;
				return new Origin(world, new net.minecraft.core.BlockPos(
						rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
			}
		} catch (SQLException e) {
			return null;
		}
	}

	/**
	 * Deletes one snapshot, by row id.
	 * <p>
	 * Identity matching was right while these were objects held in a map; now that every
	 * read builds fresh records, the row id is the only stable handle.
	 */
	public boolean removeSnapshot(UUID player, Snapshot snapshot) {
		if (snapshot.id() == 0L) return false;

		// Transactional because it is two deletes: a crash between them leaves the item rows
		// orphaned under an id nothing points at any more, taking up space forever and
		// belonging to a snapshot that no longer exists.
		boolean[] removed = { false };
		StaffCore.storage().inTransaction(c -> {
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshot_items WHERE snapshot_id = ?")) {
				ps.setLong(1, snapshot.id());
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshots WHERE id = ? AND uuid = ?")) {
				ps.setLong(1, snapshot.id());
				ps.setString(2, player.toString());
				removed[0] = ps.executeUpdate() > 0;
			}
		});
		return removed[0];
	}

	/** Drops every snapshot for a player. Returns how many went. */
	public int clearSnapshots(UUID player) {
		int[] gone = { 0 };
		StaffCore.storage().inTransaction(c -> {
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshot_items WHERE snapshot_id IN "
							+ "(SELECT id FROM snapshots WHERE uuid = ?)")) {
				ps.setString(1, player.toString());
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM snapshots WHERE uuid = ?")) {
				ps.setString(1, player.toString());
				gone[0] = ps.executeUpdate();
			}
		});
		return gone[0];
	}

	/**
	 * Nothing to forget on disconnect any more — snapshots outlive the session deliberately.
	 * Kept so the disconnect handler does not have to special-case this module.
	 */
	public void forget(UUID player) {
		// intentionally empty
	}

	/**
	 * Trims a player back to {@code maxSnapshotsPerPlayer}, discarding routine captures
	 * before evidence.
	 * <p>
	 * A plain oldest-first cap was fine while every snapshot was deliberate. It stops being
	 * fine the moment snapshots are also taken automatically: those are far more frequent, so
	 * a straight age ordering means an ordinary logout capture from five minutes ago evicts
	 * the death snapshot from two hours ago — throwing away the one somebody is actually
	 * arguing about to keep one nobody will ever open.
	 * <p>
	 * So the ordering is by kind first and age second. Routine captures are spent first, and
	 * evidence is only touched once there is nothing else left to give. That keeps automatic
	 * snapshots strictly additive: switching them on can never cost you a snapshot you would
	 * have had without them.
	 * <p>
	 * How much to hold is a judgement about your own server, so the cap is configurable and
	 * 0 keeps everything. Retention in days is a separate lever: this one bounds a busy
	 * player, that one bounds an old dispute.
	 */
	private void prune(UUID player) {
		Connection c = conn();
		if (c == null) return;

		int keep = dev.lebron.staffcore.config.StaffConfig.get().maxSnapshotsPerPlayer;
		if (keep <= 0) return;

		// Evidence sorts before routine, then newest before oldest; LIMIT -1 OFFSET n is
		// SQLite's "everything after the first n rows".
		String doomed = "SELECT id FROM snapshots WHERE uuid = ? "
				+ "ORDER BY CASE kind WHEN 'ROUTINE' THEN 1 ELSE 0 END ASC, taken_at DESC "
				+ "LIMIT -1 OFFSET ?";

		try {
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshot_items WHERE snapshot_id IN (" + doomed + ")")) {
				ps.setString(1, player.toString());
				ps.setInt(2, keep);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshots WHERE id IN (" + doomed + ")")) {
				ps.setString(1, player.toString());
				ps.setInt(2, keep);
				ps.executeUpdate();
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Inventory] Could not prune snapshots: {}", e.getMessage());
		}
	}

	/**
	 * Drops snapshots older than the configured retention. Called once at start.
	 * <p>
	 * Without it the table only ever grows: every death of every player, for ever.
	 */
	public int purgeOlderThan(int days) {
		Connection c = conn();
		if (c == null || days <= 0) return 0;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		try {
			try (PreparedStatement ps = c.prepareStatement(
					"DELETE FROM snapshot_items WHERE snapshot_id IN "
							+ "(SELECT id FROM snapshots WHERE taken_at < ?)")) {
				ps.setLong(1, cutoff);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM snapshots WHERE taken_at < ?")) {
				ps.setLong(1, cutoff);
				int gone = ps.executeUpdate();
				if (gone > 0) {
					StaffCore.LOGGER.info("[Inventory] Purged {} snapshot(s) older than {} days", gone, days);
				}
				return gone;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Inventory] Could not purge old snapshots: {}", e.getMessage());
			return 0;
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}

	// --------------------------------------------------------------- confiscation

	/**
	 * What a confiscation took, for the message, the audit line and the vault.
	 * <p>
	 * {@code taken} carries the actual stacks. Confiscation deliberately does not decide
	 * what becomes of them — holding them for review and destroying them outright are both
	 * legitimate, and that call belongs to the person who pressed the button, not here.
	 */
	public record Confiscation(int stacks, int items, List<String> names, List<ItemStack> taken) {
		public boolean isEmpty() {
			return stacks == 0;
		}

		public String summary() {
			return String.join(", ", names);
		}
	}

	/**
	 * Removes every stack matching {@code doomed} from a player's inventory and ender chest.
	 * <p>
	 * A snapshot is taken first, always. Confiscation is the most contestable thing staff
	 * can do — "you took my stuff" is the complaint that follows — so the state before it is
	 * preserved automatically and can be put back in one click if the call was wrong.
	 */
	public Confiscation confiscate(ServerPlayer target, String by, Predicate<ItemStack> doomed) {
		capture(target, "Before confiscation", by);

		List<String> names = new ArrayList<>();
		List<ItemStack> taken = new ArrayList<>();
		int[] tally = { 0, 0 };   // stacks, items

		sweep(target.getInventory(), doomed, names, taken, tally);
		sweep(target.getEnderChestInventory(), doomed, names, taken, tally);

		if (tally[0] > 0) {
			target.getInventory().setChanged();
			target.containerMenu.broadcastChanges();
			target.sendSystemMessage(dev.lebron.staffcore.gui.Theme.warn(
					"Staff removed " + tally[0] + " item stack(s) from your inventory."));
		}
		return new Confiscation(tally[0], tally[1], names, taken);
	}

	/** Removes one stack, wherever it is. Used by the per-slot confiscate button. */
	public Confiscation confiscateStack(ServerPlayer target, String by, Container container, int slot) {
		ItemStack stack = container.getItem(slot);
		if (stack.isEmpty()) return new Confiscation(0, 0, List.of(), List.of());

		capture(target, "Before confiscation", by);
		String name = stack.getCount() + "× " + stack.getHoverName().getString();
		int count = stack.getCount();
		ItemStack copy = stack.copy();

		container.setItem(slot, ItemStack.EMPTY);
		container.setChanged();
		target.containerMenu.broadcastChanges();
		target.sendSystemMessage(dev.lebron.staffcore.gui.Theme.warn(
				"Staff removed " + name + " from your inventory."));

		return new Confiscation(1, count, List.of(name), List.of(copy));
	}

	private void sweep(Container container, Predicate<ItemStack> doomed,
			List<String> names, List<ItemStack> taken, int[] tally) {

		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !doomed.test(stack)) continue;

			names.add(stack.getCount() + "× " + stack.getHoverName().getString());
			taken.add(stack.copy());
			tally[0]++;
			tally[1] += stack.getCount();
			container.setItem(i, ItemStack.EMPTY);
		}
	}

	// --------------------------------------------------------------------- counts

	/** Non-empty slots a player is currently carrying — shown on their head icon. */
	public int usedSlots(ServerPlayer target) {
		Inventory inv = target.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty()) n++;
		}
		return n;
	}

	public String describe(ServerPlayer target) {
		return Mc.name(target) + " — " + usedSlots(target) + " slot(s) in use";
	}
}
