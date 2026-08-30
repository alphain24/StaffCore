package dev.lebron.staffcore.modules.security;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.util.ItemCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where confiscated items go instead of nowhere.
 * <p>
 * Deleting a stack the moment it is flagged makes every false positive permanent and
 * unprovable: the item is gone, the player says it was legitimate, and staff have nothing
 * left to look at. Holding it costs one row and turns a mistake into a click.
 * <p>
 * Rows are never removed on release. A returned item keeps its record with a
 * {@code RETURNED} state, so "what did we take off this player, and what happened to it"
 * stays answerable long after the item itself has gone back.
 */
public final class ContrabandVault {

	/**
	 * What happened to a held stack.
	 * <p>
	 * {@code PENDING_RETURN} is a decision that has been made but not yet carried out: staff
	 * chose to hand the item back while its owner was offline. The row is no longer held —
	 * nobody should destroy it or return it twice — but the item has not moved either.
	 */
	public enum State { HELD, PENDING_RETURN, RETURNED, DESTROYED }

	/**
	 * One confiscated stack.
	 *
	 * @param display the item's name as it read when taken, so a row stays legible even if
	 *                the stack itself no longer decodes
	 */
	public record Entry(long id, UUID ownerId, String ownerName, String takenBy,
			String encoded, String display, int count, String reason,
			State state, long createdAt, Long resolvedAt, String resolvedBy) {

		public ItemStack stack(MinecraftServer server) {
			return ItemCodec.decode(server, encoded);
		}
	}

	// ------------------------------------------------------------------- writing

	/**
	 * Stores a stack and returns its row id, or 0 if storage is unavailable.
	 * <p>
	 * The caller removes the item; this only records it. Keeping those separate means a
	 * failed write cannot leave an item both taken and unrecorded — if this returns 0 the
	 * caller can decide whether taking it is still the right thing to do.
	 */
	public long deposit(ServerPlayer owner, String takenBy, ItemStack stack, String reason) {
		return deposit(owner.getUUID(), Mc.name(owner), takenBy, stack, reason, Mc.server(owner));
	}

	public long deposit(UUID ownerId, String ownerName, String takenBy, ItemStack stack,
			String reason, MinecraftServer server) {

		Connection c = conn();
		if (c == null || stack.isEmpty()) return 0L;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO contraband_vault "
						+ "(owner_uuid, owner_name, taken_by, item, display, count, reason, state, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,'HELD',?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {

			ps.setString(1, ownerId.toString());
			ps.setString(2, ownerName);
			ps.setString(3, takenBy);
			ps.setString(4, ItemCodec.encode(server, stack));
			ps.setString(5, stack.getHoverName().getString());
			ps.setInt(6, stack.getCount());
			ps.setString(7, reason);
			ps.setLong(8, System.currentTimeMillis());
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Vault] Could not store a confiscated item", e);
			return 0L;
		}
	}

	// ------------------------------------------------------------------- reading

	/** Everything still held, newest first. */
	public List<Entry> held(int offset, int limit) {
		return page(State.HELD, offset, limit);
	}

	/**
	 * One page of rows in a given state, or every row when {@code state} is null.
	 * <p>
	 * The vault screen reads through this rather than pulling a capped list into memory, so
	 * "the newest five hundred" stopped being a thing staff had to work around. Rows are
	 * never deleted on release, and this is what finally makes the returned-and-destroyed
	 * history the table has always kept actually reachable.
	 */
	public List<Entry> page(State state, int offset, int limit) {
		String where = state == null ? "" : "WHERE state = ? ";
		return query("SELECT * FROM contraband_vault " + where
				+ "ORDER BY created_at DESC LIMIT ? OFFSET ?", ps -> {
			int i = 1;
			if (state != null) ps.setString(i++, state.name());
			ps.setInt(i++, limit);
			ps.setInt(i, offset);
		});
	}

	public int heldCount() {
		return count(State.HELD);
	}

	/** How many rows are in a state, or in the table altogether when {@code state} is null. */
	public int count(State state) {
		Connection c = conn();
		if (c == null) return 0;

		String sql = state == null
				? "SELECT COUNT(*) FROM contraband_vault"
				: "SELECT COUNT(*) FROM contraband_vault WHERE state = ?";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (state != null) ps.setString(1, state.name());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/**
	 * How many rows exist for one player, without loading any of them.
	 * <p>
	 * The inventory screen wants a number for its header and used to get it by fetching two
	 * hundred rows and calling {@code size()} on the list — decoding every stored stack to
	 * throw them all away. Harmless when the screen was drawn once; not harmless now that it
	 * redraws itself twice a second.
	 */
	public int countFor(UUID owner) {
		Connection c = conn();
		if (c == null) return 0;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM contraband_vault WHERE owner_uuid = ?")) {
			ps.setString(1, owner.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** Everything ever taken off one player, whatever became of it. */
	public List<Entry> forPlayer(UUID owner, int limit) {
		return query("SELECT * FROM contraband_vault WHERE owner_uuid = ? "
				+ "ORDER BY created_at DESC LIMIT ?", ps -> {
			ps.setString(1, owner.toString());
			ps.setInt(2, limit);
		});
	}

	public Entry byId(long id) {
		List<Entry> rows = query("SELECT * FROM contraband_vault WHERE id = ?",
				ps -> ps.setLong(1, id));
		return rows.isEmpty() ? null : rows.get(0);
	}

	// ------------------------------------------------------------------ resolving

	/**
	 * Hands a held stack back to its owner and marks the row returned.
	 *
	 * @return false when the row is missing, already resolved, or the owner is offline
	 */
	public boolean release(Entry entry, ServerPlayer owner, String by) {
		if (entry == null || entry.state() != State.HELD) return false;

		MinecraftServer server = Mc.server(owner);
		ItemStack stack = entry.stack(server);
		if (stack.isEmpty()) return false;

		// Into the inventory if there is room, on the floor if not. Refusing to return an
		// item because somebody's inventory is full would be a strange way to correct a
		// mistake we made.
		dev.lebron.staffcore.util.ItemDebit.give(owner, stack);

		return resolve(entry.id(), State.RETURNED, by);
	}

	/**
	 * Books a held stack to be returned the next time its owner logs in.
	 * <p>
	 * Returning to somebody offline used to be refused outright, because the only way to do
	 * it immediately is to edit their saved player data — a far bigger hammer than this
	 * screen should swing, and one {@link dev.lebron.staffcore.util.OfflineInventory}
	 * declines to pick up. Queueing costs a row and moves the wait somewhere nobody has to
	 * stand in it: the decision is made and logged now, the item moves when there is
	 * somebody to move it to.
	 *
	 * @return false when the row is missing, already resolved, or the queue write failed
	 */
	public boolean queueRelease(MinecraftServer server, Entry entry, String by) {
		if (entry == null || entry.state() != State.HELD) return false;

		ItemStack stack = entry.stack(server);
		if (stack.isEmpty()) return false;

		// Marking the row pending and writing the delivery are one change, not two. Doing
		// them in sequence leaves a gap where a crash strands the item: no longer held, so
		// nothing offers it back, and never queued, so nothing delivers it. One transaction
		// removes the gap — either the item is booked for delivery or it is still in the
		// vault, and there is no third state to discover months later.
		//
		// The compare-and-set inside resolve() is still what it always was: the thing that
		// stops two staff clicking "give back" on one row and queueing two of an item there
		// is one of. Failing it aborts the transaction rather than returning quietly.
		return StaffCore.storage().inTransaction(conn -> {
			if (!resolve(entry.id(), State.PENDING_RETURN, by)) {
				throw new SQLException("vault row " + entry.id() + " is no longer held");
			}
			long queued = StaffCore.pending().queueGive(server, entry.ownerId(), entry.ownerName(),
					stack, entry.reason(), by, entry.id());
			if (queued == 0L) {
				throw new SQLException("could not queue the return of vault row " + entry.id());
			}
		});
	}

	/** Un-books a pending return, putting the row back into the vault. */
	public boolean cancelRelease(Entry entry) {
		if (entry == null || entry.state() != State.PENDING_RETURN) return false;

		for (var queued : StaffCore.pending().byRef(entry.id())) {
			StaffCore.pending().cancel(queued.id());
		}
		return resolveFrom(entry.id(), State.PENDING_RETURN, State.HELD, null);
	}

	/** Destroys a held stack for good. */
	public boolean destroy(Entry entry, String by) {
		if (entry == null || entry.state() != State.HELD) return false;
		return resolve(entry.id(), State.DESTROYED, by);
	}

	private boolean resolve(long id, State state, String by) {
		return resolveFrom(id, State.HELD, state, by);
	}

	/**
	 * Moves a row between states, and only from the state the caller expects.
	 * <p>
	 * The {@code AND state = ?} clause is the whole safety property: two staff clicking
	 * "give back" on the same row at once means the second update matches nothing and
	 * returns false, rather than queueing a second delivery of an item there is one of.
	 */
	private boolean resolveFrom(long id, State from, State to, String by) {
		Connection c = conn();
		if (c == null) return false;

		// Going back to HELD is an undo, so it clears the resolution rather than recording
		// one — a row that reads "resolved by X" while sitting in the vault is a lie.
		boolean undo = to == State.HELD;
		String sql = undo
				? "UPDATE contraband_vault SET state = ?, resolved_at = NULL, resolved_by = NULL "
						+ "WHERE id = ? AND state = ?"
				: "UPDATE contraband_vault SET state = ?, resolved_at = ?, resolved_by = ? "
						+ "WHERE id = ? AND state = ?";

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, to.name());
			if (!undo) {
				ps.setLong(i++, System.currentTimeMillis());
				ps.setString(i++, by);
			}
			ps.setLong(i++, id);
			ps.setString(i, from.name());
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Vault] Could not resolve a vault entry", e);
			return false;
		}
	}

	// --------------------------------------------------------------------- plumbing

	private interface Binder {
		void bind(PreparedStatement ps) throws SQLException;
	}

	private List<Entry> query(String sql, Binder binder) {
		List<Entry> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Vault] Query failed", e);
		}
		return out;
	}

	private static Entry read(ResultSet rs) throws SQLException {
		Long resolvedAt = rs.getObject("resolved_at") == null ? null : rs.getLong("resolved_at");
		State state;
		try {
			state = State.valueOf(rs.getString("state"));
		} catch (IllegalArgumentException e) {
			state = State.HELD;
		}
		return new Entry(
				rs.getLong("id"),
				UUID.fromString(rs.getString("owner_uuid")),
				rs.getString("owner_name"),
				rs.getString("taken_by"),
				rs.getString("item"),
				rs.getString("display"),
				rs.getInt("count"),
				rs.getString("reason"),
				state,
				rs.getLong("created_at"),
				resolvedAt,
				rs.getString("resolved_by"));
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
