package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.modules.security.ReplaySidebar;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The sidebar is created once per client and updated thereafter.
 *
 * <h2>The bug these pin</h2>
 * A scoreboard objective is created on a client with {@code METHOD_ADD}. Sending that for a name
 * the client already holds makes {@code Scoreboard.addObjective} throw
 * {@code IllegalArgumentException} — the 26.2 bytecode throws on a duplicate key before doing
 * anything else — and an exception raised inside the netty pipeline drops the connection with a
 * protocol error.
 * <p>
 * The x-ray replay put the sidebar up once, so it never saw this. The session replay redraws
 * every twenty ticks to advance its clock, and the <b>second</b> draw killed the client: the
 * replay opened, worked, and threw the viewer off the server about a second later. That is the
 * whole symptom, and the "about a second" is the redraw interval.
 *
 * <h2>Why this is testable here and the disconnect is not</h2>
 * Whether a client survives the packet is a question about a client. What can be asserted on
 * this side is the property that decides it: the objective packet is sent on the first draw and
 * on no later one. {@code show} returns whether it created the objective, so the thing that
 * matters is observable without reading the wire.
 */
public class SidebarLifecycleTests {

	private static final List<String> ROWS = List.of("14:22:07", "100, 64, 200", "1x play", "40%");

	@GameTest
	public void theObjectiveIsCreatedOnceAndUpdatedAfter(GameTestHelper helper) {
		ServerPlayer staff = Harness.mockPlayer(helper);
		ReplaySidebar.forget(staff.getUUID());

		Harness.check(helper, ReplaySidebar.show(staff, ROWS),
				"the first draw did not create the objective, so the sidebar never appears");

		// Every redraw after the first. Before the fix each of these sent METHOD_ADD again,
		// and the first of them disconnected the viewer.
		for (int i = 0; i < 40; i++) {
			Harness.check(helper, !ReplaySidebar.show(staff, ROWS),
					"redraw " + (i + 1) + " tried to create the objective again. On a real "
							+ "client that is Scoreboard.addObjective throwing on a duplicate "
							+ "name, inside the netty pipeline, which drops the connection "
							+ "with a protocol error about a second into the replay.");
		}

		Harness.check(helper, ReplaySidebar.isOpen(staff.getUUID()),
				"the sidebar stopped being recorded as up while it was still being drawn");

		ReplaySidebar.hide(staff);
		helper.succeed();
	}

	@GameTest
	public void hidingLetsItBeCreatedAgain(GameTestHelper helper) {
		// The other direction, and the one that would turn this fix into a different bug. If
		// the record outlived the sidebar, a staff member's second replay would skip creating
		// the objective their client no longer has, and they would watch an empty panel.
		ServerPlayer staff = Harness.mockPlayer(helper);
		ReplaySidebar.forget(staff.getUUID());

		ReplaySidebar.show(staff, ROWS);
		ReplaySidebar.hide(staff);

		Harness.check(helper, !ReplaySidebar.isOpen(staff.getUUID()),
				"hiding the sidebar left it recorded as up");
		Harness.check(helper, ReplaySidebar.show(staff, ROWS),
				"a second replay did not re-create the objective, so the viewer gets an empty "
						+ "sidebar for the rest of the session");

		ReplaySidebar.hide(staff);
		helper.succeed();
	}

	@GameTest
	public void aDisconnectDropsTheRecord(GameTestHelper helper) {
		// A disconnecting client loses the objective with everything else, and nothing is sent
		// to it. The record has to go anyway, or the replay they open after rejoining draws
		// rows into an objective that was never created.
		ServerPlayer staff = Harness.mockPlayer(helper);
		ReplaySidebar.forget(staff.getUUID());

		ReplaySidebar.show(staff, ROWS);
		io.github.alphain24.staffcore.modules.replay.ReplayStage.forget(staff.getUUID());

		Harness.check(helper, !ReplaySidebar.isOpen(staff.getUUID()),
				"a disconnect left the sidebar recorded as up, so the next replay after "
						+ "rejoining skips creating the objective and shows nothing");

		ReplaySidebar.hide(staff);
		helper.succeed();
	}
}
