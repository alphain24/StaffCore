package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 6.8 on a running server: a replay drawn for Discord is behind {@code staff.replay} on both sides,
 * as the replay in game is, and filing one as evidence is a pointer to the history.
 * <p>
 * The test server records no positions, so what is checked past the gate is that the answer says so —
 * the drawing itself is tested headless in the companion.
 */
public class DiscordReplayTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static String group(ServerPlayer player, String... nodes) {
		String name = "gametest-" + player.getUUID().toString().substring(0, 8);
		PermissionGroups groups = PermissionGroups.get();
		groups.groups.put(name, new ArrayList<>(List.of(nodes)));
		groups.players.put(player.getUUID().toString(), name);
		return name;
	}

	private static void ungroup(ServerPlayer player, String name) {
		PermissionGroups groups = PermissionGroups.get();
		groups.players.remove(player.getUUID().toString());
		groups.groups.remove(name);
	}

	@GameTest
	public void aReplayMapNeedsTheReplayPermissionOnBothSides(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		ServerPlayer suspect = Harness.namedPlayer(helper);
		String group = group(staff, Nodes.STAFF_GUI);
		try {
			DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(staff),
					Set.of(Nodes.STAFF_GUI, Nodes.REPLAY));
			String code = Mods.discord().links().issueCode(staff.getUUID(), Harness.name(staff));
			Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
			long now = System.currentTimeMillis();

			var noGameNode = DiscordAccess.replayTrack(user, Harness.name(suspect), now - 600_000L, now).join();
			Harness.check(helper, !noGameNode.answered() && noGameNode.refusal().contains(Nodes.REPLAY),
					"a replay was drawn without staff.replay in game: " + noGameNode.refusal());

			PermissionGroups.get().groups.get(group).add(Nodes.REPLAY);
			DiscordUser noRole = new DiscordUser(user.id(), user.name(), Set.of(Nodes.STAFF_GUI));
			Harness.check(helper, !DiscordAccess.replayTrack(noRole, Harness.name(suspect), now - 600_000L, now).join()
					.answered(), "a replay was drawn without staff.replay in the role mapping");

			var allowed = DiscordAccess.replayTrack(user, Harness.name(suspect), now - 600_000L, now).join();
			if (!StaffConfig.get().positionTracking) {
				Harness.check(helper, !allowed.answered() && allowed.refusal().contains("Position tracking is off"),
						"with nothing recorded, the answer does not say why: " + allowed.refusal());
			}

			// Filed as evidence: a pointer, readable back as a replay, behind the same node.
			String caseId = Mods.cases().store().openManually(suspect.getUUID(), Harness.name(suspect), "Console",
					"gametest case", 40, CaseCategory.OTHER);
			Harness.check(helper, !DiscordAccess.fileReplayEvidence(user, caseId, now, now - 1).join().done(),
					"a window that ends before it starts was filed");
			Harness.check(helper, !DiscordAccess.fileReplayEvidence(user, caseId, now - 7L * 3_600_000L, now).join().done(),
					"a window longer than six hours was filed");
			var filed = DiscordAccess.fileReplayEvidence(user, caseId, now - 1_800_000L, now).join();
			Harness.check(helper, filed.done(), "filing the replay failed: " + filed.message());
			CaseEvidence.Item item = Mods.cases().evidence().forCase(caseId).stream()
					.filter(i -> i.kind() == CaseEvidence.Kind.REPLAY).findFirst().orElse(null);
			Harness.check(helper, item != null && item.subjectId().equals(suspect.getUUID()), "no replay evidence on the case");

			var detail = DiscordAccess.evidenceItem(user, caseId, item.id()).join();
			Harness.check(helper, detail.answered() && detail.value().replay(), "the evidence does not say it is a replay");
			Harness.check(helper, !DiscordAccess.replayForEvidence(noRole, caseId, item.id()).join().answered(),
					"replay evidence was drawn without staff.replay in the role mapping");
			var forEvidence = DiscordAccess.replayForEvidence(user, caseId, item.id()).join();
			if (!StaffConfig.get().positionTracking) {
				Harness.check(helper, !forEvidence.answered() && forEvidence.refusal().contains("Position tracking is off"),
						"replay evidence with nothing recorded does not say why: " + forEvidence.refusal());
			}
		} finally {
			ungroup(staff, group);
		}
		helper.succeed();
	}
}
