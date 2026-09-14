package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.PendingActions;
import io.github.alphain24.staffcore.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Emptying the vault and deleting its history, and what each must leave alone.
 * <p>
 * Both are one click behind a confirmation, so the line they draw has to be exact: a return
 * already promised to a player is not the vault's to destroy, and a debt forgiven is never a
 * return cancelled.
 */
class VaultClearTest {

	@TempDir
	Path world;

	private Storage storage;

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	private void vaultRow(String state) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO contraband_vault (owner_uuid, owner_name, taken_by, item, display, "
						+ "count, reason, state, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
			ps.setString(1, UUID.randomUUID().toString());
			ps.setString(2, "owner");
			ps.setString(3, "staff");
			ps.setString(4, "encoded");
			ps.setString(5, "Diamond");
			ps.setInt(6, 1);
			ps.setString(7, "test");
			ps.setString(8, state);
			ps.setLong(9, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private void pendingRow(UUID owner, String kind, int count) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO pending_actions (uuid, owner_name, kind, item, count, reason, "
						+ "queued_by, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
			ps.setString(1, owner.toString());
			ps.setString(2, "owner");
			ps.setString(3, kind);
			ps.setString(4, "minecraft:diamond");
			ps.setInt(5, count);
			ps.setString(6, "test");
			ps.setString(7, "system");
			ps.setLong(8, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("destroying everything held leaves returns that are waiting for a login")
	void destroyAllKeepsPendingReturns() throws SQLException {
		open();
		vaultRow("HELD");
		vaultRow("HELD");
		vaultRow("PENDING_RETURN");
		vaultRow("RETURNED");

		ContrabandVault vault = new ContrabandVault();
		assertEquals(2, vault.destroyAllHeld("admin"), "both held items should go");

		assertEquals(0, vault.count(ContrabandVault.State.HELD));
		assertEquals(2, vault.count(ContrabandVault.State.DESTROYED));
		assertEquals(1, vault.count(ContrabandVault.State.PENDING_RETURN),
				"a return already promised to a player was destroyed with the vault");
		assertEquals(1, vault.count(ContrabandVault.State.RETURNED));
		assertTrue(vault.page(ContrabandVault.State.DESTROYED, 0, 10).stream()
				.allMatch(e -> "admin".equals(e.resolvedBy())), "who destroyed them was not recorded");
	}

	@Test
	@DisplayName("deleting the history removes only finished rows")
	void deleteHistoryKeepsLiveItems() throws SQLException {
		open();
		vaultRow("HELD");
		vaultRow("PENDING_RETURN");
		vaultRow("RETURNED");
		vaultRow("DESTROYED");
		vaultRow("DESTROYED");

		ContrabandVault vault = new ContrabandVault();
		assertEquals(3, vault.deleteHistory(), "every returned and destroyed record should go");

		assertEquals(2, vault.count(null), "a held item or a waiting return lost its row");
		assertEquals(1, vault.count(ContrabandVault.State.HELD));
		assertEquals(1, vault.count(ContrabandVault.State.PENDING_RETURN));
	}

	@Test
	@DisplayName("forgiving debts never cancels a return owed to somebody")
	void forgivingLeavesReturns() throws SQLException {
		open();
		UUID debtor = UUID.randomUUID();
		UUID owner = UUID.randomUUID();
		pendingRow(debtor, "DEBIT", 5);
		pendingRow(debtor, "DEBIT", 3);
		pendingRow(owner, "GIVE", 1);

		PendingActions pending = new PendingActions();
		assertEquals(2, pending.debtsOf(debtor).size());
		assertTrue(pending.debtsOf(owner).isEmpty(), "a queued return was listed as a debt");

		long give = pending.forPlayer(owner).get(0).id();
		assertFalse(pending.cancelDebit(give), "forgiving a debt cancelled a return instead");

		long one = pending.debtsOf(debtor).get(0).id();
		assertTrue(pending.cancelDebit(one));
		assertEquals(1, pending.debtsOf(debtor).size());

		assertEquals(3, pending.forgiveAll(), "forgiving everything wrote off the wrong amount");
		assertTrue(pending.debtsOf(debtor).isEmpty());
		assertEquals(1, pending.forPlayer(owner).size(), "forgiving every debt cancelled a return");
	}
}
