package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.analytics.AnalyticsModule;
import dev.lebron.staffcore.util.PlayerLookup;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Who on the team is doing what.
 * <p>
 * Ordered by punishments issued, which is the only number the database can rank on — and
 * which is exactly why the header says out loud that volume is not quality. Click a name
 * to open their file and read the punishments themselves.
 */
public class AnalyticsMenu extends PagedGui<AnalyticsModule.StaffStat> {

	/** Staff with no logged command in this long are called out as quiet. */
	private static final long INACTIVE_AFTER = 14L * 86_400_000L;

	private static final int SLOT_TOTALS = 47;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Analytics"), AnalyticsMenu::new);
	}

	private AnalyticsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<AnalyticsModule.StaffStat> entries() {
		return Mods.analytics().leaderboard(50);
	}

	@Override
	protected ItemStack icon(AnalyticsModule.StaffStat stat) {
		boolean quiet = stat.lastSeen() == 0L
				|| stat.lastSeen() < System.currentTimeMillis() - INACTIVE_AFTER;

		Icon icon = profileIcon(stat.name())
				.name(stat.name(), quiet ? Theme.MUTED : Theme.ACCENT)
				.field("Punishments issued", String.valueOf(stat.punishments()))
				.field("Reports claimed", String.valueOf(stat.reportsHandled()))
				.field("Commands logged", String.valueOf(stat.commands()));

		// Counting actions rewards volume, so the numbers that are not volume sit alongside
		// them rather than underneath. Somebody with two bans who closes every report they
		// claim is doing the harder job, and this is where that becomes visible.
		icon.gap();
		if (stat.reportsHandled() > 0) {
			icon.field("Seen through", stat.reportsResolved() + " of " + stat.reportsHandled()
					+ " (" + stat.followThrough() + "%)",
					stat.followThrough() >= 80 ? Theme.GOOD
							: stat.followThrough() >= 50 ? Theme.WARN : Theme.BAD);
		}
		if (stat.medianResponseMs() > 0) {
			icon.field("Typical response", TimeFormat.duration(stat.medianResponseMs()));
		}
		if (stat.punishments() > 0) {
			icon.field("Later revoked", stat.overturned() + " (" + stat.overturnRate() + "%)",
					stat.overturnRate() >= 25 ? Theme.WARN : Theme.MUTED);
		}

		icon.gap()
				.field("Last activity", stat.lastSeen() == 0L ? "never" : TimeFormat.ago(stat.lastSeen()));

		if (quiet) icon.warn("Quiet for over two weeks.");
		icon.gap()
				.lore("Numbers, not judgement. Go and read the work.", Theme.MUTED)
				.action("Left-click", "read their record")
				.action("Right-click", "open their player file");
		return icon.build();
	}

	private Icon profileIcon(String name) {
		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			var profile = PlayerLookup.profile(server, name);
			if (profile.isPresent()) return Icon.head(profile.get());
		}
		return Icon.of(Items.PLAYER_HEAD);
	}

	@Override
	protected void onPick(AnalyticsModule.StaffStat stat, Click click) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		// Left-click reads the record, right-click opens the person. The record is the more
		// common thing to want from here and used to be unreachable entirely — the only way
		// in was a row on a leaderboard that showed four numbers out of a dozen.
		if (!click.isRight()) {
			StaffSnapshotMenu.open(viewer, stat.name(), () -> open(viewer));
			return;
		}

		var profile = PlayerLookup.profile(server, stat.name());
		if (profile.isEmpty()) {
			viewer.sendSystemMessage(Theme.warn("Could not resolve " + stat.name() + " to a profile."));
			Sfx.deny(viewer);
			return;
		}
		PlayerActionsMenu.open(viewer, profile.get());
	}

	@Override
	protected ItemStack header() {
		AnalyticsModule.Totals totals = Mods.analytics().totals();
		return Icon.of(Items.MAP)
				.name("Staff Analytics", Theme.ACCENT)
				.field("Punishments on record", String.valueOf(totals.punishments()))
				.field("Bans in force", String.valueOf(totals.activeBans()),
						totals.activeBans() > 0 ? Theme.WARN : Theme.MUTED)
				.field("Reports waiting", String.valueOf(totals.openReports()),
						totals.openReports() > 0 ? Theme.WARN : Theme.MUTED)
				.field("Notes written", String.valueOf(totals.notes()))
				.gap()
				.paragraph("Ranked by punishments issued because that is what the database can "
						+ "count. It is a prompt to go and read someone's work, not a score.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.MAP)
				.name("No activity yet", Theme.MUTED)
				.lore("Nobody has issued a punishment on this server.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPanelMenu.reopen(viewer);
	}

	@Override
	protected void decorateFooter() {
		set(SLOT_TOTALS, Icon.of(Items.ENCHANTED_BOOK)
				.name("Reading this", Theme.MUTED)
				.paragraph("Command counts come from the audit log, which only records "
						+ "StaffCore commands. Someone who works mostly through the panel will "
						+ "look quieter than they are.", Theme.MUTED)
				.build());
	}
}
