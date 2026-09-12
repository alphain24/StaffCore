package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.analytics.AnalyticsModule;
import io.github.alphain24.staffcore.util.PlayerLookup;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * One staff member's record, on one screen.
 * <p>
 * The leaderboard ranks people, which is useful for spotting who has stopped showing up and
 * almost useless for anything else: a count of actions says nothing about what those actions
 * were. This is the other view — what this person has actually been doing, broken down, with
 * the numbers that qualify it sitting next to the numbers that flatter it.
 * <p>
 * It is deliberately as reachable for the person themselves as for whoever reviews them.
 * A record staff cannot see is one they cannot correct.
 */
public final class StaffSnapshotMenu extends Gui {

	private static final int SLOT_HEAD = 4;
	private static final int SLOT_TOTALS = 20;
	private static final int SLOT_PUNISH = 21;
	private static final int SLOT_QUALITY = 22;
	private static final int SLOT_COMMANDS = 23;
	private static final int SLOT_RECENT = 24;
	private static final int SLOT_BACK = 45;
	private static final int SLOT_CLOSE = 53;

	private final String staffName;
	private final Runnable back;

	public static void open(ServerPlayer viewer, String staffName, Runnable back) {
		Sfx.open(viewer);
		Guis.silent(viewer, Theme.title("Staff", staffName),
				(id, inv, v) -> new StaffSnapshotMenu(id, inv, v, staffName, back));
	}

	private StaffSnapshotMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String staffName, Runnable back) {
		super(containerId, playerInventory, viewer, 6);
		this.staffName = staffName;
		this.back = back;
		render();
	}

	@Override
	protected void build() {
		AnalyticsModule.Snapshot snap = Mods.analytics().snapshot(staffName);
		AnalyticsModule.StaffStat stat = snap.stat();
		MinecraftServer server = Mc.server(viewer);
		boolean online = server != null && server.getPlayerList().getPlayerByName(staffName) != null;

		Icon head = server == null
				? Icon.of(Items.PLAYER_HEAD)
				: PlayerLookup.profile(server, staffName).map(Icon::head).orElse(Icon.of(Items.PLAYER_HEAD));

		set(SLOT_HEAD, head
				.name(staffName, Theme.ACCENT)
				.field("Last active", stat.lastSeen() == 0
						? "never" : TimeFormat.ago(stat.lastSeen()))
				.gap()
				.state(online, "Online now", "Offline")
				.build());

		set(SLOT_TOTALS, Icon.of(Items.BOOK)
				.name("Activity", Theme.TEXT)
				.field("Actions", String.valueOf(stat.total()))
				.field("Punishments", String.valueOf(stat.punishments()))
				.field("Reports claimed", String.valueOf(stat.reportsHandled()))
				.field("Commands run", String.valueOf(stat.commands()))
				.gap()
				.lore("Actions counts punishments and claimed reports.", Theme.MUTED)
				.lore("Commands counts every staff command logged.", Theme.MUTED)
				.build());

		set(SLOT_PUNISH, breakdown(Items.NETHERITE_AXE, "What they issue", snap.byPunishment(),
				"No punishments on file.",
				"Forty warnings and forty bans are both forty",
				"actions. They are not the same record."));

		// The qualifying numbers, deliberately beside the flattering ones rather than on a
		// screen somebody has to go looking for.
		set(SLOT_QUALITY, Icon.of(Items.SPYGLASS)
				.name("Quality", Theme.TEXT)
				.field("Follow-through", stat.followThrough() + "%",
						stat.followThrough() >= 70 ? Theme.GOOD : Theme.WARN)
				.field("Overturned", stat.overturnRate() + "%",
						stat.overturnRate() <= 10 ? Theme.GOOD : Theme.BAD)
				.field("Median response", stat.medianResponseMs() == 0
						? "no data" : TimeFormat.duration(stat.medianResponseMs()))
				.gap()
				.lore("Follow-through: claimed reports actually closed.", Theme.MUTED)
				.lore("Overturned: their punishments later revoked.", Theme.MUTED)
				.lore("Response is approximate — see the handbook.", Theme.MUTED)
				.build());

		set(SLOT_COMMANDS, breakdown(Items.COMMAND_BLOCK, "Tools they reach for", snap.byCommand(),
				"No commands logged.",
				"Which parts of the mod this person actually",
				"uses — and which they have never opened."));

		Icon recent = Icon.of(Items.CLOCK)
				.name("Recent activity", Theme.TEXT)
				.gap();
		if (snap.recent().isEmpty()) {
			recent.lore("Nothing logged.", Theme.MUTED);
		} else {
			snap.recent().stream().limit(10).forEach(activity ->
					recent.lore(TimeFormat.ago(activity.at()) + " — " + trim(activity.command()),
							Theme.MUTED));
		}
		set(SLOT_RECENT, recent.build());

		if (snap.isEmpty()) {
			set(31, Icon.of(Items.STRUCTURE_VOID)
					.name("Nothing on file", Theme.MUTED)
					.lore("No punishments, reports or commands recorded", Theme.MUTED)
					.lore("for this name. Either they are new, or the", Theme.MUTED)
					.lore("name is spelled differently in the log.", Theme.MUTED)
					.build());
		}

		if (back != null) {
			backButton(SLOT_BACK, "the leaderboard", back);
		}
		button(SLOT_CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		for (int i = 0; i < size; i++) {
			if (backing.getItem(i).isEmpty()) set(i, Theme.filler());
		}
	}

	/** A counted breakdown as one icon, biggest first, capped so it stays readable. */
	private static ItemStack breakdown(net.minecraft.world.item.Item icon, String title,
			Map<String, Integer> counts, String empty, String... footnote) {

		Icon built = Icon.of(icon).name(title, Theme.TEXT).gap();
		if (counts.isEmpty()) {
			built.lore(empty, Theme.MUTED);
		} else {
			int shown = 0;
			for (var entry : counts.entrySet()) {
				if (shown++ >= 8) {
					built.lore("… and " + (counts.size() - 8) + " more", Theme.MUTED);
					break;
				}
				built.lore(entry.getValue() + "× " + entry.getKey(), Theme.TEXT);
			}
		}
		built.gap();
		for (String line : footnote) built.lore(line, Theme.MUTED);
		return built.build();
	}

	/** Command lines are longer than a lore row. */
	private static String trim(String command) {
		return command.length() <= 34 ? command : command.substring(0, 33) + "…";
	}
}
