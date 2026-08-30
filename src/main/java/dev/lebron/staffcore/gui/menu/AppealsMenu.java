package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.appeal.AppealModule;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import dev.lebron.staffcore.util.PlayerLookup;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Appeals waiting on a verdict.
 * <p>
 * Accepting one lifts the punishment it was made against, because an appeal that is
 * "accepted" but leaves the ban in place is worse than no appeal system at all. Rejecting
 * closes it with the staff member's name attached, so a second appeal arrives with the
 * history of the first.
 */
public class AppealsMenu extends PagedGui<AppealModule.Appeal> {

	/** Null for the whole queue; set to show one player's appeals. */
	private final NameAndId only;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Appeals"),
				(id, inv, v) -> new AppealsMenu(id, inv, v, null));
	}

	public static void openFor(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Appeals", target.name()),
				(id, inv, v) -> new AppealsMenu(id, inv, v, target));
	}

	private static void reopen(ServerPlayer viewer, NameAndId only) {
		Guis.silent(viewer, only == null ? Theme.title("Appeals") : Theme.title("Appeals", only.name()),
				(id, inv, v) -> new AppealsMenu(id, inv, v, only));
	}

	private AppealsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId only) {
		super(containerId, playerInventory, viewer);
		this.only = only;
		render();
	}

	@Override
	protected List<AppealModule.Appeal> entries() {
		return only == null ? Mods.appeals().queue() : Mods.appeals().forPlayer(only.id());
	}

	@Override
	protected ItemStack icon(AppealModule.Appeal appeal) {
		boolean open = "OPEN".equals(appeal.status());
		boolean accepted = "ACCEPTED".equals(appeal.verdict());

		Icon icon = headFor(appeal)
				.name(appeal.targetName(), open ? Theme.WARN : Theme.MUTED)
				.paragraph(appeal.text(), Theme.TEXT)
				.gap()
				.field("Filed", TimeFormat.ago(appeal.createdAt()));

		if (open) {
			icon.lore("● Waiting on a verdict", Theme.WARN).glow();
			if (Permissions.check(viewer, Nodes.APPEALS)) {
				icon.gap()
						.action("Left-click", "accept and lift the punishment")
						.action("Right-click", "reject");
			}
		} else {
			icon.lore(accepted ? "○ Accepted by " + appeal.handledBy() : "○ Rejected by " + appeal.handledBy(),
					accepted ? Theme.GOOD : Theme.BAD);
			if (appeal.handledAt() != null) {
				icon.field("Decided", TimeFormat.ago(appeal.handledAt()));
			}
		}
		icon.action("Shift-click", "open their file");
		return icon.build();
	}

	private Icon headFor(AppealModule.Appeal appeal) {
		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			var profile = PlayerLookup.profile(server, appeal.targetName());
			if (profile.isPresent()) return Icon.head(profile.get());
		}
		return Icon.of(Items.PLAYER_HEAD);
	}

	@Override
	protected void onPick(AppealModule.Appeal appeal, Click click) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		if (click.isShift()) {
			var profile = PlayerLookup.profile(server, appeal.targetName());
			if (profile.isEmpty()) {
				Sfx.deny(viewer);
				return;
			}
			PlayerActionsMenu.open(viewer, profile.get());
			return;
		}

		if (!"OPEN".equals(appeal.status())) {
			Sfx.page(viewer);
			return;
		}
		if (!Permissions.check(viewer, Nodes.APPEALS)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.APPEALS + "."));
			Sfx.deny(viewer);
			return;
		}

		if (click.isRight()) confirmVerdict(appeal, false);
		else confirmVerdict(appeal, true);
	}

	private void confirmVerdict(AppealModule.Appeal appeal, boolean accept) {
		ItemStack summary = Icon.of(accept ? Mc.concrete(DyeColor.LIME) : Mc.concrete(DyeColor.RED))
				.name((accept ? "Accept" : "Reject") + " " + appeal.targetName() + "'s appeal",
						accept ? Theme.GOOD : Theme.BAD)
				.paragraph(appeal.text(), Theme.MUTED)
				.gap()
				.lore(accept
						? "Their active ban and mute are both lifted."
						: "The punishment stands. They are told the outcome.", Theme.TEXT)
				.build();

		ConfirmMenu.open(viewer, accept ? "Accept appeal" : "Reject appeal", summary,
				() -> decide(appeal, accept),
				() -> reopen(viewer, only));
	}

	private void decide(AppealModule.Appeal appeal, boolean accept) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		String staff = Mc.name(viewer);
		boolean done = accept
				? Mods.appeals().accept(appeal.id(), staff)
				: Mods.appeals().reject(appeal.id(), staff);

		if (!done) {
			viewer.sendSystemMessage(Theme.warn("Somebody decided that one first."));
			Sfx.deny(viewer);
			reopen(viewer, only);
			return;
		}

		if (accept) {
			// An accepted appeal that leaves the ban in place is worse than none at all.
			int lifted = Mods.punish().revoke(server, appeal.targetUuid(), staff, true)
					+ Mods.punish().revoke(server, appeal.targetUuid(), staff, false);
			viewer.sendSystemMessage(Theme.good(
					"Appeal accepted — " + lifted + " punishment(s) lifted for " + appeal.targetName() + "."));
		} else {
			viewer.sendSystemMessage(Theme.info("Appeal rejected. The punishment stands."));
		}

		ServerPlayer online = server.getPlayerList().getPlayer(appeal.targetUuid());
		if (online != null) {
			online.sendSystemMessage(accept
					? Theme.good("Your appeal was accepted. Welcome back.")
					: Theme.bad("Your appeal was reviewed and rejected."));
		}

		Mods.alerts().onStaffAction(server, "%s %s %s's appeal"
				.formatted(staff, accept ? "accepted" : "rejected", appeal.targetName()));
		Sfx.bigSuccess(viewer);
		reopen(viewer, only);
	}

	@Override
	protected ItemStack header() {
		List<AppealModule.Appeal> all = entries();
		long open = all.stream().filter(a -> "OPEN".equals(a.status())).count();

		return Icon.of(Items.PAPER)
				.name(only == null ? "Appeal Queue" : only.name() + "'s appeals", Theme.ACCENT)
				.field("Waiting", String.valueOf(open), open > 0 ? Theme.WARN : Theme.GOOD)
				.field("Total", String.valueOf(all.size()))
				.gap()
				.lore(only == null ? "Oldest first." : "Newest first.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("No appeals", Theme.GOOD)
				.lore(only == null ? "Nothing waiting on a verdict." : "They have never appealed.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return only == null
				? () -> StaffPanelMenu.reopen(viewer)
				: () -> PlayerActionsMenu.reopen(viewer, only);
	}

	@Override
	protected String backLabel() {
		return only == null ? "the staff panel" : only.name() + "'s file";
	}
}
