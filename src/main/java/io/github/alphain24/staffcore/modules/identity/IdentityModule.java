package io.github.alphain24.staffcore.modules.identity;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.util.NetAddress;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is who: connection history, alt detection, session and death logs.
 * <p>
 * Everything here is derived from data the server already has — the address a connection
 * came from, and the fact that a player joined, left or died. Nothing is inferred beyond
 * that, and nothing here punishes anybody. Two accounts sharing an address is evidence of
 * a shared house at least as often as it is evidence of an alt, so the module reports and
 * lets a human decide.
 */
public class IdentityModule implements Module {

	@Override
	public String id() {
		return "identity";
	}

	@Override
	public String displayName() {
		return "Identity";
	}

	private boolean lifecycleRegistered;

	/**
	 * Converts anything stored in the clear, then drops what is past the retention window.
	 * <p>
	 * At start rather than on a timer, for the same reason the snapshot purge is: an address
	 * a day past its window is not an emergency, and a job that runs while forty people are
	 * online is.
	 */
	@Override
	public void onEnable() {
		if (lifecycleRegistered) return;
		lifecycleRegistered = true;

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
				server -> {
					java.sql.Connection c = conn();
					if (c == null) return;
					AddressPrivacy.convertExisting(c);
					AddressPrivacy.purgeOlderThan(c, StaffConfig.get().connectionRetentionDays);
				});
	}

	/** How two accounts came to be linked, strongest first. */
	public enum Match {
		/** Both have connected from the exact same address. */
		EXACT_IP,
		/** Their addresses sit in the same range — the same house after a lease renewal. */
		SUBNET
	}

	/**
	 * Another account that looks like it might be the same person.
	 *
	 * @param confidence 0-100. A lead, not a verdict: a shared address is evidence of a
	 *                   shared house at least as often as it is evidence of an alt, and no
	 *                   number this produces changes that.
	 * @param reasons    what produced the score, in the order they were weighed
	 */
	public record Alt(UUID uuid, String name, String sharedIp, long lastSeen, boolean banned,
			Match match, int confidence, List<String> reasons) {

		/** Only an exact address match is ever strong enough to act on automatically. */
		public boolean isStrong() {
			return match == Match.EXACT_IP;
		}

		public String headline() {
			return confidence + "% — " + (reasons.isEmpty() ? "shares an address" : reasons.get(0));
		}
	}

	public record Session(String action, String ip, long at) {}

	public record Death(String cause, String killer, String world, int x, int y, int z, long at) {}

	// ------------------------------------------------------------------ recording

	public void recordJoin(ServerPlayer player) {
		String ip = addressOf(player);
		refreshStoredName(player.getUUID(), Mc.name(player));
		touchConnection(player.getUUID(), Mc.name(player), ip);
		logSession(player.getUUID(), Mc.name(player), "JOIN", ip);
	}

	/**
	 * Tables that keep a name beside a UUID, and the column holding it.
	 * <p>
	 * Every record here is keyed by UUID, which is the correct thing to key on because names
	 * change. The name is stored next to it anyway so a row is readable without a lookup —
	 * and that copy is what goes stale. Historical rows deliberately keep their own copies
	 * ({@code staff_name} on a punishment is who issued it under the name they had at the
	 * time), so only the subject's name is refreshed.
	 */
	private static final String[][] NAME_COLUMNS = {
			{"punishments", "target_uuid", "target_name"},
			{"reports", "target_uuid", "target_name"},
			{"reports", "reporter_uuid", "reporter_name"},
			{"appeals", "target_uuid", "target_name"},
			{"contraband_vault", "owner_uuid", "owner_name"},
			{"pending_actions", "uuid", "owner_name"},
			{"connections", "uuid", "name"},
	};

	/**
	 * Brings a renamed player's records up to date.
	 * <p>
	 * Without this, someone who changes their name disappears from every screen that searches
	 * by name and shows up under a name that is no longer theirs on every screen that does
	 * not. Staff then look up the new name, find nothing, and conclude the account is clean —
	 * which is exactly the outcome a rename is bought for.
	 * <p>
	 * Runs on join, and only writes when the stored name actually differs, so the normal case
	 * is one indexed read and nothing else.
	 */
	public void refreshStoredName(UUID uuid, String name) {
		Connection c = conn();
		if (c == null || name == null || name.isBlank()) return;

		String previous = null;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT name FROM connections WHERE uuid = ? LIMIT 1")) {
			ps.setString(1, uuid.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) previous = rs.getString("name");
			}
		} catch (SQLException e) {
			return;
		}

		// Never seen before, or unchanged — nothing to rewrite.
		if (previous == null || previous.equals(name)) return;

		final String was = previous;
		boolean ok = StaffCore.storage().inTransaction(conn -> {
			for (String[] target : NAME_COLUMNS) {
				try (PreparedStatement ps = conn.prepareStatement(
						"UPDATE " + target[0] + " SET " + target[2] + " = ? "
								+ "WHERE " + target[1] + " = ? AND " + target[2] + " <> ?")) {
					ps.setString(1, name);
					ps.setString(2, uuid.toString());
					ps.setString(3, name);
					ps.executeUpdate();
				}
			}
		});

		if (ok) {
			StaffCore.LOGGER.info("[Identity] {} was {} - records updated to the new name",
					name, was);
		}
	}

	public void recordLeave(ServerPlayer player) {
		logSession(player.getUUID(), Mc.name(player), "LEAVE", addressOf(player));
	}

	public void recordDeath(ServerPlayer player, String cause, String killer) {
		Connection c = conn();
		if (c == null) return;

		String sql = """
				INSERT INTO death_log (uuid, name, cause, killer, world, x, y, z, created_at)
				VALUES (?,?,?,?,?,?,?,?,?)
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, player.getUUID().toString());
			ps.setString(2, Mc.name(player));
			ps.setString(3, cause);
			ps.setString(4, killer);
			ps.setString(5, Mc.dimensionId(player.level()));
			ps.setInt(6, player.getBlockX());
			ps.setInt(7, player.getBlockY());
			ps.setInt(8, player.getBlockZ());
			ps.setLong(9, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] death log failed", e);
		}
	}

	/**
	 * Strips the port and any IPv6 brackets. Ports change every reconnect, so keeping them
	 * would make every session look like a different address and defeat the whole table.
	 */
	private static String addressOf(ServerPlayer player) {
		String raw = player.getIpAddress();
		if (raw == null) return "unknown";
		raw = raw.replace("/", "");
		int slash = raw.lastIndexOf(':');
		if (slash > 0 && raw.indexOf(':') == slash) {
			raw = raw.substring(0, slash);   // IPv4 with a port
		}
		return raw.replace("[", "").replace("]", "");
	}

	private void touchConnection(UUID uuid, String name, String ip) {
		Connection c = conn();
		if (c == null) return;

		long now = System.currentTimeMillis();
		try (PreparedStatement ps = c.prepareStatement("""
				INSERT INTO connections (uuid, name, ip, ip_prefix, first_seen, last_seen, joins)
				VALUES (?,?,?,?,?,?,1)
				ON CONFLICT(uuid, ip) DO UPDATE SET
				    name = excluded.name,
				    ip_prefix = excluded.ip_prefix,
				    last_seen = excluded.last_seen,
				    joins = joins + 1
				""")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.setString(3, AddressPrivacy.store(c, ip));
			ps.setString(4, AddressPrivacy.storePrefix(c, ip));
			ps.setLong(5, now);
			ps.setLong(6, now);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] connection write failed", e);
		}
	}

	private void logSession(UUID uuid, String name, String action, String ip) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO session_log (uuid, name, action, ip, created_at) VALUES (?,?,?,?,?)")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, name);
			ps.setString(3, action);
			ps.setString(4, AddressPrivacy.store(c, ip));
			ps.setLong(5, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] session log failed", e);
		}
	}

	// -------------------------------------------------------------------- reading

	/**
	 * Other accounts that look like they might be the same person, strongest first.
	 * <p>
	 * Two passes rather than one. The first is an exact address match, which is what this
	 * always did. The second is a range match, which catches the case an exact match cannot
	 * see at all: a home address that moved within its provider's block between one session
	 * and the next, and so stopped matching itself. That is not an exotic scenario — it is
	 * what happens when a router reboots.
	 * <p>
	 * A range match is much weaker evidence and scores much lower for it, but a weak lead
	 * that is visible beats a strong one that never fires. An account found both ways is
	 * reported once, on the stronger of the two.
	 */
	public List<Alt> altsOf(UUID player) {
		Connection c = conn();
		if (c == null) return List.of();

		// Read once, not once per candidate. This runs on the join path — every player who
		// logs in is screened for evasion — and the subject's own sessions are the same
		// however many accounts turn out to share their address.
		List<long[]> mine = sessionSpans(player, System.currentTimeMillis() - OVERLAP_WINDOW_MS);

		Map<UUID, Alt> found = new LinkedHashMap<>();
		collect(c, player, mine, Match.EXACT_IP, found);

		if (StaffConfig.get().altSubnetMatching) {
			collect(c, player, mine, Match.SUBNET, found);
		}

		return found.values().stream()
				.sorted(Comparator.comparing(Alt::banned).reversed()
						.thenComparing(Comparator.comparingInt(Alt::confidence).reversed())
						.thenComparing(Comparator.comparingLong(Alt::lastSeen).reversed()))
				.toList();
	}

	/** Runs one matching pass, keeping whichever match for an account is already stronger. */
	private void collect(Connection c, UUID player, List<long[]> mine, Match match,
			Map<UUID, Alt> found) {
		// Range matching deliberately excludes exact-address pairs so the two passes cannot
		// both claim the same link and disagree about how strong it is.
		String join = match == Match.EXACT_IP
				? "other.ip = mine.ip"
				: "other.ip_prefix = mine.ip_prefix AND other.ip <> mine.ip "
						+ "AND mine.ip_prefix IS NOT NULL";

		String sql = """
				SELECT other.uuid, other.name, other.ip,
				       MAX(other.last_seen) AS last_seen, MIN(other.first_seen) AS first_seen,
				       SUM(other.joins) AS joins,
				       EXISTS (SELECT 1 FROM punishments p
				               WHERE p.target_uuid = other.uuid AND p.active = 1
				                 AND p.type IN ('BAN','TEMPBAN')) AS banned
				FROM connections mine
				JOIN connections other ON %s AND other.uuid <> mine.uuid
				WHERE mine.uuid = ?
				GROUP BY other.uuid
				""".formatted(join);

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					UUID other = UUID.fromString(rs.getString("uuid"));

					// A stronger pass already claimed this account.
					Alt existing = found.get(other);
					if (existing != null && existing.match().ordinal() <= match.ordinal()) continue;

					found.put(other, score(rs, mine, other, match));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] alt lookup failed", e);
		}
	}

	/**
	 * Turns a match into a confidence with its reasoning attached.
	 * <p>
	 * The point of a score is not precision — there is none available here — it is ordering.
	 * Staff opening this screen want to know which of nine linked accounts to read first,
	 * and "shares an exact address, and has never been seen at the same time as you" is a
	 * different proposition from "was once in the same city block".
	 */
	private Alt score(ResultSet rs, List<long[]> mine, UUID other, Match match) throws SQLException {
		List<String> reasons = new ArrayList<>();
		int confidence;

		if (match == Match.EXACT_IP) {
			confidence = 60;
			reasons.add("shares an exact address");
		} else {
			confidence = 25;
			reasons.add("addresses are in the same range");
		}

		// Repeated use of the shared address is worth more than one stray connection, which
		// is as likely to be a guest on somebody's wifi as anything else.
		int joins = rs.getInt("joins");
		if (joins >= 20) {
			confidence += 15;
			reasons.add("connected " + joins + " times from it");
		} else if (joins >= 5) {
			confidence += 8;
			reasons.add("connected " + joins + " times from it");
		} else if (joins <= 1) {
			confidence -= 10;
			reasons.add("only ever connected once from it");
		}

		// Two accounts that are never online together read as one person switching between
		// them. Two that overlap read as two people in one house — the far more ordinary
		// explanation, and the one worth arguing against the score.
		if (neverOverlapped(mine, other)) {
			confidence += 15;
			reasons.add("never online at the same time");
		} else {
			confidence -= 20;
			reasons.add("has been online at the same time — likely a different person");
		}

		if (rs.getInt("banned") == 1) {
			reasons.add("that account is banned");
		}

		return new Alt(other, rs.getString("name"), rs.getString("ip"),
				rs.getLong("last_seen"), rs.getInt("banned") == 1,
				match, Math.clamp(confidence, 0, 100), List.copyOf(reasons));
	}

	/** How far back the overlap check reads. Beyond this the sessions say little. */
	private static final long OVERLAP_WINDOW_MS = 90L * 86_400_000L;

	/**
	 * True when the two accounts have never been logged in at the same moment.
	 * <p>
	 * This is the signal that separates the two ordinary explanations for a shared address.
	 * One person switching between accounts can never be online twice at once; two siblings
	 * routinely are. Neither is proof, but they point in opposite directions, and a screen
	 * that only says "same address" cannot tell staff which one they are looking at.
	 * <p>
	 * Absent evidence counts as no overlap rather than as overlap — an account with no
	 * session history should not be scored as though it were cleared.
	 */
	private boolean neverOverlapped(List<long[]> mine, UUID other) {
		if (mine.isEmpty()) return true;

		List<long[]> theirs = sessionSpans(other, System.currentTimeMillis() - OVERLAP_WINDOW_MS);
		if (theirs.isEmpty()) return true;

		for (long[] a : mine) {
			for (long[] b : theirs) {
				if (a[0] < b[1] && b[0] < a[1]) return false;
			}
		}
		return true;
	}

	/**
	 * Join/leave pairs for one account inside the window.
	 * <p>
	 * Read as a sequence rather than reconstructed in SQL. A JOIN with no matching LEAVE —
	 * a crash, a restart, a session still open — is closed at the present moment instead of
	 * being dropped, so a player who is online right now still counts as online.
	 */
	private List<long[]> sessionSpans(UUID who, long since) {
		List<long[]> spans = new ArrayList<>();
		Connection c = conn();
		if (c == null) return spans;

		String sql = """
				SELECT action, created_at FROM session_log
				WHERE uuid = ? AND created_at >= ?
				ORDER BY created_at ASC LIMIT 2000
				""";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, who.toString());
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				long openedAt = -1;
				while (rs.next()) {
					if ("JOIN".equals(rs.getString("action"))) {
						openedAt = rs.getLong("created_at");
					} else if (openedAt > 0) {
						spans.add(new long[] { openedAt, rs.getLong("created_at") });
						openedAt = -1;
					}
				}
				if (openedAt > 0) spans.add(new long[] { openedAt, System.currentTimeMillis() });
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] session span lookup failed", e);
		}
		return spans;
	}

	/** Banned accounts linked to this one — the evasion signal. */
	public List<Alt> bannedAlts(UUID player) {
		return altsOf(player).stream().filter(Alt::banned).toList();
	}

	public List<Session> sessions(UUID player, int limit) {
		List<Session> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT action, ip, created_at FROM session_log WHERE uuid=? "
						+ "ORDER BY created_at DESC LIMIT ?")) {
			ps.setString(1, player.toString());
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Session(rs.getString("action"), rs.getString("ip"), rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] session lookup failed", e);
		}
		return out;
	}

	public List<Death> deaths(UUID player, int limit) {
		List<Death> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM death_log WHERE uuid=? ORDER BY created_at DESC LIMIT ?")) {
			ps.setString(1, player.toString());
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Death(
							rs.getString("cause"), rs.getString("killer"), rs.getString("world"),
							rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] death lookup failed", e);
		}
		return out;
	}

	public int altCount(UUID player) {
		return altsOf(player).size();
	}

	public String lastAddress(UUID player) {
		Connection c = conn();
		if (c == null) return null;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT ip FROM connections WHERE uuid=? ORDER BY last_seen DESC LIMIT 1")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString("ip") : null;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
