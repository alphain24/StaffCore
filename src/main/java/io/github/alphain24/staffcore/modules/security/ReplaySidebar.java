package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.gui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;
import java.util.Optional;

/**
 * The numbers, on screen, while somebody is flying around inside the dig they describe.
 *
 * <h2>Why not chat</h2>
 * A replay is spent looking at the world, and chat scrolls. The four numbers that decide
 * whether this dig is worth acting on need to still be there after two minutes of flying down a
 * tunnel — otherwise the staff member is reading the shape of the excavation with no idea
 * whether it is unusual, which is half the evidence missing.
 *
 * <h2>Sent, not registered</h2>
 * The server's own scoreboard is shared by everybody, so putting a sidebar on it would show one
 * staff member's investigation to the whole server — including the player being investigated.
 * <p>
 * These packets are sent to one connection instead. The objective exists only in the client
 * that receives it: nothing is stored, nothing is broadcast, and a disconnect takes it with no
 * cleanup because there was never anything on the server to clean.
 */
public final class ReplaySidebar {
	private ReplaySidebar() {}

	/** Short, because a sidebar entry that wraps is unreadable. */
	private static final String OBJECTIVE = "sc_replay";

	/**
	 * Viewers whose client already holds the objective.
	 *
	 * <h2>This is a disconnect fix, not an optimisation</h2>
	 * {@code METHOD_ADD} for a name the client already has makes
	 * {@code Scoreboard.addObjective} throw {@code IllegalArgumentException} — verified in the
	 * 26.2 bytecode — and an exception inside the netty pipeline drops the connection with a
	 * protocol error.
	 * <p>
	 * The x-ray replay put the sidebar up once and never hit it. The session replay redraws to
	 * advance its clock, so the <em>second</em> draw killed the client: the replay opened,
	 * worked, and threw the viewer off the server about a second later. A second, because the
	 * redraw is every twenty ticks.
	 */
	private static final java.util.Set<java.util.UUID> OPEN =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Drops a viewer without sending anything. For a disconnect.
	 * <p>
	 * Their client has lost the objective along with everything else, so the record of it being
	 * up has to go too — otherwise the next replay after they rejoin skips creating it and
	 * they watch an empty sidebar.
	 */
	public static void forget(java.util.UUID viewer) {
		if (viewer != null) OPEN.remove(viewer);
	}

	/** Whether this viewer's client is believed to hold the objective. For tests. */
	public static boolean isOpen(java.util.UUID viewer) {
		return OPEN.contains(viewer);
	}

	/**
	 * Puts the sidebar up for one viewer.
	 * <p>
	 * Scores count downward from the number of lines, because a sidebar sorts by score
	 * descending and the reading order is top to bottom. Getting that backwards produces a
	 * panel that is right in every particular and upside down.
	 */
	public static void show(ServerPlayer staff, XraySweep.Finding finding, int canaryHits) {
		show(staff, lines(finding, canaryHits));
	}

	/**
	 * Puts an arbitrary set of rows up for one viewer, creating the objective only if this
	 * client does not already have it.
	 * <p>
	 * The x-ray replay shows four numbers about a dig and the session replay shows four about a
	 * clock; the packet work is identical and the rows are not. Splitting it here means the
	 * awkward parts are solved once — that scores sort descending so the reading order has to
	 * be inverted, that the entry name rather than the text is the key, and that the objective
	 * must be created exactly once per client.
	 * <p>
	 * <b>Rows are added, never removed.</b> Redrawing with fewer lines than last time leaves the
	 * extra ones on screen, because nothing tells the client to drop a row. Every caller here
	 * sends a fixed number, and one that did not would need to send blanks rather than a
	 * shorter list.
	 *
	 * @return true when the objective was created, false when only the rows were updated
	 */
	public static boolean show(ServerPlayer staff, List<String> lines) {
		if (staff == null || staff.connection == null) return false;

		boolean created = OPEN.add(staff.getUUID());
		if (created) {
			Objective objective = new Objective(new Scoreboard(), OBJECTIVE,
					ObjectiveCriteria.DUMMY,
					Theme.prefix().append(io.github.alphain24.staffcore.gui.Icon.text("Replay",
							Theme.ACCENT)),
					ObjectiveCriteria.RenderType.INTEGER, false, null);

			staff.connection.send(new ClientboundSetObjectivePacket(objective,
					ClientboundSetObjectivePacket.METHOD_ADD));
			staff.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR,
					objective));
		}

		// Scores are always re-sent. Updating a score is idempotent on the client, which is
		// what makes the clock able to tick without touching the objective again.
		for (int i = 0; i < lines.size(); i++) {
			// The entry name is what a vanilla sidebar keys on and must be unique per row; the
			// display component is what is actually drawn. Using the text as the key would
			// collapse two rows that happened to read the same.
			staff.connection.send(new ClientboundSetScorePacket("row" + i, OBJECTIVE,
					lines.size() - i,
					Optional.of(Component.literal(lines.get(i))), Optional.empty()));
		}
		return created;
	}

	/**
	 * The four numbers, in the order they answer questions.
	 * <p>
	 * How unlikely first, because that is the finding. Then the two fractions it was computed
	 * from, so the claim can be checked rather than taken. Canary hits last and only when there
	 * are any — a line reading "0" would be the most reassuring thing on the panel and it is
	 * not reassurance, it is the absence of a separate signal.
	 */
	static List<String> lines(XraySweep.Finding finding, int canaryHits) {
		List<String> out = new java.util.ArrayList<>();

		out.add("Chance: " + Hypergeometric.describe(finding.pValue()));
		out.add("Ore taken: " + finding.found() + " of " + finding.ores()
				+ " (" + percent(finding.foundFraction()) + ")");
		out.add("Dug: " + finding.drawn() + " of " + finding.population()
				+ " (" + percent(finding.drawn() / (double) Math.max(1, finding.population())) + ")");
		out.add(finding.world() + ", y " + finding.band());

		if (canaryHits > 0) {
			out.add("Decoys broken: " + canaryHits);
		}
		return out;
	}

	private static String percent(double fraction) {
		return Math.round(fraction * 100) + "%";
	}

	/**
	 * Takes it down.
	 * <p>
	 * Removing the objective is enough; the rows go with it. Sent unconditionally on exit
	 * because a sidebar left behind is a panel of numbers about somebody else's mine following
	 * a staff member around their own game.
	 */
	public static void hide(ServerPlayer staff) {
		if (staff == null) return;
		// Forgotten whether the connection is still there or not. A viewer who disconnected
		// still has to be dropped from the set, or their next session skips the objective
		// they no longer have and shows an empty sidebar.
		OPEN.remove(staff.getUUID());
		if (staff.connection == null) return;

		Objective objective = new Objective(new Scoreboard(), OBJECTIVE, ObjectiveCriteria.DUMMY,
				Component.literal(OBJECTIVE), ObjectiveCriteria.RenderType.INTEGER, false, null);

		staff.connection.send(new ClientboundSetObjectivePacket(objective,
				ClientboundSetObjectivePacket.METHOD_REMOVE));
	}
}
