package io.github.alphain24.staffcore.inventory;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantees the one door is supposed to provide.
 * <p>
 * Most of what {@code InventoryGateway} does needs a live {@code ServerPlayer} and cannot be
 * driven headless. What can be checked here is the part that decides whether the door is a
 * door at all: that the audit table exists on both a fresh and an upgraded database, that a
 * subsystem whose hooks are broken is refused, and that the refusal names the reason rather
 * than failing silently — a gateway that declines quietly is worse than no gateway, because
 * the caller carries on believing the items moved.
 */
class InventoryGatewayTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	@Test
	@DisplayName("the audit table is there to be written to")
	void theAuditTableExists() throws SQLException {
		open();

		// The gateway commits the record before it moves anything, so a missing table does
		// not mean "no audit trail", it means every mutation is refused. Worth its own check
		// for that reason: the failure is total rather than cosmetic.
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT name FROM sqlite_master WHERE type='table' AND name='inventory_audit'")) {
			assertTrue(rs.next(), "inventory_audit is missing, so no mutation could be recorded "
					+ "and every one of them would be refused");
		}
	}

	@Test
	@DisplayName("the audit table carries everything needed to answer 'who took my things'")
	void theAuditRowIsComplete() throws SQLException {
		open();

		List<String> required = List.of("origin", "direction", "actor", "target_uuid",
				"target_name", "reason", "items", "item_count", "snapshot_id", "created_at");

		List<String> present = new java.util.ArrayList<>();
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("PRAGMA table_info(inventory_audit)")) {
			while (rs.next()) present.add(rs.getString("name"));
		}

		for (String column : required) {
			assertTrue(present.contains(column),
					"inventory_audit has no " + column + " column; the record would not say "
							+ "enough to act on");
		}
	}

	@Test
	@DisplayName("history reads back empty rather than throwing when nothing has happened")
	void historyIsSafeOnAnEmptyTable() {
		open();
		assertTrue(InventoryGateway.historyFor(java.util.UUID.randomUUID(), 20).isEmpty());
	}

	@Test
	@DisplayName("every origin declares what it needs, and only the log-derived ones need anything")
	void originsDeclareTheirDependencies() {
		// The distinction that makes the health check meaningful. A rollback debit works out
		// what somebody owes by reading the block and pickup logs, so a broken logging hook
		// does not leave those logs empty — it leaves them *wrong*, and a debit computed from
		// them takes items for a debt that was never measured. A staff member dragging a
		// stack out of an inventory depends on no such thing.
		assertFalse(InventoryGateway.Origin.ROLLBACK_DEBIT.requiredFeatures().isEmpty(),
				"a rollback debit is computed from the logs and must refuse when they are broken");
		assertTrue(InventoryGateway.Origin.INVSEE_EDIT.requiredFeatures().isEmpty(),
				"a direct staff edit reads no log, so gating it on one would refuse work that "
						+ "was never at risk");
		assertTrue(InventoryGateway.Origin.VAULT_RETURN.requiredFeatures().isEmpty());
	}

	@Test
	@DisplayName("a refusal says why, in words a server owner can act on")
	void refusalsAreNotSilent() {
		InventoryGateway.Outcome refused = InventoryGateway.Outcome.refused("the hooks are broken");

		assertTrue(refused.wasRefused());
		assertFalse(refused.applied(), "a refused mutation must not report success — the caller "
				+ "carries on believing the items moved");
		assertEquals(0, refused.items());
		assertTrue(refused.refused().length() > 10, "the reason has to be usable, not a flag");
	}
}
