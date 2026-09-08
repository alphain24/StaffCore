package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.cases.Versions;
import io.github.alphain24.staffcore.modules.identity.AddressPrivacy;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What one staff member did, drawn from the records that already exist.
 * <p>
 * Deliberately not a new table. Every action here is already written somewhere — punishments
 * to {@code punishments}, inventory changes to {@code inventory_audit}, case work to
 * {@code case_events}, everything else to {@code command_log} — and a parallel store would be
 * a second copy of the same facts that drifts from the first. When they disagreed, and they
 * would, there would be no way to tell which was lying.
 * <p>
 * So this is a read across four tables, merged by time.
 *
 * <h2>The staff address</h2>
 * Every command row records the acting staff member's own address. That is uncomfortable and
 * it is the point: the thing you cannot establish after the fact is which of two people
 * holding one account was at the keyboard, and a compromised staff account looks exactly like
 * a staff member behaving badly right up until you can see that the actions came from
 * somewhere they have never connected from before.
 * <p>
 * It is guarded three ways. Stored hashed through the same salt as every other address, so it
 * still compares against the connections table and is still not readable. Never returned by
 * {@link #forStaff}, which is what {@code /staff audit} calls. And reachable only through
 * {@link #addressesFor}, which is a separate method with a name that says what it does, behind
 * its own node.
 */
public final class StaffAudit {

	/** One thing a staff member did. */
	public record Entry(long at, String kind, String detail, String caseId) {

		/** {@code punishment}, {@code inventory}, {@code case} or {@code command}. */
		public boolean isCaseWork() {
			return caseId != null && !caseId.isBlank();
		}
	}

	private boolean ready() {
		return StaffCore.storage().isReady();
	}

	// -------------------------------------------------------------------- writing

	/**
	 * Records a staff command, with who and from where.
	 * <p>
	 * Replaces the old two-column write. The version columns matter for the same reason they
	 * do on a signal: an action taken under a build where a hook was silently broken means
	 * something different from the same action under a healthy one.
	 */
	public void record(ServerPlayer staff, String staffName, String command, String caseId) {
		record(io.github.alphain24.staffcore.permission.Actor.of(staff), staff, staffName,
				command, caseId);
	}

	/** As above, with the identity whose permissions authorised it. */
	public void record(io.github.alphain24.staffcore.permission.Actor actor, ServerPlayer staff,
			String staffName, String command, String caseId) {

		if (!ready()) return;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				INSERT INTO command_log (staff_name, command, created_at, staff_uuid, staff_ip,
				                         case_id, server_version, mod_version,
				                         actor_resolved_at)
				VALUES (?,?,?,?,?,?,?,?,?)
				""")) {
			ps.setString(1, staffName);
			ps.setString(2, command.length() > 256 ? command.substring(0, 256) : command);
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, staff == null ? null : staff.getUUID().toString());
			ps.setString(5, addressOf(staff));
			ps.setString(6, caseId);
			ps.setString(7, Versions.minecraft());
			ps.setString(8, Versions.mod());
			if (actor == null) ps.setNull(9, java.sql.Types.INTEGER);
			else ps.setLong(9, actor.resolvedAt());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Audit] could not record a staff command", e);
		}
	}

	/**
	 * The staff member's address, hashed the same way every other address is.
	 * <p>
	 * Hashing does not weaken this. The question an investigation asks is "did these actions
	 * come from where this person usually connects from", which is a comparison against the
	 * connections table — and equal addresses hash equal under the same salt. What goes is the
	 * ability to read somebody's home address out of a moderation log.
	 */
	private String addressOf(ServerPlayer staff) {
		if (staff == null || staff.connection == null) return null;
		try {
			String raw = staff.connection.getRemoteAddress().toString();
			int colon = raw.lastIndexOf(':');
			String host = (colon > 0 ? raw.substring(0, colon) : raw)
					.replace("/", "").replace("[", "").replace("]", "");
			return AddressPrivacy.store(StaffCore.storage().conn(), host);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// -------------------------------------------------------------------- reading

	/**
	 * Everything one staff member did, newest first.
	 * <p>
	 * <b>Never includes the address.</b> Not filtered out at the end — never selected, so
	 * there is no ordering of the code in which it leaks into a message, an export or a
	 * Discord embed by accident.
	 */
	public List<Entry> forStaff(String staffName, int days, int limit) {
		List<Entry> out = new ArrayList<>();
		if (!ready() || staffName == null) return out;

		long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;

		out.addAll(read("""
				SELECT created_at AS at, type AS kind,
				       ('punished ' || target_name || ' — ' || COALESCE(reason,'')) AS detail,
				       case_id
				FROM punishments WHERE staff_name = ? AND created_at >= ?
				""", staffName, since));

		out.addAll(read("""
				SELECT created_at AS at, ('inventory ' || direction) AS kind,
				       (origin || ' on ' || target_name || ' — ' || items) AS detail,
				       NULL AS case_id
				FROM inventory_audit WHERE actor = ? AND created_at >= ?
				""", staffName, since));

		out.addAll(read("""
				SELECT at, ('case ' || kind) AS kind, COALESCE(body,'') AS detail, case_id
				FROM case_events WHERE actor = ? AND at >= ?
				""", staffName, since));

		out.addAll(read("""
				SELECT created_at AS at, 'command' AS kind, command AS detail, case_id
				FROM command_log WHERE staff_name = ? AND created_at >= ?
				""", staffName, since));

		return out.stream()
				.sorted(Comparator.comparingLong(Entry::at).reversed())
				.limit(limit)
				.toList();
	}

	private List<Entry> read(String sql, String staffName, long since) {
		List<Entry> out = new ArrayList<>();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			ps.setString(1, staffName);
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Entry(rs.getLong("at"), rs.getString("kind"),
							rs.getString("detail"), rs.getString("case_id")));
				}
			}
		} catch (SQLException e) {
			// One unreadable source should cost that source, not the whole audit.
			StaffCore.LOGGER.warn("[Audit] a source could not be read: {}", e.getMessage());
		}
		return out;
	}

	/** How many actions, for the rate-limit message and the summary line. */
	public int countSince(String staffName, long since) {
		if (!ready()) return 0;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT COUNT(*) FROM command_log WHERE staff_name = ? AND created_at >= ?")) {
			ps.setString(1, staffName);
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	// ----------------------------------------------------------- the guarded half

	/** One address a staff member acted from, and how often. */
	public record Origin(String hashedAddress, int actions, long firstSeen, long lastSeen) {

		/** Short and comparable by eye. The full hash is not useful on screen. */
		public String display() {
			return AddressPrivacy.display(hashedAddress);
		}
	}

	/**
	 * The addresses a staff member acted from.
	 * <p>
	 * Separate from {@link #forStaff} on purpose, with a name that says what it returns, so
	 * calling it is a decision somebody made rather than a field that came along for the ride.
	 * The caller is responsible for the permission check — {@code Nodes.AUDIT_ADDRESSES} — and
	 * this is the only method in the class that will hand the value over.
	 * <p>
	 * What it is for: an account acting from an address it has never used before, in a week
	 * when that account did something out of character, is the difference between a staff
	 * member who has gone bad and a staff member whose account was taken.
	 */
	public List<Origin> addressesFor(String staffName, int days) {
		List<Origin> out = new ArrayList<>();
		if (!ready() || staffName == null) return out;

		long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT staff_ip, COUNT(*) AS actions, MIN(created_at) AS first_seen,
				       MAX(created_at) AS last_seen
				FROM command_log
				WHERE staff_name = ? AND created_at >= ? AND staff_ip IS NOT NULL
				GROUP BY staff_ip ORDER BY actions DESC
				""")) {
			ps.setString(1, staffName);
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Origin(rs.getString("staff_ip"), rs.getInt("actions"),
							rs.getLong("first_seen"), rs.getLong("last_seen")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Audit] could not read staff origins", e);
		}
		return out;
	}

	/** Which accounts have connected from a hash, so an origin can be tied to a person. */
	public List<String> accountsAt(String hashedAddress) {
		List<String> out = new ArrayList<>();
		if (!ready() || hashedAddress == null) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT DISTINCT name FROM connections WHERE ip = ? ORDER BY name")) {
			ps.setString(1, hashedAddress);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString(1));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Audit] could not resolve an origin", e);
		}
		return out;
	}

	/** Unused today; kept so a caller cannot reach for the raw UUID by accident. */
	public UUID uuidOf(String staffName) {
		if (!ready()) return null;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT staff_uuid FROM command_log WHERE staff_name = ? AND staff_uuid IS NOT NULL "
						+ "ORDER BY created_at DESC LIMIT 1")) {
			ps.setString(1, staffName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? UUID.fromString(rs.getString(1)) : null;
			}
		} catch (SQLException | IllegalArgumentException e) {
			return null;
		}
	}
}
