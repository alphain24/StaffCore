package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.util.ItemCodec;
import io.github.alphain24.staffcore.inventory.InventoryGateway;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Item movements owed to players who were offline when staff decided them.
 * <p>
 * Handing a vaulted item back and debiting a rolled-back griefer are the same problem seen
 * from two ends: the decision is made now, the player is not here, and the only way to act
 * immediately would be to edit a save file underneath somebody who might reconnect
 * mid-write. {@link io.github.alphain24.staffcore.util.OfflineInventory} refuses to do that on
 * purpose, and this is what refusing costs nothing.
 * <p>
 * The queue is drained on join. Staff click once and move on; the player is told what
 * happened the moment they are in a position to see it.
 */
public final class PendingActions {

	/** What is owed. GIVE hands a stack over; DEBIT takes one back. */
	public enum Kind { GIVE, DEBIT }

	/**
	 * One owed movement.
	 *
	 * @param item  an {@link ItemCodec} blob for GIVE, a plain item id for DEBIT — a debit
	 *              only needs to know the type, and storing a whole stack would imply the
	 *              exact one taken has to come back
	 * @param refId the row that caused this, or null; a vault return carries its entry id
	 *              so delivery can retire it
	 */
	public record Entry(long id, UUID ownerId, String ownerName, Kind kind, String item,
			int count, Long refId, String reason, String queuedBy, long createdAt) {

		/** The stack a GIVE will hand over, or empty for a DEBIT. */
		public ItemStack stack(MinecraftServer server) {
			return kind == Kind.GIVE ? ItemCodec.decode(server, item) : ItemStack.EMPTY;
		}

		/** A short label for the vault and log screens. */
		public String describe(MinecraftServer server) {
			if (kind == Kind.DEBIT) {
				Item type = Mc.itemFromId(item, null);
				String name = type == null ? item : new ItemStack(type).getHoverName().getString();
				return count + "× " + name;
			}
			ItemStack stack = stack(server);
			return stack.isEmpty() ? item : count + "× " + stack.getHoverName().getString();
		}
	}

	// ------------------------------------------------------------------- writing

	/** Queues a stack to be handed over on next login. Returns the row id, or 0. */
	public long queueGive(MinecraftServer server, UUID owner, String ownerName, ItemStack stack,
			String reason, String queuedBy, Long refId) {

		if (stack == null || stack.isEmpty()) return 0L;
		return insert(owner, ownerName, Kind.GIVE, ItemCodec.encode(server, stack),
				stack.getCount(), refId, reason, queuedBy);
	}

	/**
	 * Queues items to be taken back on next login.
	 * <p>
	 * One row per item type rather than one per item: a griefer who owes 300 cobblestone is
	 * one debt, not three hundred.
	 */
	public int queueDebit(UUID owner, String ownerName, Map<Item, Integer> owed,
			String reason, String queuedBy) {
		return queueDebit(owner, ownerName, owed, reason, queuedBy, null, null);
	}

	/**
	 * As above, recording what raised the debt.
	 * <p>
	 * A debit from a rollback only makes sense while that rollback stands. Undo it and the
	 * blocks are broken again, so the reason anybody owed anything has gone — but without a
	 * link back there is no way to find the rows, and the debt outlives its cause.
	 *
	 * @param refKind what {@code refId} points at, currently only {@code ROLLBACK}
	 */
	public int queueDebit(UUID owner, String ownerName, Map<Item, Integer> owed,
			String reason, String queuedBy, String refKind, Long refId) {

		int queued = 0;
		for (Map.Entry<Item, Integer> due : owed.entrySet()) {
			if (due.getValue() == null || due.getValue() <= 0) continue;
			if (insert(owner, ownerName, Kind.DEBIT, Mc.itemId(due.getKey()), due.getValue(),
					refId, reason, queuedBy, refKind) != 0L) {
				queued += due.getValue();
			}
		}
		return queued;
	}

	/**
	 * Drops every outstanding debit raised by one cause.
	 * <p>
	 * Used when a rollback is undone. The obligation existed because the blocks were back; if
	 * they are broken again then nobody gained anything from the restore and there is nothing
	 * to collect. Leaving it would bill somebody for a repair that no longer exists.
	 *
	 * @return how many individual items were written off
	 */
	public int cancelFor(String refKind, long refId) {
		Connection c = conn();
		if (c == null) return 0;

		int items = 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT SUM(count) FROM pending_actions "
						+ "WHERE kind = 'DEBIT' AND ref_kind = ? AND ref_id = ?")) {
			ps.setString(1, refKind);
			ps.setLong(2, refId);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) items = rs.getInt(1);
			}
		} catch (SQLException e) {
			return 0;
		}
		if (items == 0) return 0;

		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM pending_actions WHERE kind = 'DEBIT' AND ref_kind = ? AND ref_id = ?")) {
			ps.setString(1, refKind);
			ps.setLong(2, refId);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not cancel debts for {} {}", refKind, refId, e);
			return 0;
		}
		return items;
	}

	/**
	 * Writes off debts older than the configured window.
	 * <p>
	 * A debit is an anti-duplication measure, not a sentence. It exists because somebody might
	 * still be carrying what a rollback put back — and the longer nobody manages to collect it,
	 * the likelier it is that those items simply do not exist any more. Past that point,
	 * continuing to charge takes items the player earned honestly afterwards to pay for ones
	 * that were never duplicated, which is the opposite of what this is for.
	 * <p>
	 * Deliveries never expire. A queued GIVE is somebody's property waiting to be handed back,
	 * and losing that would be theft rather than mercy.
	 *
	 * @return how many individual items were written off
	 */
	public int expireOldDebts(int days) {
		Connection c = conn();
		if (days <= 0 || c == null) return 0;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		int items = 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT SUM(count) FROM pending_actions WHERE kind = 'DEBIT' AND created_at < ?")) {
			ps.setLong(1, cutoff);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) items = rs.getInt(1);
			}
		} catch (SQLException e) {
			return 0;
		}
		if (items == 0) return 0;

		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM pending_actions WHERE kind = 'DEBIT' AND created_at < ?")) {
			ps.setLong(1, cutoff);
			ps.executeUpdate();
			StaffCore.LOGGER.info("[Pending] Wrote off {} item(s) owed for more than {} days",
					items, days);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not expire old debts", e);
			return 0;
		}
		return items;
	}

	private long insert(UUID owner, String ownerName, Kind kind, String item, int count,
			Long refId, String reason, String queuedBy) {
		return insert(owner, ownerName, kind, item, count, refId, reason, queuedBy, null);
	}

	private long insert(UUID owner, String ownerName, Kind kind, String item, int count,
			Long refId, String reason, String queuedBy, String refKind) {

		Connection c = conn();
		if (c == null) return 0L;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO pending_actions "
						+ "(uuid, owner_name, kind, item, count, ref_id, ref_kind, reason, queued_by, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,?,?,?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {

			ps.setString(1, owner.toString());
			ps.setString(2, ownerName);
			ps.setString(3, kind.name());
			ps.setString(4, item);
			ps.setInt(5, count);
			if (refId == null) ps.setNull(6, java.sql.Types.INTEGER);
			else ps.setLong(6, refId);
			ps.setString(7, refKind);
			ps.setString(8, reason);
			ps.setString(9, queuedBy);
			ps.setLong(10, System.currentTimeMillis());
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0L;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not queue a {} for {}", kind, ownerName, e);
			return 0L;
		}
	}

	// ------------------------------------------------------------------- reading

	public List<Entry> forPlayer(UUID owner) {
		return query("SELECT * FROM pending_actions WHERE uuid = ? ORDER BY created_at ASC",
				ps -> ps.setString(1, owner.toString()));
	}

	/** Whether anything is waiting for this player — cheaper than loading the rows. */
	public int countFor(UUID owner) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM pending_actions WHERE uuid = ?")) {
			ps.setString(1, owner.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** Row ids waiting against one {@code ref_id}, so a caller can undo what it queued. */
	public List<Entry> byRef(long refId) {
		return query("SELECT * FROM pending_actions WHERE ref_id = ?", ps -> ps.setLong(1, refId));
	}

	// ------------------------------------------------------------------ draining

	/** What a drain actually managed to do, for the message the player sees. */
	public record Settled(int given, int debited, int owedStill) {
		public boolean isEmpty() {
			return given == 0 && debited == 0 && owedStill == 0;
		}
	}

	/**
	 * Settles everything owed to a player who has just joined.
	 * <p>
	 * A GIVE always succeeds — the stack goes to the floor if the inventory is full — so its
	 * row is spent unconditionally. A DEBIT can only take what is actually there; whatever
	 * is left stays queued at the reduced amount, so somebody who spent the loot before
	 * logging off still owes it the next time they have some.
	 */
	public Settled drainFor(ServerPlayer player) {
		Connection c = conn();
		if (c == null) return new Settled(0, 0, 0);

		List<Entry> rows = forPlayer(player.getUUID());
		if (rows.isEmpty()) return new Settled(0, 0, 0);

		MinecraftServer server = Mc.server(player);
		if (server == null) return new Settled(0, 0, 0);

		int given = 0;
		int debited = 0;
		int owedStill = 0;
		List<Long> spent = new ArrayList<>();
		List<Long> vaultRows = new ArrayList<>();
		Map<Long, Integer> reduced = new java.util.LinkedHashMap<>();

		for (Entry row : rows) {
			if (row.kind() == Kind.GIVE) {
				ItemStack stack = row.stack(server);
				if (!stack.isEmpty()) {
					InventoryGateway.give(player, InventoryGateway.Origin.PENDING_SETTLEMENT,
							io.github.alphain24.staffcore.permission.Actor.system(), "queued while they were offline",
							java.util.List.of(stack));
					given += stack.getCount();
				}
				spent.add(row.id());
				if (row.refId() != null) vaultRows.add(row.refId());
				continue;
			}

			Item type = Mc.itemFromId(row.item(), null);
			if (type == null) {
				// The item no longer exists in this version — the debt is unpayable and
				// keeping the row would mean asking for it forever.
				spent.add(row.id());
				continue;
			}

			Map<Item, Integer> owed = new HashMap<>();
			owed.put(type, row.count());
			int took = InventoryGateway.take(player, InventoryGateway.Origin.PENDING_SETTLEMENT,
					io.github.alphain24.staffcore.permission.Actor.system(),
					"settling a debt owed since they were last online", owed).items();
			debited += took;

			int remaining = owed.getOrDefault(type, 0);
			if (remaining <= 0) {
				spent.add(row.id());
			} else {
				owedStill += remaining;
				reduced.put(row.id(), remaining);
			}
		}

		// All the bookkeeping in one go. The items have already moved, so a crash between
		// "handed it over" and "spent the row" would hand it over a second time on their next
		// login — and settling exactly once is the entire promise of this queue.
		StaffCore.storage().inTransaction(conn -> {
			for (long vaultRow : vaultRows) retireVaultRow(conn, vaultRow);
			for (var partial : reduced.entrySet()) reduce(conn, partial.getKey(), partial.getValue());
			deleteAll(conn, spent);
		});

		return new Settled(given, debited, owedStill);
	}

	/**
	 * Tells the player what StaffCore just did to their inventory.
	 * <p>
	 * Silently taking three hundred cobblestone off somebody at login is how a moderation
	 * tool gets mistaken for an item-loss bug.
	 */
	public void announce(ServerPlayer player, Settled settled) {
		if (settled.isEmpty()) return;

		if (settled.given() > 0) {
			player.sendSystemMessage(Theme.good(
					"Staff returned " + settled.given() + " item(s) to you while you were away."));
		}
		if (settled.debited() > 0) {
			player.sendSystemMessage(Theme.info(
					settled.debited() + " item(s) were taken back as part of a rollback."));
		}
		// Only mentioned when something actually moved this login. Repeating it every time
		// somebody joins is nagging rather than informing: they cannot pay a debt in items
		// they do not have, and a warning that fires on every connection stops being read
		// long before it stops being sent. It is on their file either way, and staff can see
		// it with /staff owed.
		if (settled.owedStill() > 0 && (settled.debited() > 0 || settled.given() > 0)) {
			player.sendSystemMessage(Theme.warn(
					settled.owedStill() + " item(s) are still owed from that rollback."));
		}
	}

	/** One player and what they owe, for the overview. */
	public record Debt(String ownerName, java.util.UUID ownerId, int rows, int items) {}

	/**
	 * Everybody currently owing something, worst first.
	 * <p>
	 * The queue was previously write-only: rows went in, players were nagged on every join,
	 * and there was no way to see who was affected or to let anybody off. A moderation tool
	 * that can create an obligation and not clear one is half a tool.
	 */
	public List<Debt> outstanding(int limit) {
		List<Debt> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT uuid, owner_name, COUNT(*) AS rows_owed, SUM(count) AS items_owed "
						+ "FROM pending_actions WHERE kind = 'DEBIT' "
						+ "GROUP BY uuid, owner_name ORDER BY items_owed DESC LIMIT ?")) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Debt(rs.getString("owner_name"),
							UUID.fromString(rs.getString("uuid")),
							rs.getInt("rows_owed"), rs.getInt("items_owed")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not list outstanding debts", e);
		}
		return out;
	}

	/**
	 * Writes off everything one player owes.
	 * <p>
	 * Only debits: a queued GIVE is somebody's property waiting to be handed back, and
	 * forgiving that would be losing it rather than excusing it.
	 *
	 * @return how many individual items were written off
	 */
	public int forgive(UUID owner) {
		Connection c = conn();
		if (c == null) return 0;

		int items = 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT SUM(count) FROM pending_actions WHERE uuid = ? AND kind = 'DEBIT'")) {
			ps.setString(1, owner.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) items = rs.getInt(1);
			}
		} catch (SQLException e) {
			return 0;
		}

		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM pending_actions WHERE uuid = ? AND kind = 'DEBIT'")) {
			ps.setString(1, owner.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not forgive a debt", e);
			return 0;
		}
		return items;
	}

	/** Drops a queued row without settling it — used when staff change their mind. */
	public boolean cancel(long id) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_actions WHERE id = ?")) {
			ps.setLong(1, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Pending] Could not cancel a queued action", e);
			return false;
		}
	}

	// -------------------------------------------------------------------- plumbing

	/** A delivered vault return retires its own row, so the two never disagree. */
	private void retireVaultRow(Connection c, long vaultId) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE contraband_vault SET state = 'RETURNED', resolved_at = ? "
						+ "WHERE id = ? AND state = 'PENDING_RETURN'")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setLong(2, vaultId);
			ps.executeUpdate();
		}
	}

	private void reduce(Connection c, long id, int remaining) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE pending_actions SET count = ? WHERE id = ?")) {
			ps.setInt(1, remaining);
			ps.setLong(2, id);
			ps.executeUpdate();
		}
	}

	private void deleteAll(Connection c, List<Long> ids) throws SQLException {
		if (ids.isEmpty()) return;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM pending_actions WHERE id = ?")) {
			for (long id : ids) {
				ps.setLong(1, id);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

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
			StaffCore.LOGGER.error("[Pending] Query failed", e);
		}
		return out;
	}

	private static Entry read(ResultSet rs) throws SQLException {
		Long refId = rs.getObject("ref_id") == null ? null : rs.getLong("ref_id");
		Kind kind;
		try {
			kind = Kind.valueOf(rs.getString("kind"));
		} catch (IllegalArgumentException e) {
			kind = Kind.GIVE;
		}
		return new Entry(
				rs.getLong("id"),
				UUID.fromString(rs.getString("uuid")),
				rs.getString("owner_name"),
				kind,
				rs.getString("item"),
				rs.getInt("count"),
				refId,
				rs.getString("reason"),
				rs.getString("queued_by"),
				rs.getLong("created_at"));
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
