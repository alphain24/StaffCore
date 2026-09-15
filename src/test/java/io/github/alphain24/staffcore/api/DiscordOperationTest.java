package io.github.alphain24.staffcore.api;

import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of what Discord may ask for, and what it may not.
 */
class DiscordOperationTest {

	@Test
	@DisplayName("every Discord operation needs a real StaffCore node")
	void everyOperationIsGated() {
		for (DiscordOperation operation : DiscordOperation.values()) {
			assertTrue(Actor.all().contains(operation.node()),
					operation + " needs " + operation.node() + ", which StaffCore does not define — so "
							+ "nobody could ever hold it, except that a typo tends to get noticed last");
		}
	}

	@Test
	@DisplayName("no Discord operation needs a node that belongs to an IP ban, a rollback or an inventory edit")
	void theGuardedActionsAreNotOffered() {
		Set<String> inGameOnly = Set.of(Nodes.IP_BAN, Nodes.ROLLBACK, Nodes.ROLLBACK_REGEN,
				Nodes.INVSEE_EDIT, Nodes.CONFISCATE, Nodes.APPROVE, Nodes.VAULT_DESTROY, Nodes.VAULT_CLEAR,
				Nodes.PERMS_ADMIN, Nodes.AUDIT_ADDRESSES);
		for (DiscordOperation operation : DiscordOperation.values()) {
			assertFalse(inGameOnly.contains(operation.node()),
					operation + " offers " + operation.node() + " to Discord");
			String name = operation.name();
			for (String word : List.of("IP", "ROLLBACK", "INVENTORY", "APPROVE", "CONFISCATE")) {
				assertFalse(name.contains(word), operation + " looks like an in-game-only action");
			}
		}
	}

	@Test
	@DisplayName("the four services that run in-game-only actions refuse a Discord actor themselves")
	void theDoorsCheckTheChannel() throws IOException {
		String base = "src/main/java/io/github/alphain24/staffcore/";
		for (String file : List.of("modules/punish/AddressBans.java", "modules/accountability/Approvals.java",
				"modules/grief/GriefModule.java", "inventory/InventoryGateway.java")) {
			String source = Files.readString(Path.of(base + file));
			assertTrue(source.contains("DiscordReach.refusal("),
					file + " no longer refuses Discord actors itself. Keeping them out of the "
							+ "companion's command list is not enough: the next way in would not know.");
		}
	}
}
