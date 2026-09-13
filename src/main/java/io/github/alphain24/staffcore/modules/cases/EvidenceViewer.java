package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * Opens a piece of case evidence for a staff member: plays the replay, stands them in the dig,
 * shows the grief log around the spot, takes them there, or opens the snapshots.
 *
 * <h2>Each kind keeps its own permission</h2>
 * Evidence on a case is a pointer to a tool that already exists, and it is opened through that
 * tool's own gate. Being able to read a case is not being allowed to watch where somebody has
 * been, and a replay filed against a case must not become a way to watch one without
 * {@code staff.replay}.
 */
public final class EvidenceViewer {
	private EvidenceViewer() {}

	/** Opens it, or tells the viewer why not. Returns whether anything opened. */
	public static boolean open(ServerPlayer viewer, CaseEvidence.Item item) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return false;

		return switch (item.kind()) {
			case REPLAY -> replay(server, viewer, item);
			case XRAY_DIG -> dig(server, viewer, item);
			case BLOCKS -> blocks(server, viewer, item);
			case LOCATION -> location(server, viewer, item);
			case SNAPSHOT -> snapshot(viewer, item);
		};
	}

	private static boolean replay(MinecraftServer server, ServerPlayer viewer, CaseEvidence.Item item) {
		if (!allowed(viewer, Nodes.REPLAY)) return false;
		if (item.subjectId() == null) return refuse(viewer, "This replay does not say whose it is.");

		viewer.closeContainer();
		var entry = io.github.alphain24.staffcore.modules.replay.SessionReplay.enter(server, viewer,
				item.subjectId(), item.subjectName(), item.caseId(), item.from(), item.to());
		return entry.started() || refuse(viewer, entry.refusal());
	}

	private static boolean dig(MinecraftServer server, ServerPlayer viewer, CaseEvidence.Item item) {
		if (!allowed(viewer, Nodes.SECURITY_CHECK)) return false;

		viewer.closeContainer();
		// The dig view reads "the last so-many hours", so the window is stretched back to cover
		// this evidence's start. A wider window can only find the same dig or a bigger one.
		long window = Math.max(60_000L, System.currentTimeMillis() - item.from());
		var entry = io.github.alphain24.staffcore.modules.security.XrayReplayView.enter(server,
				viewer, item.subjectName(), item.caseId(), window);
		return entry.started() || refuse(viewer, entry.refusal());
	}

	private static boolean blocks(MinecraftServer server, ServerPlayer viewer, CaseEvidence.Item item) {
		if (!allowed(viewer, Nodes.LOGS)) return false;
		if (item.pos() == null) return refuse(viewer, "This evidence does not say where.");
		if (item.world() != null && !item.world().equals(Mc.dimensionId(viewer.level()))) {
			return refuse(viewer, "That happened in " + item.world() + ". Open the location "
					+ "evidence to go there, then open this again.");
		}

		// The grief log reads "the last so-many minutes", so it is opened far enough back to
		// include this evidence's start.
		int minutes = (int) Math.max(5,
				Math.ceil((System.currentTimeMillis() - item.from()) / 60_000.0));
		io.github.alphain24.staffcore.gui.menu.GriefMenu.openAround(viewer, item.pos(), minutes);
		return true;
	}

	private static boolean location(MinecraftServer server, ServerPlayer viewer, CaseEvidence.Item item) {
		if (!allowed(viewer, Nodes.TP)) return false;
		if (item.pos() == null) return refuse(viewer, "This evidence does not say where.");

		ServerLevel level = item.world() == null ? viewer.level()
				: io.github.alphain24.staffcore.modules.replay.ReplayStage.levelOf(server, item.world());
		if (level == null) return refuse(viewer, "This server no longer has " + item.world() + ".");

		viewer.closeContainer();
		Mods.teleport().toPosition(viewer, level, item.pos().getX() + 0.5, item.pos().getY(),
				item.pos().getZ() + 0.5);
		viewer.sendSystemMessage(Theme.good("Taken to " + item.where() + "."));
		return true;
	}

	private static boolean snapshot(ServerPlayer viewer, CaseEvidence.Item item) {
		if (!allowed(viewer, Nodes.INVSEE)) return false;
		if (item.subjectId() == null) return refuse(viewer, "This snapshot does not say whose it is.");

		io.github.alphain24.staffcore.gui.menu.SnapshotsMenu.open(viewer,
				new NameAndId(item.subjectId(), item.subjectName() == null ? "?" : item.subjectName()));
		return true;
	}

	private static boolean allowed(ServerPlayer viewer, String node) {
		if (Permissions.check(viewer, node)) return true;
		return refuse(viewer, "Opening this needs " + node + ".");
	}

	private static boolean refuse(ServerPlayer viewer, String why) {
		viewer.sendSystemMessage(Theme.bad(why));
		Sfx.deny(viewer);
		return false;
	}
}
