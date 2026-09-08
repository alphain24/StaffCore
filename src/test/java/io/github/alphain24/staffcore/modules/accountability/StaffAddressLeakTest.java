package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.identity.AddressPrivacy;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff address does not leave by any of the doors.
 * <p>
 * Recording where a staff member acted from is the sharpest thing this mod stores. It exists
 * for one question — was this account compromised, or has this person gone bad — and that
 * question is worth being able to answer. It is also a colleague's home address sitting in a
 * moderation database, and the difference between those two readings is entirely about who
 * can reach it.
 * <p>
 * Gate 2 asks for a test that it is absent from non-admin output, from exports, and from
 * anything Discord can read. This is that test, and it checks the three doors separately
 * because they fail separately: a field can be filtered out of a command and still be in the
 * CSV, or absent from both and still handed to an embed builder.
 */
class StaffAddressLeakTest {

	@TempDir
	Path world;

	private Storage storage;
	private StaffAudit audit;

	private static final String STAFF = "Alice";
	private static final String ADDRESS = "203.0.113.77";

	@BeforeEach
	void open() throws SQLException {
		AddressPrivacy.forgetSalt();
		storage = StaffCore.storage();
		storage.open(world);
		audit = new StaffAudit();
		StaffConfig.get().hashConnectionAddresses = true;

		try (PreparedStatement ps = storage.conn().prepareStatement("""
				INSERT INTO command_log (staff_name, command, created_at, staff_uuid, staff_ip)
				VALUES (?,?,?,?,?)
				""")) {
			ps.setString(1, STAFF);
			ps.setString(2, "/staff ban Bob cheating");
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, java.util.UUID.randomUUID().toString());
			ps.setString(5, AddressPrivacy.store(storage.conn(), ADDRESS));
			ps.executeUpdate();
		}
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
		AddressPrivacy.forgetSalt();
	}

	// ------------------------------------------------------------------ door one

	@Test
	@DisplayName("the ordinary audit read never returns the address")
	void forStaffDoesNotCarryIt() {
		List<StaffAudit.Entry> entries = audit.forStaff(STAFF, 30, 50);
		assertFalse(entries.isEmpty(), "the fixture did not land, so this proves nothing");

		// Not filtered at the end — never selected. That distinction is the point: there is
		// no ordering of the code in which a later change accidentally prints a field that
		// was never fetched.
		for (StaffAudit.Entry entry : entries) {
			assertFalse(String.valueOf(entry).contains(ADDRESS), "plaintext address in an entry");
			assertFalse(String.valueOf(entry).contains("staff_ip"), "the column leaked");
			assertFalse(containsHash(String.valueOf(entry)), "even the hash should not be here");
		}
	}

	@Test
	@DisplayName("the Entry record has no field that could hold an address")
	void theShapeCannotCarryIt() {
		// Stronger than checking values, and the version that survives somebody adding a
		// field later: if the record cannot express an address, no code path can leak one.
		for (var component : StaffAudit.Entry.class.getRecordComponents()) {
			String name = component.getName().toLowerCase(java.util.Locale.ROOT);
			assertFalse(name.contains("ip") || name.contains("address") || name.contains("host"),
					"StaffAudit.Entry gained a field called " + component.getName()
							+ ". Everything that renders an entry would now be able to print it.");
		}
	}

	@Test
	@DisplayName("the guarded read does return it, or the feature is pointless")
	void theAdminPathStillWorks() {
		// The other half. A control that hides the data from everybody including the person
		// investigating a compromised account has not protected anything, it has just deleted
		// the feature.
		List<StaffAudit.Origin> origins = audit.addressesFor(STAFF, 30);
		assertFalse(origins.isEmpty(), "the admin path returned nothing");
		assertTrue(AddressPrivacy.isHashed(origins.get(0).hashedAddress()),
				"and what it returns is a hash, not an address");
		assertFalse(origins.get(0).display().contains(ADDRESS),
				"even the display form must not be readable");
	}

	// ------------------------------------------------------------------ door two

	@Test
	@DisplayName("the export redacts the staff address")
	void exportDoesNotCarryIt() throws IOException {
		Path dir = storage.export();
		assertTrue(dir != null && Files.exists(dir), "the export did not run");

		try (Stream<Path> files = Files.list(dir)) {
			for (Path csv : files.filter(f -> f.toString().endsWith(".csv")).toList()) {
				String body = Files.readString(csv, StandardCharsets.UTF_8);
				assertFalse(body.contains(ADDRESS),
						csv.getFileName() + " contains a plaintext staff address");

				if (csv.getFileName().toString().startsWith("command_log")) {
					assertTrue(body.contains("staff_ip"),
							"the column should stay so the file keeps its shape");
					assertTrue(body.contains("[redacted]"),
							"and its values should be redacted");
				}
			}
		}
	}

	@Test
	@DisplayName("asking for addresses explicitly is the only way to export them")
	void exportWithAddressesIsDeliberate() throws IOException {
		Path dir = storage.export(true);
		assertTrue(dir != null && Files.exists(dir));

		// Even then the file holds hashes, because that is what is stored. The confirm
		// prompt exists for the disclosure, not because the value is readable.
		Path log = dir.resolve("command_log.csv");
		if (Files.exists(log)) {
			String body = Files.readString(log, StandardCharsets.UTF_8);
			assertFalse(body.contains(ADDRESS), "the export should never hold plaintext");
		}
	}

	// ---------------------------------------------------------------- door three

	@Test
	@DisplayName("nothing outside the accountability package reads the staff address column")
	void noOtherCodeTouchesIt() throws IOException {
		// The Discord door, checked before there is a Discord module to check. Phase 5 will
		// render embeds from whatever the read API offers, so the guarantee worth having now
		// is that the column has exactly one reader — anything else reaching for it is a new
		// path somebody has to justify, and this fails when they add it.
		List<String> offenders = new java.util.ArrayList<>();
		Path source = Path.of("src", "main", "java");

		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = source.relativize(file).toString().replace('\\', '/');
				if (relative.contains("modules/accountability/")) continue;
				if (relative.contains("storage/Schema.java")) continue;      // defines it
				if (relative.contains("storage/Storage.java")) continue;     // redacts it

				String body = Files.readString(file, StandardCharsets.UTF_8);
				if (body.contains("staff_ip")) offenders.add(relative);
			}
		}

		assertTrue(offenders.isEmpty(),
				"staff_ip is read outside the accountability package by:\n  "
						+ String.join("\n  ", offenders)
						+ "\nEvery one of those is a way for a colleague's address to reach a "
						+ "screen, a file or an embed. Route it through "
						+ "StaffAudit.addressesFor instead, which is gated.");
	}

	private boolean containsHash(String text) {
		try {
			return text.contains(AddressPrivacy.store(storage.conn(), ADDRESS));
		} catch (RuntimeException e) {
			return false;
		}
	}
}
