package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.StaffCoreListener;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.Signal;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A frozen player who leaves is reported to staff and written into their case; one the server removes
 * is not; and one who comes back is frozen again, and the case says so.
 */
public class FreezeEvasionTests {

	private static List<Signal> evasions(String caseId) {
		return Mods.cases().store().signalsFor(caseId).stream()
				.filter(s -> s.type() == Signal.Type.FREEZE_EVASION).toList();
	}

	/** Leaves nothing frozen on disk for an account that is gone. */
	private static void release(UUID player) {
		Mods.freeze().forget(player);
	}

	@GameTest
	public void aFrozenPlayerWhoLeavesIsWrittenIntoTheCaseTheyHaveOpen(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		UUID id = player.getUUID();
		String name = Harness.name(player);
		String caseId = Mods.cases().store().openManually(id, name, "Console", "gametest: suspected x-ray", 40,
				CaseCategory.CHEATING);

		List<StaffCoreEvent.SignalRaised> raised = new CopyOnWriteArrayList<>();
		StaffCoreListener listener = event -> {
			if (event instanceof StaffCoreEvent.SignalRaised signal && id.equals(signal.subjectId())) raised.add(signal);
		};
		StaffCoreApi.addListener(listener);
		try {
			Mods.freeze().toggle(player, "Moderator");
			Harness.quit(player);

			List<Signal> found = evasions(caseId);
			Harness.check(helper, found.size() == 1, "leaving while frozen was not written into the open case: " + found);
			Harness.check(helper, found.get(0).evidenceJson().contains("left the server while frozen by Moderator"),
					"the case does not say what happened: " + found.get(0).evidenceJson());
			Harness.check(helper, Mods.cases().store().openCasesFor(id).size() == 1,
					"a second case was opened instead of adding to the one they had");
			Harness.check(helper, Mods.cases().evidence().forCase(caseId).stream()
					.anyMatch(item -> item.kind() == CaseEvidence.Kind.LOCATION), "where they were frozen was not filed");

			Harness.check(helper, EventBus.drain(5000), "the event thread did not finish");
			Harness.check(helper, raised.stream().anyMatch(s -> caseId.equals(s.caseId())
					&& "FREEZE_EVASION".equals(s.type())), "staff and Discord were not told: " + raised);
			Harness.check(helper, StaffCore.state().loadAll().get(id) != null && StaffCore.state().loadAll().get(id).frozen(),
					"leaving cleared the freeze");
		} finally {
			StaffCoreApi.removeListener(listener);
			release(id);
		}
		helper.succeed();
	}

	@GameTest
	public void leavingWithNoCaseOpenOpensOne(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		UUID id = player.getUUID();
		try {
			Mods.freeze().toggle(player, "Moderator");
			Harness.quit(player);
			List<Case> open = Mods.cases().store().openCasesFor(id);
			Harness.check(helper, open.size() == 1, "no case was opened for leaving while frozen: " + open);
			Harness.check(helper, evasions(open.get(0).id()).size() == 1, "the case does not hold what happened");
		} finally {
			release(id);
		}
		helper.succeed();
	}

	@GameTest
	public void aFrozenPlayerTheServerRemovesIsNotReported(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		UUID id = player.getUUID();
		try {
			Mods.freeze().toggle(player, "Moderator");
			Harness.kick(player, Component.literal("gametest: kicked"));
			Harness.check(helper, player.hasDisconnected(), "the kick did not remove the player");
			Harness.check(helper, Mods.cases().store().openCasesFor(id).isEmpty(),
					"a kick was reported as leaving while frozen");
		} finally {
			release(id);
		}
		helper.succeed();
	}

	@GameTest
	public void aPlayerWhoComesBackIsFrozenAgainAndTheCaseSaysSo(GameTestHelper helper) {
		ServerPlayer player = Harness.namedPlayer(helper);
		UUID id = player.getUUID();
		var profile = player.getGameProfile();
		ServerPlayer back = null;
		try {
			Mods.freeze().toggle(player, "Moderator");
			Harness.quit(player);
			String caseId = Mods.cases().store().openCasesFor(id).get(0).id();

			back = Harness.namedPlayer(helper, profile);
			Harness.check(helper, Mods.freeze().isFrozen(back), "coming back cleared the freeze");
			Harness.check(helper, Mods.cases().store().eventsFor(caseId).stream()
					.anyMatch(e -> e.body() != null && e.body().contains("came back")), "the case does not say they came back");
		} finally {
			if (back != null && Mods.freeze().isFrozen(back)) Mods.freeze().toggle(back);
			release(id);
		}
		helper.succeed();
	}
}
