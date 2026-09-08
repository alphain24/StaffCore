package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.security.ReplaySession;
import io.github.alphain24.staffcore.modules.security.XrayReplayView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Getting back out of a replay, which is the only part that can strand somebody.
 * <p>
 * Gate 3 names four ways of leaving and asks that all of them restore. Three need a live
 * player: the exit command, a reconnect, and walking into another dimension. The fourth — the
 * server stopping — is the record surviving on disk, and is covered headlessly.
 * <p>
 * The failure this guards against does not throw. It leaves a staff member in spectator, in
 * somebody else's mine, with the row that says where they came from either gone or pointing
 * somewhere wrong. Nothing about that produces an error anybody sees.
 */
public class ReplayViewTests {

	/** A dig for the viewer to stand in, since the world here has no mining history. */
	private static void seedDig(GameTestHelper helper, String subject) throws SQLException {
		long now = System.currentTimeMillis();
		String world = io.github.alphain24.staffcore.compat.Mc.dimensionId(helper.getLevel());

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"INSERT INTO block_log (player_name, action, block, world, x, y, z, created_at, "
						+ "gamemode) VALUES (?,'BREAK',?,?,?,?,?,?,'survival')")) {

			for (int i = 0; i < 700; i++) {
				ps.setString(1, subject);
				ps.setString(2, i % 30 == 0
						? "minecraft:deepslate_diamond_ore" : "minecraft:deepslate");
				ps.setString(3, world);
				ps.setInt(4, i % 50);
				ps.setInt(5, 10);
				ps.setInt(6, i / 50);
				ps.setLong(7, now - (700 - i) * 1000L);
				ps.addBatch();
			}
			ps.executeBatch();
		}
	}

	private static void clean(ServerPlayer staff) {
		ReplaySession.clear(staff.getUUID());
		XrayReplayView.forgetAll();
	}

	@GameTest
	public void enteringRecordsTheWayHomeBeforeMovingAnybody(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		try {
			seedDig(helper, "replay-subject-a");
		} catch (SQLException e) {
			throw helper.assertionException("could not seed: " + e.getMessage());
		}

		double startedAt = staff.getX();
		var entry = XrayReplayView.enter(server, staff, "replay-subject-a", null,
				6L * 3_600_000L);

		Harness.check(helper, entry.started(), "the replay did not start: " + entry.refusal());
		Harness.check(helper, ReplaySession.isReplaying(staff.getUUID()),
				"nothing recorded where they came from, so there is no way back");

		var prior = ReplaySession.of(staff.getUUID());
		Harness.check(helper, Math.abs(prior.x() - startedAt) < 0.001,
				"the recorded position is not where they were standing before the teleport");
		Harness.check(helper, prior.gameMode() != GameType.SPECTATOR,
				"the recorded gamemode is spectator, which is the state being escaped from — "
						+ "the record was taken after the change rather than before it");

		XrayReplayView.exit(server, staff, null);
		clean(staff);
		helper.succeed();
	}

	@GameTest
	public void exitPutsThemBackAndForgetsTheSession(GameTestHelper helper) {
		MinecraftServer server = Harness.server(helper);
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		try {
			seedDig(helper, "replay-subject-b");
		} catch (SQLException e) {
			throw helper.assertionException("could not seed: " + e.getMessage());
		}

		GameType before = staff.gameMode();
		XrayReplayView.enter(server, staff, "replay-subject-b", null, 6L * 3_600_000L);

		Harness.check(helper, XrayReplayView.exit(server, staff, null), "exit did nothing");
		Harness.check(helper, staff.gameMode() == before,
				"they came back in " + staff.gameMode() + " rather than the "
						+ before + " they left in");
		Harness.check(helper, !ReplaySession.isReplaying(staff.getUUID()),
				"the session was not cleared, so the next restore would move them again");

		clean(staff);
		helper.succeed();
	}

	@GameTest
	public void exitIsSafeToCallOnSomebodyNotReplaying(GameTestHelper helper) {
		// Four of the five callers fire for every player on every join, death and dimension
		// change. Almost every call is about somebody who is not replaying, and it must not
		// teleport them anywhere or change their gamemode.
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		GameType before = staff.gameMode();
		double x = staff.getX();

		Harness.check(helper, !XrayReplayView.exit(Harness.server(helper), staff, null),
				"exit reported bringing back somebody who was never away");
		Harness.check(helper, staff.gameMode() == before, "it changed their gamemode anyway");
		Harness.check(helper, Math.abs(staff.getX() - x) < 0.001, "it moved them anyway");
		helper.succeed();
	}

	@GameTest
	public void reconnectingMidReplayPutsThemBack(GameTestHelper helper) {
		// A disconnect leaves the row on disk on purpose, so the restore happens when they
		// return. This is that path — the same one the join handler calls for every player.
		MinecraftServer server = Harness.server(helper);
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		try {
			seedDig(helper, "replay-subject-c");
		} catch (SQLException e) {
			throw helper.assertionException("could not seed: " + e.getMessage());
		}

		GameType before = staff.gameMode();
		XrayReplayView.enter(server, staff, "replay-subject-c", null, 6L * 3_600_000L);

		// What a disconnect does: drops the drawing, keeps the way home.
		XrayReplayView.forget(staff.getUUID());
		Harness.check(helper, ReplaySession.isReplaying(staff.getUUID()),
				"the disconnect took the way home with it");

		XrayReplayView.restoreOnJoin(server, staff);
		Harness.check(helper, !ReplaySession.isReplaying(staff.getUUID()),
				"rejoining did not end the replay");
		Harness.check(helper, staff.gameMode() == before,
				"they rejoined still in " + staff.gameMode());

		clean(staff);
		helper.succeed();
	}

	@GameTest
	public void enteringTwiceIsRefusedRatherThanStacked(GameTestHelper helper) {
		// The second entry would record the spectator position as the way home. Refusing is
		// the only safe answer, and the message has to say how to get out.
		MinecraftServer server = Harness.server(helper);
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		try {
			seedDig(helper, "replay-subject-d");
		} catch (SQLException e) {
			throw helper.assertionException("could not seed: " + e.getMessage());
		}

		XrayReplayView.enter(server, staff, "replay-subject-d", null, 6L * 3_600_000L);
		var second = XrayReplayView.enter(server, staff, "replay-subject-d", null,
				6L * 3_600_000L);

		Harness.check(helper, !second.started(), "a second replay started on top of the first");
		Harness.check(helper, second.refusal().contains("exit"),
				"the refusal does not say how to get out: " + second.refusal());

		XrayReplayView.exit(server, staff, null);
		clean(staff);
		helper.succeed();
	}

	@GameTest
	public void replayingSomebodyWithNoMiningIsRefusedNotEmpty(GameTestHelper helper) {
		// Refused before anything is recorded or anybody is moved. Teleporting a staff member
		// into an empty replay and then having nothing to show them is worse than saying no.
		ServerPlayer staff = Harness.mockPlayer(helper);
		clean(staff);

		var entry = XrayReplayView.enter(Harness.server(helper), staff,
				"nobody-has-ever-mined-here", null, 6L * 3_600_000L);

		Harness.check(helper, !entry.started(), "a replay started with nothing to replay");
		Harness.check(helper, !ReplaySession.isReplaying(staff.getUUID()),
				"a session was recorded for a replay that never began, so the next join would "
						+ "teleport them somewhere for no reason");
		helper.succeed();
	}
}
