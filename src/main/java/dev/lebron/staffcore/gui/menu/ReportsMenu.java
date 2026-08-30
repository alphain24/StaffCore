package dev.lebron.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.report.ReportModule;
import dev.lebron.staffcore.util.PlayerLookup;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * The report queue: open reports first, oldest at the top.
 * <p>
 * Claiming is the whole point of the screen. An unclaimed queue is two staff members
 * teleporting to the same person while a third report ages out, so claiming is one click
 * and the claimant's name is on the icon from then on.
 */
public class ReportsMenu extends PagedGui<ReportModule.Report> {

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Reports"), ReportsMenu::new);
	}

	static void reopen(ServerPlayer viewer) {
		Guis.silent(viewer, Theme.title("Reports"), ReportsMenu::new);
	}

	private ReportsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<ReportModule.Report> entries() {
		return Mods.reports().queue();
	}

	@Override
	protected ItemStack icon(ReportModule.Report report) {
		boolean open = "OPEN".equals(report.status());
		boolean mine = Mc.name(viewer).equals(report.claimedBy());
		boolean online = onlineTarget(report) != null;

		Icon icon = Icon.of(open ? Items.PAPER : Items.MAP)
				.name(report.targetName(), open ? Theme.WARN : Theme.TEXT)
				.field("Reported by", report.reporter())
				.paragraph(report.reason(), Theme.TEXT)
				.gap()
				.field("Filed", TimeFormat.ago(report.createdAt()))
				.state(online, "Target is online", "Target is offline");

		if (open) {
			icon.lore("● Waiting for someone", Theme.WARN).glow();
			icon.gap().action("Left-click", "claim it");
		} else {
			icon.lore("○ Claimed by " + report.claimedBy(), mine ? Theme.GOOD : Theme.MUTED);
			icon.gap();
			if (mine) icon.action("Left-click", "mark it resolved");
			else icon.action("Left-click", "take it over");
		}
		icon.action("Right-click", "open the reported player's file");
		if (online) icon.action("Shift-click", "teleport to them");

		return icon.build();
	}

	@Override
	protected void onPick(ReportModule.Report report, Click click) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		if (click.isRight()) {
			Optional<NameAndId> profile =
					PlayerLookup.profile(server, report.targetName());
			if (profile.isEmpty()) {
				viewer.sendSystemMessage(Theme.bad("Could not resolve " + report.targetName() + "."));
				Sfx.deny(viewer);
				return;
			}
			PlayerActionsMenu.open(viewer, profile.get());
			return;
		}

		if (click.isShift()) {
			ServerPlayer target = onlineTarget(report);
			if (target == null) {
				viewer.sendSystemMessage(Theme.warn(report.targetName() + " is offline."));
				Sfx.deny(viewer);
				return;
			}
			Mods.teleport().toPlayer(viewer, target);
			viewer.sendSystemMessage(Theme.info("Teleported to " + report.targetName() + "."));
			viewer.closeContainer();
			return;
		}

		boolean mine = Mc.name(viewer).equals(report.claimedBy());
		if (mine) {
			resolve(server, report);
		} else {
			claim(server, report);
		}
	}

	private void claim(MinecraftServer server, ReportModule.Report report) {
		// Taking over someone else's claim means releasing it first: `claim` only moves a
		// report out of OPEN, which is what makes two staff racing for the same one safe.
		if ("CLAIMED".equals(report.status())) {
			Mods.reports().unclaim(report.id());
		}

		if (Mods.reports().claim(report.id(), Mc.name(viewer))) {
			viewer.sendSystemMessage(Theme.good("Claimed report #" + report.id()
					+ " against " + report.targetName() + "."));
			Mods.alerts().onStaffAction(server, Mc.name(viewer) + " claimed report #" + report.id());
			Sfx.success(viewer);
		} else {
			viewer.sendSystemMessage(Theme.warn("Somebody claimed that one first."));
			Sfx.deny(viewer);
		}
		render();
	}

	private void resolve(MinecraftServer server, ReportModule.Report report) {
		ConfirmMenu.open(viewer, "Resolve report",
				Icon.of(Mc.concrete(DyeColor.LIME))
						.name("Resolve report #" + report.id(), Theme.GOOD)
						.field("Against", report.targetName())
						.paragraph(report.reason(), Theme.MUTED)
						.gap()
						.lore("It leaves the queue. The record stays in the database.")
						.build(),
				() -> {
					Mods.reports().resolve(report.id(), Mc.name(viewer));
					viewer.sendSystemMessage(Theme.good("Report #" + report.id() + " resolved."));
					Mods.alerts().onStaffAction(server, Mc.name(viewer) + " resolved report #" + report.id());
					Sfx.bigSuccess(viewer);
					reopen(viewer);
				},
				() -> reopen(viewer));
	}

	private ServerPlayer onlineTarget(ReportModule.Report report) {
		MinecraftServer server = Mc.server(viewer);
		return server == null ? null : server.getPlayerList().getPlayer(report.targetUuid());
	}

	// --------------------------------------------------------------------- chrome

	@Override
	protected ItemStack header() {
		List<ReportModule.Report> all = entries();
		long open = all.stream().filter(r -> "OPEN".equals(r.status())).count();

		return Icon.of(Items.PAPER)
				.name("Report Queue", Theme.ACCENT)
				.field("Waiting", String.valueOf(open), open > 0 ? Theme.WARN : Theme.GOOD)
				.field("In progress", String.valueOf(all.size() - open))
				.gap()
				.lore("Oldest unclaimed report is at the top.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("Queue is empty", Theme.GOOD)
				.lore("Nothing to answer for right now.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPanelMenu.reopen(viewer);
	}
}
