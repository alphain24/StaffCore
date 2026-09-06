package io.github.alphain24.staffcore.modules.identity;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hashing addresses without breaking the thing addresses are for.
 * <p>
 * The whole case for this rests on one property: nothing in the mod needs to <em>read</em> an
 * address, only to know whether two accounts used the same one. That is an equality test, and
 * equality survives hashing exactly — so if these tests pass, privacy here costs nothing.
 * If the matching tests fail, the feature is worse than useless: it would look like it was
 * protecting people while quietly making alt detection stop working.
 */
class AddressPrivacyTest {

	@TempDir
	Path world;

	private Storage storage;

	private void open() {
		AddressPrivacy.forgetSalt();
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
		StaffConfig.get().hashConnectionAddresses = true;
		AddressPrivacy.forgetSalt();
	}

	private void connect(String uuid, String name, String ip) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO connections (uuid, name, ip, ip_prefix, first_seen, last_seen, joins)
				VALUES (?,?,?,?,?,?,1)
				ON CONFLICT(uuid, ip) DO UPDATE SET last_seen = excluded.last_seen
				""")) {
			ps.setString(1, uuid);
			ps.setString(2, name);
			ps.setString(3, AddressPrivacy.store(storage.conn(), ip));
			ps.setString(4, AddressPrivacy.storePrefix(storage.conn(), ip));
			ps.setLong(5, System.currentTimeMillis());
			ps.setLong(6, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	/** How many accounts share an address with this one, the way altsOf asks it. */
	private int sharesAddressWith(String uuid) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement("""
				SELECT COUNT(DISTINCT other.uuid) FROM connections mine
				JOIN connections other ON other.ip = mine.ip AND other.uuid <> mine.uuid
				WHERE mine.uuid = ?
				""")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	private int sharesRangeWith(String uuid) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement("""
				SELECT COUNT(DISTINCT other.uuid) FROM connections mine
				JOIN connections other ON other.ip_prefix = mine.ip_prefix
				     AND other.ip <> mine.ip AND mine.ip_prefix IS NOT NULL
				WHERE mine.uuid = ?
				""")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	@Test
	@DisplayName("exact matching still links two accounts on the same address")
	void exactMatchingSurvivesHashing() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = true;
		open();

		connect("uuid-a", "Alice", "203.0.113.7");
		connect("uuid-b", "Bob", "203.0.113.7");
		connect("uuid-c", "Carol", "198.51.100.9");

		assertEquals(1, sharesAddressWith("uuid-a"),
				"if this fails the feature is worse than useless: it would look like privacy "
						+ "while silently switching alt detection off");
		assertEquals(0, sharesAddressWith("uuid-c"));
	}

	@Test
	@DisplayName("range matching still links two accounts in the same block")
	void subnetMatchingSurvivesHashing() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = true;
		open();

		// The case exact matching cannot see: a home address that moved within its
		// provider's block when the router rebooted.
		connect("uuid-a", "Alice", "203.0.113.7");
		connect("uuid-b", "Bob", "203.0.113.40");

		assertEquals(0, sharesAddressWith("uuid-a"), "different addresses");
		assertEquals(1, sharesRangeWith("uuid-a"),
				"the prefix has to be hashed separately, or range matching dies with the "
						+ "plaintext");
	}

	@Test
	@DisplayName("what lands on disk is not the address")
	void theStoredValueIsNotReadable() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = true;
		open();
		connect("uuid-a", "Alice", "203.0.113.7");

		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT ip, ip_prefix FROM connections")) {
			assertTrue(rs.next());
			String stored = rs.getString("ip");
			assertNotEquals("203.0.113.7", stored);
			assertFalse(stored.contains("203.0.113"),
					"a hash that still contains the address is not a hash");
			assertTrue(AddressPrivacy.isHashed(stored));
			assertTrue(AddressPrivacy.isHashed(rs.getString("ip_prefix")));
		}
	}

	@Test
	@DisplayName("the salt is per-server, so one rainbow table does not open every database")
	void hashesAreSalted() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = true;
		open();
		String here = AddressPrivacy.store(storage.conn(), "203.0.113.7");
		String salt = AddressPrivacy.salt(storage.conn());

		// IPv4 is 32 bits. An unsalted digest of every possible address can be built in
		// seconds, which would make this an encoding of an address rather than a hash of one.
		assertTrue(salt.length() >= 32, "a short salt is barely a salt");
		assertNotEquals(here, "h$" + salt, "sanity: the hash is not just the salt");

		// Same address, same database, same answer — or nothing would ever match.
		assertEquals(here, AddressPrivacy.store(storage.conn(), "203.0.113.7"));
	}

	@Test
	@DisplayName("hashing twice is a no-op, so a half-converted table is safe")
	void hashingIsIdempotent() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = true;
		open();

		String once = AddressPrivacy.store(storage.conn(), "203.0.113.7");
		String twice = AddressPrivacy.store(storage.conn(), once);
		assertEquals(once, twice,
				"re-hashing a hash would make a row stop matching the rows around it");
	}

	@Test
	@DisplayName("switching hashing on converts what is already stored")
	void existingRowsAreConverted() throws SQLException {
		StaffConfig.get().hashConnectionAddresses = false;
		open();

		connect("uuid-a", "Alice", "203.0.113.7");
		connect("uuid-b", "Bob", "203.0.113.7");
		assertEquals(1, sharesAddressWith("uuid-a"), "they match in the clear");

		// Without conversion the table ends up half in each form, and two accounts sharing an
		// address stop matching because one row predates the change — alt detection would get
		// quietly worse and nothing would say why.
		StaffConfig.get().hashConnectionAddresses = true;
		int converted = AddressPrivacy.convertExisting(storage.conn());

		assertTrue(converted >= 2, "expected both rows converted, got " + converted);
		assertEquals(1, sharesAddressWith("uuid-a"), "and they still match afterwards");

		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT ip FROM connections")) {
			while (rs.next()) {
				assertTrue(AddressPrivacy.isHashed(rs.getString("ip")),
						"a row was left in the clear");
			}
		}
	}

	@Test
	@DisplayName("retention deletes what is past the window and keeps the rest")
	void retentionDropsOldRecords() throws SQLException {
		open();
		long now = System.currentTimeMillis();

		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO connections (uuid, name, ip, ip_prefix, first_seen, last_seen, joins)
				VALUES (?,?,?,?,?,?,1)
				""")) {
			for (String[] row : new String[][] {
					{ "old", String.valueOf(now - 120L * 86_400_000L) },
					{ "recent", String.valueOf(now - 3L * 86_400_000L) } }) {
				ps.setString(1, row[0]);
				ps.setString(2, row[0]);
				ps.setString(3, "ip-" + row[0]);
				ps.setString(4, "prefix");
				ps.setLong(5, Long.parseLong(row[1]));
				ps.setLong(6, Long.parseLong(row[1]));
				ps.executeUpdate();
			}
		}

		assertEquals(1, AddressPrivacy.purgeOlderThan(storage.conn(), 90));

		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT uuid FROM connections")) {
			assertTrue(rs.next());
			assertEquals("recent", rs.getString(1));
			assertFalse(rs.next(), "only the one past the window should have gone");
		}
	}

	@Test
	@DisplayName("0 days keeps everything, like every other retention knob")
	void zeroKeepsEverything() throws SQLException {
		open();
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("INSERT INTO connections (uuid, name, ip, first_seen, last_seen, joins) "
					+ "VALUES ('ancient','ancient','ip',0,0,1)");
		}
		assertEquals(0, AddressPrivacy.purgeOlderThan(storage.conn(), 0),
				"0 has to mean forever here too, or the knob reads differently from the others");
	}
}
