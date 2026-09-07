package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A punishment that runs out while nobody is looking still ends.
 * <p>
 * Expiry used to happen only on read — a lookup noticed a stale row and cleared it. That is
 * correct for enforcement, because a banned player is checked on login and an expired ban
 * therefore never keeps anybody out. It is useless for everything else: a ban that ran out
 * during a weekend of downtime stayed marked active until somebody happened to look, and its
 * case log skipped from "banned" to whatever came next with the ending missing.
 * <p>
 * That gap is the one an appeal falls into. "It expired three weeks ago" and "somebody lifted
 * it" are different facts, and only one of them was anybody's decision.
 */
class ExpirySweepTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach
	void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private long ban(long expiresInMillis, String caseId) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason, "
						+ "created_at, expires_at, active, case_id) "
						+ "VALUES (?,'Steve_','Alice','TEMPBAN','griefing',?,?,1,?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setLong(2, System.currentTimeMillis() - 86_400_000L);
			ps.setLong(3, System.currentTimeMillis() + expiresInMillis);
			ps.setString(4, caseId);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : -1;
			}
		}
	}

	private boolean isActive(long id) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"SELECT active FROM punishments WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) == 1;
			}
		}
	}

	@Test
	@DisplayName("a punishment past its time is retired without anybody looking it up")
	void sweepRetiresExpired() throws SQLException {
		long gone = ban(-3_600_000L, null);
		long standing = ban(3_600_000L, null);

		assertEquals(1, new PunishmentModule().sweepExpired(null));

		assertFalse(isActive(gone), "an expired ban stayed marked active");
		assertTrue(isActive(standing), "a ban with time left was retired early");
	}

	@Test
	@DisplayName("a permanent ban is never swept")
	void permanentIsNotExpiry() throws SQLException {
		// The row that must never be touched by a clock. A permanent ban has no expires_at,
		// and a sweep that treated null as "in the past" would quietly unban everybody.
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO punishments (target_uuid, target_name, staff_name, type, reason, "
						+ "created_at, active) VALUES (?,'Steve_','Alice','BAN','x',?,1)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setLong(2, System.currentTimeMillis());
			ps.executeUpdate();
		}

		assertEquals(0, new PunishmentModule().sweepExpired(null));
	}

	@Test
	@DisplayName("sweeping twice retires nothing the second time")
	void idempotent() throws SQLException {
		ban(-3_600_000L, null);

		PunishmentModule punish = new PunishmentModule();
		assertEquals(1, punish.sweepExpired(null));
		assertEquals(0, punish.sweepExpired(null),
				"a second sweep found the same row again, which would write a duplicate case "
						+ "event on every pass of the timer");
	}
}
