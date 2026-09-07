package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who else was online when something was recorded.
 * <p>
 * Recording needs a live server and is covered by the gametests. What is checked here is the
 * half that decides whether the record is any use afterwards: that an empty list is stored and
 * read back as "nobody was on" rather than as "nothing was recorded", and that the wording
 * cannot be mistaken for a claim that these people saw anything.
 */
class WitnessesTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void witnessed(String kind, String ref, String names, int present)
			throws SQLException {

		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO incident_witness (kind, ref, at, world, names, present) "
						+ "VALUES (?,?,?,'overworld',?,?)")) {
			ps.setString(1, kind);
			ps.setString(2, ref);
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, names);
			ps.setInt(5, present);
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("a list of names reads back as those names")
	void namesRoundTrip() throws SQLException {
		witnessed("punishment", "42", "Alice,Bob,Carol", 3);

		var record = Witnesses.forIncident(Witnesses.Kind.PUNISHMENT, "42");
		assertNotNull(record);
		assertEquals(List.of("Alice", "Bob", "Carol"), record.names());
		assertEquals(3, record.present());
		assertFalse(record.alone());
	}

	@Test
	@DisplayName("nobody else being on is a finding, not a missing record")
	void emptyIsNotAbsent() {
		// The distinction the whole thing turns on. "Nobody could have seen it" is evidence;
		// "we did not write it down" is a gap. Collapsing them makes both useless.
		assertNull(Witnesses.forIncident(Witnesses.Kind.PUNISHMENT, "999"),
				"an incident with no row should read as no record");
		assertTrue(Witnesses.describe(null).contains("Nobody recorded"));
	}

	@Test
	@DisplayName("an empty name list is stored and read as alone")
	void aloneIsStored() throws SQLException {
		witnessed("punishment", "7", "", 0);

		var record = Witnesses.forIncident(Witnesses.Kind.PUNISHMENT, "7");
		assertNotNull(record, "an empty list is still a record");
		assertTrue(record.alone());
		assertTrue(Witnesses.describe(record).contains("nobody to ask"));
	}

	@Test
	@DisplayName("the wording cannot be read as a claim that anybody saw anything")
	void connectedIsNotWatching() {
		// Somebody alone in the nether is a witness to nothing. The list is people worth
		// asking, and if the sentence overstates that, staff will act on it as though it did
		// not — which is how a list of names becomes an accusation.
		var record = new Witnesses.Record(System.currentTimeMillis(), "overworld",
				List.of("Alice", "Bob"), 2);

		String described = Witnesses.describe(record);
		assertTrue(described.contains("Connected, not necessarily watching"),
				"the qualification is load-bearing and is missing: " + described);
		assertTrue(described.contains("Alice") && described.contains("Bob"));
	}

	@Test
	@DisplayName("the newest record wins when an incident has more than one")
	void latestFirst() throws SQLException, InterruptedException {
		witnessed("signal", "5", "Old", 1);
		// Two rows written in the same millisecond are ordered by nothing, and a test that
		// passes by coincidence is worse than no test.
		Thread.sleep(2);
		witnessed("signal", "5", "New", 1);

		assertEquals(List.of("New"), Witnesses.forIncident(Witnesses.Kind.SIGNAL, "5").names());
	}

	@Test
	@DisplayName("kinds do not collide, so signal 1 is not punishment 1")
	void kindsAreSeparate() throws SQLException {
		witnessed("punishment", "1", "FromPunishment", 1);
		witnessed("signal", "1", "FromSignal", 1);

		assertEquals(List.of("FromPunishment"),
				Witnesses.forIncident(Witnesses.Kind.PUNISHMENT, "1").names());
		assertEquals(List.of("FromSignal"),
				Witnesses.forIncident(Witnesses.Kind.SIGNAL, "1").names());
	}
}
