package dev.lebron.staffcore.modules.grief;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.util.ItemCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Records what actually moved in and out of a chest.
 * <p>
 * The block log answers "who broke this container". It cannot answer "who emptied it",
 * which is the question behind most theft reports — and until now a rollback would put a
 * looted chest back and leave it empty, which is arguably worse than not rolling back at
 * all because it looks like the problem was fixed.
 * <p>
 * The approach is a snapshot-and-diff rather than intercepting every click: contents are
 * copied when a player opens a container and compared when they close it. That misses
 * nothing a click hook would catch, survives shift-clicking and drag-distribution without
 * special cases, and costs one array copy per container opened instead of work on every
 * slot interaction.
 */
public final class ContainerWatch {

	/** What a player found when they opened a container, and where it was. */
	private record OpenContainer(BlockPos pos, String world, Container container, ItemStack[] before) {}

	private final Map<UUID, OpenContainer> open = new HashMap<>();

	/** One row of movement: a stack that appeared in or vanished from a container. */
	public record Move(long id, String player, String action, String item, int count,
			int slot, int x, int y, int z, long at) {}

	/**
	 * SQL matching any one of several block positions.
	 * <p>
	 * A double chest is one inventory reached from two blocks, and a row is written at
	 * whichever half the player happened to click. Two people using opposite halves therefore
	 * write to two different coordinates &mdash; so any query that looks at a single position
	 * sees half the history and quietly presents it as all of it. That is not a display
	 * quirk: it is the difference between "nobody took anything" and "somebody did".
	 */
	static String anyPosition(int count) {
		StringBuilder sql = new StringBuilder("(");
		for (int i = 0; i < count; i++) {
			if (i > 0) sql.append(" OR ");
			sql.append("(x = ? AND y = ? AND z = ?)");
		}
		return sql.append(")").toString();
	}

	/** Binds what {@link #anyPosition} expects. Returns the next free parameter index. */
	static int bindPositions(PreparedStatement ps, int from, Collection<BlockPos> positions)
			throws SQLException {

		int i = from;
		for (BlockPos pos : positions) {
			ps.setInt(i++, pos.getX());
			ps.setInt(i++, pos.getY());
			ps.setInt(i++, pos.getZ());
		}
		return i;
	}

	// -------------------------------------------------------------------- capture

	/** Called when a player opens a container block. */
	public void onOpen(ServerPlayer player, BlockPos pos, Container container, String world) {
		if (container == null) return;

		// Scanned before the snapshot is taken, not after. The scan can confiscate a staff
		// tool, and a snapshot from before that happened would make the diff on close read
		// as though this player had taken it — recording our own confiscation as their
		// theft, in the log staff would use to judge them.
		dev.lebron.staffcore.module.Mods.security().scanContainer(player, pos, world, container);

		// Scanning on open as well as close catches a chest that was already stocked —
		// otherwise a container filled before the watch existed stays invisible until
		// somebody happens to change something in it.
		open.put(player.getUUID(), new OpenContainer(pos, world, container, copyOf(container)));
	}

	/**
	 * Called when the player closes whatever they had open. Diffs and records.
	 * <p>
	 * Runs on the server thread and touches only the arrays it already holds; the write
	 * itself is handed to the grief worker so a slow disk never stalls a container close.
	 */
	public void onClose(ServerPlayer player, ExecutorService worker) {
		OpenContainer record = open.remove(player.getUUID());
		if (record == null || worker == null || !StaffCore.storage().isReady()) return;

		MinecraftServer server = Mc.server(player);
		if (server == null) return;

		ItemStack[] after = copyOf(record.container());
		List<Move> moves = diff(record, after, Mc.name(player));

		// Checked on the way out too, so putting a banned item into a chest is caught at the
		// moment it happens rather than whenever somebody next looks inside.
		dev.lebron.staffcore.module.Mods.security()
				.scanContainer(player, record.pos(), record.world(), record.container());

		if (moves.isEmpty()) return;

		// Encoding needs the registry, so it happens here rather than on the worker.
		List<String[]> rows = new ArrayList<>(moves.size());
		for (Move move : moves) {
			rows.add(new String[] { move.player(), move.action(), move.item(),
					String.valueOf(move.count()), String.valueOf(move.slot()) });
		}
		long now = System.currentTimeMillis();
		BlockPos pos = record.pos();
		String world = record.world();

		worker.execute(() -> write(rows, world, pos, now));
	}

	private static ItemStack[] copyOf(Container container) {
		int size = container.getContainerSize();
		ItemStack[] out = new ItemStack[size];
		for (int i = 0; i < size; i++) {
			out[i] = container.getItem(i).copy();
		}
		return out;
	}

	/**
	 * Compares before and after slot by slot.
	 * <p>
	 * Deliberately per-slot rather than a net tally across the container: a player who takes
	 * 64 diamonds from one slot and puts 64 cobblestone in another has done two things, and
	 * a net comparison would show one confusing swap.
	 */
	private List<Move> diff(OpenContainer record, ItemStack[] after, String player) {
		List<Move> moves = new ArrayList<>();
		ItemStack[] before = record.before();
		int size = Math.min(before.length, after.length);

		MinecraftServer server = StaffCore.server();
		if (server == null) return moves;

		for (int slot = 0; slot < size; slot++) {
			ItemStack was = before[slot];
			ItemStack now = after[slot];
			if (ItemStack.matches(was, now)) continue;

			if (!was.isEmpty() && (now.isEmpty() || !ItemStack.isSameItemSameComponents(was, now))) {
				moves.add(row(player, "TAKE", server, was, was.getCount(), slot, record));
			} else if (!was.isEmpty() && was.getCount() > now.getCount()) {
				moves.add(row(player, "TAKE", server, was, was.getCount() - now.getCount(), slot, record));
			}

			if (!now.isEmpty() && (was.isEmpty() || !ItemStack.isSameItemSameComponents(was, now))) {
				moves.add(row(player, "PUT", server, now, now.getCount(), slot, record));
			} else if (!now.isEmpty() && now.getCount() > was.getCount()) {
				moves.add(row(player, "PUT", server, now, now.getCount() - was.getCount(), slot, record));
			}
		}
		return moves;
	}

	private Move row(String player, String action, MinecraftServer server, ItemStack stack,
			int count, int slot, OpenContainer record) {
		ItemStack single = stack.copy();
		single.setCount(1);
		return new Move(-1, player, action, ItemCodec.encode(server, single), count, slot,
				record.pos().getX(), record.pos().getY(), record.pos().getZ(), 0);
	}

	private void write(List<String[]> rows, String world, BlockPos pos, long at) {
		String sql = """
				INSERT INTO container_log
				  (player_name, action, item, count, slot, world, x, y, z, created_at)
				VALUES (?,?,?,?,?,?,?,?,?,?)
				""";
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			for (String[] row : rows) {
				ps.setString(1, row[0]);
				ps.setString(2, row[1]);
				ps.setString(3, row[2]);
				ps.setInt(4, Integer.parseInt(row[3]));
				ps.setInt(5, Integer.parseInt(row[4]));
				ps.setString(6, world);
				ps.setInt(7, pos.getX());
				ps.setInt(8, pos.getY());
				ps.setInt(9, pos.getZ());
				ps.setLong(10, at);
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] container log failed", e);
		}
	}

	public void forget(UUID player) {
		open.remove(player);
	}

	// -------------------------------------------------------------------- reading

	/** Movements at one exact container, newest first — what the inspect stick shows. */
	public List<Move> at(net.minecraft.world.level.Level level, BlockPos pos, long windowMs,
			int limit) {

		List<Move> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		// Both halves of a double chest, because they are one container and a row lands on
		// whichever block was clicked.
		java.util.Set<BlockPos> positions = Mc.containerHalves(level, pos);

		String sql = "SELECT * FROM container_log WHERE world = ? AND "
				+ anyPosition(positions.size())
				+ " AND created_at >= ? ORDER BY created_at DESC LIMIT ?";

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, Mc.dimensionId(level));
			i = bindPositions(ps, i, positions);
			ps.setLong(i++, System.currentTimeMillis() - windowMs);
			ps.setInt(i, limit);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] container log lookup failed", e);
		}
		return out;
	}


	// ------------------------------------------------------------------- rollback

	/**
	 * What a container rollback managed, and what it could not.
	 *
	 * @param deferred stacks that had nowhere to go because the container was full. The rows
	 *                 stay un-retired so retrying works, and this is the number that says so
	 *                 out loud instead of leaving staff to compare counts by hand.
	 */
	public record Result(int restored, int deferred) {
		static final Result NOTHING = new Result(0, 0);
	}

	/**
	 * Puts back what was taken from containers in an area, and removes what was added.
	 * <p>
	 * Restoring into the exact slot it came from where that slot is free, and anywhere free
	 * otherwise — a chest that comes back with the right items in the wrong order is a far
	 * better outcome than one that comes back empty.
	 */
	public Result rollback(net.minecraft.server.level.ServerLevel level, String player,
			BlockPos centre, int radius, long windowMs, boolean dryRun) {
		return rollback(level, player, centre, radius, windowMs, dryRun, java.util.Set.of());
	}

	/**
	 * @param alreadyRestored containers that were rebuilt from a break snapshot, and must not
	 *                        be replayed over. See the note inside the loop — this is the
	 *                        difference between a repair and deleting the contents twice.
	 */
	public Result rollback(net.minecraft.server.level.ServerLevel level, String player,
			BlockPos centre, int radius, long windowMs, boolean dryRun,
			java.util.Set<BlockPos> alreadyRestored) {

		if (!StaffCore.storage().isReady()) return Result.NOTHING;

		String world = Mc.dimensionId(level);
		long cutoff = System.currentTimeMillis() - windowMs;
		String sql = """
				SELECT * FROM container_log
				WHERE world = ? AND created_at >= ? AND rolled_back = 0
				  AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?
				""" + (player == null ? "" : "  AND player_name = ?\n")
				+ "ORDER BY created_at DESC";

		int restored = 0;
		int deferred = 0;
		List<Long> applied = new ArrayList<>();

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, world);
			ps.setLong(i++, cutoff);
			ps.setInt(i++, centre.getX() - radius);
			ps.setInt(i++, centre.getX() + radius);
			ps.setInt(i++, centre.getZ() - radius);
			ps.setInt(i++, centre.getZ() + radius);
			if (player != null) ps.setString(i, player);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Move move = map(rs);
					BlockPos pos = new BlockPos(move.x(), move.y(), move.z());

					// A container rebuilt from a break snapshot is already correct, and
					// replaying the log on top of it undoes the same movements a second time.
					//
					// The snapshot is the container's actual final state at the instant it was
					// destroyed — every PUT and TAKE before that is baked into it. So the
					// obvious-looking sequence "restore the chest full, then undo the PUTs
					// that filled it" strips the contents straight back out, while the
					// offender is separately debited for them. The items end up nowhere,
					// which is worse than either half of the repair on its own.
					if (alreadyRestored.contains(pos)) continue;

					Container container = Mc.containerAt(level, pos);
					if (container == null) continue;

					ItemStack stack = ItemCodec.decode(level.getServer(), move.item());
					if (stack.isEmpty()) continue;
					stack.setCount(move.count());

					// A preview that always claims everything fits is not a preview of this
					// rollback. Putting a stack back needs a free slot, so a full chest is
					// predictable before anything is written — and being told beforehand is
					// the whole reason to run a preview.
					if (dryRun) {
						if ("PUT".equals(move.action()) || hasRoom(container, move.slot())) restored++;
						else deferred++;
						continue;
					}

					// TAKE is undone by putting it back; PUT is undone by removing it.
					boolean undone = "TAKE".equals(move.action())
							? insert(container, move.slot(), stack)
							: remove(container, stack);

					// Only a row that actually applied is retired. Marking a failed one
					// rolled-back would quietly forget it: the chest was full, the items were
					// never returned, and running the rollback again would now skip the row
					// entirely. Leaving it un-retired means clearing space and retrying works.
					if (undone) {
						restored++;
						applied.add(move.id());
					} else {
						deferred++;
					}
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] container rollback failed", e);
		}

		if (!applied.isEmpty()) retire(applied);
		return new Result(restored, deferred);
	}

	// ------------------------------------------------------------------ undoing theft

	/** What undoing a theft managed. */
	/**
	 * How far around the looted container to sweep for a haul dumped on the floor.
	 * <p>
	 * Deliberately tighter than a block rollback's radius: a rollback is given a radius by
	 * the staff member running it, where this is scoped to one chest and should not go
	 * hoovering up unrelated items from halfway across a base. The offender gets swept
	 * around separately wherever they are standing, which is where a dropped haul usually is.
	 */
	private static final int THEFT_SWEEP_RADIUS = 12;

	public record TheftResult(int restored, int deferred, int debited, int queued) {
		public boolean didNothing() {
			return restored == 0 && deferred == 0;
		}
	}

	/**
	 * Puts back what was taken out of one specific container, and takes it off the thief.
	 * <p>
	 * The area rollback can already do this, but only as part of undoing everything in a
	 * radius and a window — which is the wrong shape for the commonest theft report there is.
	 * "Somebody emptied my chest" is a question about <em>one container</em>, and the answer
	 * should not require reverting every block change around it as well.
	 * <p>
	 * Only TAKE rows are undone. A PUT is somebody adding to the chest, which is not theft
	 * and not something to reverse — the area rollback undoes those because it is putting a
	 * whole region back to a moment in time, and this is not.
	 *
	 * @param thief restrict to one player, or null for everyone who took from it
	 */
	public TheftResult undoTheftAt(net.minecraft.server.level.ServerLevel level, BlockPos pos,
			long windowMs, String thief) {

		if (!StaffCore.storage().isReady()) return new TheftResult(0, 0, 0, 0);

		Container container = Mc.containerAt(level, pos);
		if (container == null) return new TheftResult(0, 0, 0, 0);

		String world = Mc.dimensionId(level);

		// Every half, or undoing a theft from a double chest puts back only the stacks taken
		// through the block staff happened to click.
		java.util.Set<BlockPos> positions = Mc.containerHalves(level, pos);

		String sql = "SELECT * FROM container_log WHERE world = ? AND "
				+ anyPosition(positions.size())
				+ " AND created_at >= ? AND action = 'TAKE' AND rolled_back = 0"
				+ (thief == null ? "" : " AND player_name = ?")
				+ " ORDER BY created_at DESC";

		List<Move> thefts = new ArrayList<>();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, world);
			i = bindPositions(ps, i, positions);
			ps.setLong(i++, System.currentTimeMillis() - windowMs);
			if (thief != null) ps.setString(i, thief);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) thefts.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] theft lookup failed", e);
			return new TheftResult(0, 0, 0, 0);
		}

		int restored = 0;
		int deferred = 0;
		List<Long> applied = new ArrayList<>();

		// Per thief, not one shared pile. Two people who took turns on the same chest each
		// owe what they personally lifted, and charging either of them for the other's half
		// would be a worse mistake than the theft.
		Map<String, Map<net.minecraft.world.item.Item, Integer>> owedBy = new LinkedHashMap<>();

		for (Move move : thefts) {
			ItemStack stack = ItemCodec.decode(level.getServer(), move.item());
			if (stack.isEmpty()) continue;
			stack.setCount(move.count());

			if (insert(container, move.slot(), stack)) {
				restored++;
				applied.add(move.id());
				// Only what actually went back is charged for. A stack we could not fit is
				// still theirs to keep until somebody clears space and runs this again.
				owedBy.computeIfAbsent(move.player(), who -> new HashMap<>())
						.merge(stack.getItem(), move.count(), Integer::sum);
			} else {
				// The chest is full. Left un-retired so clearing space and retrying works.
				deferred++;
			}
		}

		if (!applied.isEmpty()) retire(applied);
		if (owedBy.isEmpty()) return new TheftResult(restored, deferred, 0, 0);

		// Whoever took it must not keep a copy — the same rule that stops a block rollback
		// duplicating items applies exactly as much to a chest.
		return debitThieves(level, pos, owedBy, windowMs, restored, deferred);
	}

	private TheftResult debitThieves(net.minecraft.server.level.ServerLevel level, BlockPos pos,
			Map<String, Map<net.minecraft.world.item.Item, Integer>> owedBy,
			long windowMs, int restored, int deferred) {

		int debited = 0;
		int queued = 0;
		String reason = "Items returned to a chest they took them from";

		for (var due : owedBy.entrySet()) {
			// Every route a thief could use to keep a copy, not just their inventory. This
			// used to check the inventory alone, so dropping the haul on the floor or
			// stashing it in their own chest meant the victim's chest was refilled while the
			// thief kept the lot — the restore printed the difference.
			LootRecovery.Result result = LootRecovery.collect(level, due.getKey(), due.getValue(),
					pos, THEFT_SWEEP_RADIUS, windowMs, this, reason, true,
					Mc.containerHalves(level, pos));

			debited += result.recovered();
			queued += result.queued();
		}
		return new TheftResult(restored, deferred, debited, queued);
	}


	// ------------------------------------------------------------- chasing banked loot

	/**
	 * Takes owed items back out of chests the offender put them into.
	 * <p>
	 * Reclaiming ground drops and debiting a live inventory only catches somebody still
	 * standing in the mess they made. The obvious defeat — carry the haul away and stash it
	 * — used to be out of reach, and that gap is most of what made rollback a duplication
	 * exploit in practice.
	 * <p>
	 * It is not out of reach: every stack put into every container is already logged with
	 * who put it there and when. This walks that player's own deposits inside the same
	 * window, newest first, and takes back only what is still owed. Somebody else's chest
	 * that merely happens to contain cobblestone is never touched, because the log knows the
	 * difference between a chest with cobblestone in it and a chest this person filled.
	 *
	 * @param owed mutated in place as items are found
	 * @return how many individual items were recovered
	 */
	public int reclaimBanked(net.minecraft.server.level.ServerLevel level, String player,
			long windowMs, Map<net.minecraft.world.item.Item, Integer> owed,
			java.util.Set<BlockPos> keepFilled) {

		if (player == null || owed.isEmpty() || !StaffCore.storage().isReady()) return 0;

		long cutoff = System.currentTimeMillis() - windowMs;
		String world = Mc.dimensionId(level);
		int reclaimed = 0;

		String sql = """
				SELECT * FROM container_log
				WHERE world = ? AND player_name = ? AND action = 'PUT' AND created_at >= ?
				ORDER BY created_at DESC
				""";
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			ps.setString(1, world);
			ps.setString(2, player);
			ps.setLong(3, cutoff);

			List<Move> deposits = new ArrayList<>();
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) deposits.add(map(rs));
			}

			for (Move deposit : deposits) {
				if (owed.isEmpty()) break;

				BlockPos pos = new BlockPos(deposit.x(), deposit.y(), deposit.z());

				// Never raid a container this same operation has just refilled. An offender
				// who had also put items into the chest they later emptied would otherwise
				// have those deposits read as banked loot, and the restore would be undone
				// by the debit that is supposed to pay for it.
				if (keepFilled != null && keepFilled.contains(pos)) continue;

				Container container = Mc.containerAt(level, pos);
				if (container == null) continue;

				ItemStack logged = ItemCodec.decode(level.getServer(), deposit.item());
				if (logged.isEmpty()) continue;

				Integer due = owed.get(logged.getItem());
				if (due == null || due <= 0) continue;

				// Never take more than was deposited here, and never more than is owed —
				// a chest the offender also had legitimate items in keeps them.
				int budget = Math.min(due, deposit.count());
				int taken = takeFrom(container, logged, budget);
				if (taken == 0) continue;

				owed.put(logged.getItem(), due - taken);
				reclaimed += taken;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] banked-loot reclaim failed", e);
		}
		return reclaimed;
	}

	/** Removes up to {@code budget} matching items from one container. */
	private int takeFrom(Container container, ItemStack match, int budget) {
		int taken = 0;
		for (int slot = 0; slot < container.getContainerSize() && taken < budget; slot++) {
			ItemStack inSlot = container.getItem(slot);
			if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, match)) continue;

			int take = Math.min(budget - taken, inSlot.getCount());
			inSlot.shrink(take);
			taken += take;
		}
		if (taken > 0) container.setChanged();
		return taken;
	}

	/**
	 * Whether {@link #insert} would find somewhere to put a stack.
	 * <p>
	 * Deliberately the same question in the same order, so a preview and the run that
	 * follows it cannot disagree.
	 */
	private boolean hasRoom(Container container, int preferredSlot) {
		if (preferredSlot >= 0 && preferredSlot < container.getContainerSize()
				&& container.getItem(preferredSlot).isEmpty()) {
			return true;
		}
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).isEmpty()) return true;
		}
		return false;
	}

	private boolean insert(Container container, int preferredSlot, ItemStack stack) {
		if (preferredSlot >= 0 && preferredSlot < container.getContainerSize()
				&& container.getItem(preferredSlot).isEmpty()) {
			container.setItem(preferredSlot, stack);
			container.setChanged();
			return true;
		}
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).isEmpty()) {
				container.setItem(slot, stack);
				container.setChanged();
				return true;
			}
		}
		return false;   // full; the items stay owed rather than being dropped on the floor
	}

	private boolean remove(Container container, ItemStack stack) {
		int owed = stack.getCount();
		for (int slot = 0; slot < container.getContainerSize() && owed > 0; slot++) {
			ItemStack inSlot = container.getItem(slot);
			if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, stack)) continue;

			int take = Math.min(owed, inSlot.getCount());
			inSlot.shrink(take);
			owed -= take;
		}
		container.setChanged();
		return owed < stack.getCount();
	}

	private void retire(List<Long> ids) {
		try (PreparedStatement ps = StaffCore.storage().conn()
				.prepareStatement("UPDATE container_log SET rolled_back = 1 WHERE id = ?")) {
			for (long id : ids) {
				ps.setLong(1, id);
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] could not retire container entries", e);
		}
	}

	private Move map(ResultSet rs) throws SQLException {
		return new Move(rs.getLong("id"), rs.getString("player_name"), rs.getString("action"),
				rs.getString("item"), rs.getInt("count"), rs.getInt("slot"),
				rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getLong("created_at"));
	}
}
