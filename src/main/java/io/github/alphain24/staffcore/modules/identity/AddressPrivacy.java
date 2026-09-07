package io.github.alphain24.staffcore.modules.identity;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.util.NetAddress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/**
 * Stores addresses as one-way hashes, so alt detection keeps working and the addresses stop
 * being readable.
 * <p>
 * An IP address is the only personal data this mod holds. Everything else it records is about
 * conduct on the server — punishments, blocks broken, items moved — and belongs to the server
 * in a way somebody's home address does not. It was also the one category with no retention
 * limit while grief logs, snapshots and debts all had one, and {@code /staff export} wrote all
 * of it to a CSV anybody could open.
 * <p>
 * <b>Why hashing does not cost anything here.</b> Nothing in this mod needs to read an
 * address; it needs to know whether two accounts used the <em>same</em> one. That is an
 * equality test, and equality survives hashing exactly. Both matching passes still work —
 * exact matching compares the address hash, and range matching compares a separately hashed
 * /24 prefix, so two accounts on the same block still link without either block being
 * legible.
 * <p>
 * <b>The salt matters.</b> IPv4 is 32 bits: an unsalted hash of every possible address can be
 * built in seconds, so an unsalted digest is not a hash of an address, it is an encoding of
 * one. The salt is generated once per server, stored beside the data, and never leaves it.
 * That is enough to stop a leaked database being reversed offline, and it is honestly all it
 * is: somebody with the database <em>and</em> the salt can still test a guess.
 */
public final class AddressPrivacy {
	private AddressPrivacy() {}

	/** Marks a stored value as already hashed, so a mixed table can be read safely. */
	private static final String PREFIX = "h$";

	private static String salt;

	/** True when addresses should be written and matched as hashes. */
	public static boolean enabled() {
		return StaffConfig.get().hashConnectionAddresses;
	}

	/**
	 * The per-server salt, created on first use and kept in the database.
	 * <p>
	 * In the database rather than the config file because it is not a setting: nobody should
	 * edit it, and changing it silently unlinks every account from every other by making
	 * yesterday's hashes match nothing.
	 */
	public static synchronized String salt(Connection conn) {
		if (salt != null) return salt;

		try (Statement st = conn.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS staffcore_meta ("
					+ "key TEXT PRIMARY KEY, value TEXT NOT NULL)");

			try (ResultSet rs = st.executeQuery(
					"SELECT value FROM staffcore_meta WHERE key = 'address_salt'")) {
				if (rs.next()) {
					salt = rs.getString(1);
					return salt;
				}
			}

			byte[] fresh = new byte[32];
			new SecureRandom().nextBytes(fresh);
			salt = HexFormat.of().formatHex(fresh);

			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT INTO staffcore_meta (key, value) VALUES ('address_salt', ?)")) {
				ps.setString(1, salt);
				ps.executeUpdate();
			}
			StaffCore.LOGGER.info("[Identity] Generated an address salt. Addresses are stored "
					+ "hashed; alt matching is unaffected.");
			return salt;
		} catch (SQLException e) {
			// Without a salt, hashing would be reversible in seconds. Refusing to hash is
			// the wrong answer too, so this fails loudly rather than quietly downgrading.
			throw new IllegalStateException("Could not read or create the address salt", e);
		}
	}

	/** Forgets the cached salt. Only for tests, which open several databases in a row. */
	public static synchronized void forgetSalt() {
		salt = null;
	}

	/**
	 * The stored form of an address.
	 * <p>
	 * Already-hashed input passes through unchanged, so this is safe to apply twice and safe
	 * on a table that is halfway through being converted.
	 */
	public static String store(Connection conn, String address) {
		if (address == null || address.isBlank()) return address;
		if (!enabled() || address.startsWith(PREFIX)) return address;
		return PREFIX + digest(salt(conn), address);
	}

	/** The stored form of an address's /24 (or /48) prefix, for range matching. */
	public static String storePrefix(Connection conn, String address) {
		String prefix = NetAddress.prefix(address);
		if (prefix == null || prefix.isBlank()) return prefix;
		if (!enabled() || prefix.startsWith(PREFIX)) return prefix;
		return PREFIX + digest(salt(conn), prefix);
	}

	/** True when a stored value is a hash rather than an address. */
	public static boolean isHashed(String stored) {
		return stored != null && stored.startsWith(PREFIX);
	}

	/**
	 * What to show a staff member.
	 * <p>
	 * A hash is not useful on screen and pretending otherwise wastes the line, so it is
	 * shortened to something that can still be compared between two rows by eye — which is
	 * the only thing anybody was doing with the full address anyway.
	 */
	public static String display(String stored) {
		if (stored == null) return "unknown";
		if (!isHashed(stored)) return stored;
		String body = stored.substring(PREFIX.length());
		return "#" + body.substring(0, Math.min(8, body.length()));
	}

	private static String digest(String salt, String value) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(salt.getBytes(StandardCharsets.UTF_8));
			sha.update(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(sha.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required and is missing", e);
		}
	}

	/**
	 * Converts any plaintext addresses already on disk.
	 * <p>
	 * Run at boot when hashing is on. Without it a server that switches hashing on gets a
	 * table half in each form, and two accounts that share an address stop matching because
	 * one row was written before the change and one after — alt detection would quietly get
	 * worse and nothing would say why.
	 *
	 * @return how many rows were converted
	 */
	public static int convertExisting(Connection conn) {
		if (!enabled()) return 0;

		int converted = 0;
		try {
			converted += convertConnections(conn);
			converted += convertSessions(conn);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] Could not convert stored addresses", e);
			return converted;
		}

		if (converted > 0) {
			StaffCore.LOGGER.info("[Identity] Hashed {} stored address(es). Alt matching is "
					+ "unaffected; the plaintext is gone.", converted);
		}
		return converted;
	}

	private static int convertConnections(Connection conn) throws SQLException {
		record Row(String uuid, String ip, String hashed, String hashedPrefix) {}
		java.util.List<Row> pending = new java.util.ArrayList<>();

		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT uuid, ip FROM connections WHERE ip NOT LIKE '" + PREFIX + "%'")) {
			while (rs.next()) {
				String ip = rs.getString("ip");
				pending.add(new Row(rs.getString("uuid"), ip,
						PREFIX + digest(salt(conn), ip),
						NetAddress.prefix(ip) == null ? null
								: PREFIX + digest(salt(conn), NetAddress.prefix(ip))));
			}
		}
		if (pending.isEmpty()) return 0;

		// The primary key is (uuid, ip), so two plaintext rows can collapse onto one hashed
		// row. Merging rather than failing keeps the join counts honest.
		int done = 0;
		for (Row row : pending) {
			try (PreparedStatement ps = conn.prepareStatement("""
					UPDATE OR REPLACE connections SET ip = ?, ip_prefix = ?
					WHERE uuid = ? AND ip = ?
					""")) {
				ps.setString(1, row.hashed());
				ps.setString(2, row.hashedPrefix());
				ps.setString(3, row.uuid());
				ps.setString(4, row.ip());
				done += ps.executeUpdate();
			}
		}
		return done;
	}

	private static int convertSessions(Connection conn) throws SQLException {
		java.util.Map<Long, String> pending = new java.util.LinkedHashMap<>();
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT id, ip FROM session_log "
						+ "WHERE ip IS NOT NULL AND ip NOT LIKE '" + PREFIX + "%'")) {
			while (rs.next()) {
				pending.put(rs.getLong("id"), PREFIX + digest(salt(conn), rs.getString("ip")));
			}
		}
		if (pending.isEmpty()) return 0;

		int done = 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE session_log SET ip = ? WHERE id = ?")) {
			for (var entry : pending.entrySet()) {
				ps.setString(1, entry.getValue());
				ps.setLong(2, entry.getKey());
				done += ps.executeUpdate();
			}
		}
		return done;
	}

	/**
	 * Deletes connection and session rows older than the retention window.
	 * <p>
	 * The only category of personal data here, and it was the only one without a limit while
	 * grief logs, snapshots, anti-cheat findings and debts all had one.
	 *
	 * @param days 0 keeps everything, matching every other retention knob
	 * @return rows removed
	 */
	public static int purgeOlderThan(Connection conn, int days) {
		if (days <= 0) return 0;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		int removed = 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM connections WHERE last_seen < ?")) {
			ps.setLong(1, cutoff);
			removed += ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] Could not purge old connections", e);
		}
		try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM session_log WHERE created_at < ?")) {
			ps.setLong(1, cutoff);
			removed += ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Identity] Could not purge old sessions", e);
		}

		if (removed > 0) {
			StaffCore.LOGGER.info("[Identity] Removed {} connection record(s) older than {} days.",
					removed, days);
		}
		return removed;
	}
}
