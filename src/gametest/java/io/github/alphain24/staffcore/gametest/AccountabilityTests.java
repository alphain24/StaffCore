package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.modules.accountability.Witnesses;
import io.github.alphain24.staffcore.module.Mods;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The parts of the 2.3 accountability work that only exist with a server running.
 * <p>
 * Recording who was online needs a player list; recording a name needs somebody to have
 * joined; resolving a name that nobody is currently using needs the mod's own tables and the
 * server's name cache to agree. None of those can be checked headlessly, and all three fail in
 * the same quiet way — by writing nothing and reporting success.
 */
public class AccountabilityTests {

	@GameTest
	public void whoWasOnlineIsRecordedAgainstAnIncident(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		ServerPlayer subject = Harness.mockPlayer(helper);

		String ref = "gametest-" + System.nanoTime();
		Witnesses.record(server, Witnesses.Kind.PUNISHMENT, ref, Harness.name(subject));

		var record = Witnesses.forIncident(Witnesses.Kind.PUNISHMENT, ref);
		Harness.check(helper, record != null,
				"nothing was recorded, so an appeal about this moment has no answer to the "
						+ "only question that decides it");

		// The subject is excluded on purpose: "the player being punished was online" is not
		// information anybody needs written down.
		Harness.check(helper, !record.names().contains(Harness.name(subject)),
				"the subject is in their own witness list: " + record.names());
		helper.succeed();
	}

	@GameTest
	public void anEmptyServerIsRecordedAsEmpty(GameTestHelper helper) {
		// The finding that is easiest to lose. "Nobody could have seen it" and "we did not
		// write it down" lead to opposite conclusions, and a row is what tells them apart.
		String ref = "gametest-alone-" + System.nanoTime();
		Witnesses.record(Harness.server(helper), Witnesses.Kind.SIGNAL, ref, "Nobody");

		var record = Witnesses.forIncident(Witnesses.Kind.SIGNAL, ref);
		Harness.check(helper, record != null, "an empty list must still be a record");
		Harness.check(helper, Witnesses.describe(record).contains("Connected")
						|| record.alone(),
				"the description does not distinguish empty from absent: "
						+ Witnesses.describe(record));
		helper.succeed();
	}

	@GameTest
	public void aNameIsRecordedAndFoundAgainAfterwards(GameTestHelper helper) {
		// The whole point of the table: an old record naming somebody nobody can find any
		// more. Recorded here through the real method, then looked up by the name rather than
		// by the id, which is the direction an investigation actually goes.
		ServerPlayer player = Harness.mockPlayer(helper);
		String name = Harness.name(player);

		Mods.identity().recordName(player.getUUID(), name);

		var names = Mods.identity().namesOf(player.getUUID());
		Harness.check(helper, !names.isEmpty(), "the join recorded no name at all");
		Harness.check(helper, names.stream().anyMatch(n -> n.name().equals(name)),
				"the name recorded is not the one they joined under: " + names);

		Harness.check(helper,
				Mods.identity().accountsEverCalled(name).contains(player.getUUID()),
				"the name does not lead back to the account, which is the direction that "
						+ "matters when a ban record names somebody who has since renamed");
		helper.succeed();
	}

	@GameTest
	public void expiredPunishmentsAreRetiredWithoutAnybodyLooking(GameTestHelper helper) {
		// The sweep runs against a real server here, which is where it runs in production —
		// the headless test covers the query, and this covers that calling it with a live
		// server present does not throw on the case-note path.
		int retired = Mods.punish().sweepExpired(Harness.server(helper));
		Harness.check(helper, retired >= 0, "the sweep failed rather than finding nothing");
		helper.succeed();
	}
}
