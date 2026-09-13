package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.RiskProfile;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Actor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;

import java.util.UUID;

/**
 * The risk profile reads what is really on record, and a reversed punishment counts for nothing.
 */
public class RiskProfileTests {

	@GameTest
	public void aBanAndAnOpenCaseShowUpAndALiftedBanDoesNot(GameTestHelper helper) {
		var server = Harness.server(helper);
		NameAndId player = new NameAndId(UUID.randomUUID(), "riskSubject");

		Harness.checkEquals(helper, 0,
				RiskProfile.assess(RiskProfile.gather(server, player.id())).score(),
				"a player nobody has ever recorded anything about");

		Mods.punish().apply(server, player, "gametest", PunishmentType.BAN, null, "risk test",
				null, null, Actor.console());
		Mods.cases().store().openManually(player.id(), player.name(), "gametest", "risk", 80,
				CaseCategory.GRIEFING);

		var assessment = RiskProfile.assess(RiskProfile.gather(server, player.id()));
		Harness.check(helper, assessment.factors().stream().anyMatch(f -> f.label().equals("Banned right now")),
				"the ban in force is not a factor: " + assessment.factors());
		Harness.check(helper, assessment.factors().stream().anyMatch(f -> f.label().equals("Open cases")),
				"the open case is not a factor: " + assessment.factors());

		Mods.punish().revoke(server, player.id(), "gametest", true);
		var after = RiskProfile.assess(RiskProfile.gather(server, player.id()));
		Harness.check(helper, after.factors().stream().noneMatch(f -> f.source() == RiskProfile.Source.PUNISHMENTS),
				"a lifted ban still counts against them: " + after.factors());
		helper.succeed();
	}
}
