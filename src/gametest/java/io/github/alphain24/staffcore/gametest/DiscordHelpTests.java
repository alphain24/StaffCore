package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Asking staff for help from the public contact channel: open to anybody but limited, honest about who is
 * asking, written into a case only for the player's own account, and joined by staff through the gate.
 */
public class DiscordHelpTests {

	private static String snowflake() {
		return String.valueOf(100_000_000_000_000_000L + (long) (Math.random() * 800_000_000_000_000_000L));
	}

	private static DiscordUser link(GameTestHelper helper, ServerPlayer player, String... roleNodes) {
		DiscordUser user = new DiscordUser(snowflake(), "dc_" + Harness.name(player), Set.of(roleNodes));
		String code = Mods.discord().links().issueCode(player.getUUID(), Harness.name(player));
		Harness.check(helper, DiscordAccess.link(user, code).join().done(), "linking failed");
		return user;
	}

	private static boolean caseSaysAsked(String caseId, long request) {
		return Mods.cases().store().eventsFor(caseId).stream()
				.anyMatch(e -> e.body() != null && e.body().contains("request #" + request));
	}

	@GameTest
	public void aFrozenPlayerAskingFromTheirOwnAccountIsNotedOnTheirCase(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		String name = Harness.name(player);
		String caseId = Mods.cases().store().openManually(player.getUUID(), name, "Mod", "gametest: x-ray", 40,
				CaseCategory.CHEATING);
		Mods.freeze().toggle(player, "Mod");
		try {
			DiscordUser own = link(helper, player);
			var answer = DiscordAccess.helpRequest(own, name.toUpperCase(java.util.Locale.ROOT), "I am frozen, why?", 501).join();
			Harness.check(helper, answer.answered(), "the request was refused: " + answer.refusal());
			var info = answer.value();
			Harness.check(helper, info.known() && info.playerName().equals(name), "the player was not recognised: " + info);
			Harness.check(helper, info.frozen() && info.online() && !info.banned(), "the player's state is wrong: " + info);
			Harness.check(helper, info.linkedToThisPlayer(), "the account was not seen to be the player's own");
			Harness.checkEquals(helper, caseId, info.caseId(), "the open case");
			Harness.check(helper, caseSaysAsked(caseId, 501), "the case does not say they asked for help");

			// Somebody else, naming the same player: told to staff, but not written into the player's case.
			DiscordUser stranger = new DiscordUser(snowflake(), "stranger", Set.of());
			var other = DiscordAccess.helpRequest(stranger, name, "help", 502).join();
			Harness.check(helper, other.answered() && !other.value().linkedToThisPlayer() && other.value().linkedTo() == null,
					"an unlinked account was taken for the player: " + other);
			Harness.check(helper, !caseSaysAsked(caseId, 502), "an unlinked account wrote into the player's case");
		} finally {
			Mods.freeze().toggle(player);
		}
		helper.succeed();
	}

	@GameTest
	public void askingIsCheckedAndLimited(GameTestHelper helper) {
		DiscordUser asker = new DiscordUser(snowflake(), "asker", Set.of());
		Harness.check(helper, !DiscordAccess.helpRequest(asker, "not a name!", "help", 1).join().answered(),
				"a name that cannot be a Minecraft name was taken");
		Harness.check(helper, !DiscordAccess.helpRequest(asker, "Steve_", "   ", 1).join().answered(),
				"an empty request was taken");

		var unknown = DiscordAccess.helpRequest(asker, "Nobody_" + (int) (Math.random() * 9000 + 1000), "help", 2).join();
		Harness.check(helper, unknown.answered() && !unknown.value().known(), "an unknown name was not said to be unknown");

		for (int i = 0; i < 2; i++) {
			Harness.check(helper, DiscordAccess.helpRequest(asker, "Steve_", "help", 3 + i).join().answered(),
					"a request within the hour's limit was refused");
		}
		var fourth = DiscordAccess.helpRequest(asker, "Steve_", "help", 9).join();
		Harness.check(helper, !fourth.answered() && fourth.refusal().contains("last hour"),
				"a fourth request in an hour was taken: " + fourth);
		helper.succeed();
	}

	@GameTest
	public void joiningARequestGoesThroughTheGate(GameTestHelper helper) {
		ServerPlayer staff = Harness.namedPlayer(helper);
		String group = "gametest-" + staff.getUUID().toString().substring(0, 8);
		PermissionGroups.get().groups.put(group, new ArrayList<>(List.of(Nodes.STAFF_GUI)));
		PermissionGroups.get().players.put(staff.getUUID().toString(), group);
		try {
			DiscordUser user = link(helper, staff, Nodes.REPORT_VIEW, Nodes.STAFF_GUI);
			DiscordUser unlinked = new DiscordUser(snowflake(), "nobody", Set.of(Nodes.REPORT_VIEW));
			Harness.check(helper, !DiscordAccess.helpDesk(unlinked, 1, "join").join().done(),
					"an unlinked account joined a request");

			var refused = DiscordAccess.helpDesk(user, 1, "join").join();
			Harness.check(helper, !refused.done() && refused.message().contains(Nodes.REPORT_VIEW),
					"a request was joined without report.view in game: " + refused.message());

			PermissionGroups.get().groups.get(group).add(Nodes.REPORT_VIEW);
			var joined = DiscordAccess.helpDesk(user, 1, "join").join();
			Harness.check(helper, joined.done(), "joining failed: " + joined.message());
			Harness.checkEquals(helper, Harness.name(staff), joined.message(), "the name the thread is told");
		} finally {
			PermissionGroups.get().players.remove(staff.getUUID().toString());
			PermissionGroups.get().groups.remove(group);
		}
		helper.succeed();
	}
}
