package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.AddressBans;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everybody banned right now, who banned them, and the way to their file to lift it.
 * <p>
 * Lifting is not on this screen. It is on the player's file, behind its confirmation, where the
 * rest of what is known about them — history, notes, linked accounts, the case — is in front of
 * whoever decides. A list of names is the wrong place to reverse a ban from.
 */
public final class BannedPlayersMenu extends PagedGui<Punishment> {

	private static final int SLOT_STAFF = 47;
	private static final int SLOT_ALL = 51;

	/** Only bans by this staff member, or null for everybody's. */
	private String staffName;

	public static void open(ServerPlayer viewer) {
		open(viewer, null);
	}

	public static void open(ServerPlayer viewer, String staffName) {
		Guis.navigate(viewer, Theme.title("Banned", staffName == null ? "everyone" : "by " + staffName),
				(id, inv, v) -> new BannedPlayersMenu(id, inv, v, staffName));
	}

	private BannedPlayersMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String staffName) {
		super(containerId, playerInventory, viewer);
		this.staffName = staffName;
		render();
	}

	@Override
	protected List<Punishment> entries() {
		return Mods.punish().bansInForce(staffName, 1000);
	}

	/** Players whose connection is also banned, for the marker on their row. */
	private Map<UUID, AddressBans.AddressBan> addressBans() {
		return Mods.punish().addressBans().inForce(1000).stream()
				.filter(ban -> ban.sourceId() != null)
				.collect(Collectors.toMap(AddressBans.AddressBan::sourceId, ban -> ban,
						(a, b) -> a));
	}

	@Override
	protected ItemStack header() {
		List<Punishment> bans = entries();
		long permanent = bans.stream().filter(Punishment::isPermanent).count();
		return Icon.of(Items.IRON_BARS)
				.name(staffName == null ? "Banned players" : "Banned by " + staffName, Theme.ACCENT)
				.field("In force", String.valueOf(bans.size()))
				.field("Permanent", String.valueOf(permanent))
				.field("Temporary", String.valueOf(bans.size() - permanent))
				.field("Connections banned", String.valueOf(Mods.punish().addressBans().inForce(1000).size()))
				.gap()
				.lore("Newest first. Click somebody to open their file,", Theme.MUTED)
				.lore("where the ban can be lifted.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(Punishment ban) {
		NameAndId target = new NameAndId(ban.targetUuid(), ban.targetName());
		AddressBans.AddressBan connection = addressBans().get(ban.targetUuid());

		Icon icon = Icon.head(target)
				.name(ban.targetName(), Theme.BAD)
				.field("Banned by", ban.staffName() == null ? "console" : ban.staffName())
				.field("Reason", ban.reasonOr("No reason given"))
				.field("Since", TimeFormat.ago(ban.createdAt()) + " (" + TimeFormat.stamp(ban.createdAt()) + ")")
				.field("Ends", ban.isPermanent() ? "never" : TimeFormat.stamp(ban.expiresAt()),
						ban.isPermanent() ? Theme.BAD : Theme.WARN)
				.field("Reference", "#" + ban.id())
				.field("Case", ban.hasCase() ? ban.caseId() : "none", ban.hasCase() ? Theme.ACCENT : Theme.MUTED);
		if (connection != null) {
			icon.gap().warn("Their connection is banned too (IP ban #" + connection.id() + ")");
			if (connection.approvedBy() != null) icon.lore("Approved by " + connection.approvedBy(), Theme.MUTED);
			if (connection.refused() > 0) {
				icon.lore(connection.refused() + " other login(s) refused, last "
						+ (connection.lastRefusedName() == null ? "" : connection.lastRefusedName()), Theme.MUTED);
			}
		}
		icon.gap()
				.action("Click", "open their file (lift the ban there)");
		if (ban.hasCase()) icon.action("Right-click", "open case " + ban.caseId());
		icon.action("Shift-click", "their whole history");
		return icon.build();
	}

	@Override
	protected void onPick(Punishment ban, Click click) {
		NameAndId target = new NameAndId(ban.targetUuid(), ban.targetName());
		Sfx.page(viewer);
		if (click.isShift()) HistoryMenu.open(viewer, target);
		else if (click.isRight() && ban.hasCase()) CaseMenu.open(viewer, ban.caseId());
		else PlayerActionsMenu.open(viewer, target);
	}

	@Override
	protected void decorateFooter() {
		// Cycles through whoever has bans in force, so "every ban Steve issued that still
		// stands" is a click or two rather than a scroll.
		List<String> issuers = new ArrayList<>();
		for (Punishment ban : Mods.punish().bansInForce(null, 1000)) {
			String name = ban.staffName() == null ? null : ban.staffName();
			if (name != null && issuers.stream().noneMatch(n -> n.equalsIgnoreCase(name))) issuers.add(name);
		}
		button(SLOT_STAFF, Icon.of(Items.PLAYER_HEAD)
				.name(staffName == null ? "Banned by: anybody" : "Banned by: " + staffName, Theme.ACCENT)
				.lore(issuers.size() + " staff member(s) have bans in force.", Theme.MUTED)
				.action("Click", "next staff member")
				.build(), click -> {
			if (issuers.isEmpty()) return;
			int at = -1;
			for (int i = 0; i < issuers.size(); i++) {
				if (issuers.get(i).equalsIgnoreCase(String.valueOf(staffName))) at = i;
			}
			staffName = at + 1 >= issuers.size() ? null : issuers.get(at + 1);
			resetPage();
			Sfx.toggleOn(viewer);
			render();
		});

		if (staffName != null) {
			button(SLOT_ALL, Icon.of(Items.BARRIER)
					.name("Show everybody's", Theme.ACCENT)
					.build(), click -> {
				staffName = null;
				resetPage();
				Sfx.toggleOn(viewer);
				render();
			});
		}
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nobody is banned", Theme.GOOD)
				.lore(staffName == null ? "No bans are in force." : "No ban by " + staffName + " is in force.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffSections.punishments(viewer);
	}

	@Override
	protected String backLabel() {
		return "Punishments";
	}
}
