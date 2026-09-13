package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.AddressBans;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * IP banning one player from their file: pick how long and why, read what it will catch, confirm.
 * <p>
 * Everything the command refuses is said here before the reasons are even clickable — no address
 * on record, an address that is really a proxy, an address a dozen accounts share — so nobody
 * gets as far as a confirm screen for a ban that cannot happen. The address itself is never on
 * screen; see {@link AddressBans} for why.
 */
public final class AddressBanMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] REASONS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25};
	private static final int PERMANENT = 29;
	private static final int MONTH = 31;
	private static final int WEEK = 33;
	private static final int LIFT = 40;
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private static final long DAY = 86_400_000L;

	private final NameAndId target;
	/** Null for permanent. */
	private Long durationMs = null;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("IP ban", target.name()),
				(id, inv, v) -> new AddressBanMenu(id, inv, v, target));
	}

	private AddressBanMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {
		super(containerId, playerInventory, viewer, 5);
		this.target = target;
		render();
	}

	@Override
	protected void build() {
		MinecraftServer server = Mc.server(viewer);
		AddressBans bans = Mods.punish().addressBans();
		AddressBans.Check check = bans.check(server, target);
		List<AddressBans.AddressBan> current = bans.forSource(target.id()).stream()
				.filter(AddressBans.AddressBan::inForce).toList();

		Icon header = Icon.head(target)
				.name("IP ban " + target.name(), Theme.BAD)
				.lore("Bans the account, and the internet connection it")
				.lore("last joined from, so a new account from there is")
				.lore("refused at the door.")
				.gap();
		if (check.refusal() != null) {
			header.warn(check.refusal());
		} else {
			header.field("Other accounts on that connection",
					String.valueOf(check.othersSharing().size()),
					check.othersSharing().isEmpty() ? Theme.MUTED : Theme.WARN);
			check.othersSharing().stream().limit(5).forEach(name -> header.lore("  " + name, Theme.MUTED));
			if (!check.othersSharing().isEmpty()) {
				header.warn("They will be refused too, unless they are operators.");
			}
		}
		if (Mods.accountability().approvals().required(
				io.github.alphain24.staffcore.modules.accountability.Approvals.Action.IP_BAN)) {
			header.gap().lore("A second staff member has to approve it.", Theme.ACCENT);
		}
		if (!current.isEmpty()) header.gap().warn(current.size() + " IP ban(s) already in force.");
		set(HEADER, header.build());

		if (check.refusal() == null) {
			List<String> reasons = StaffConfig.get().presetReasons;
			for (int i = 0; i < reasons.size() && i < REASONS.length; i++) {
				String reason = reasons.get(i);
				button(REASONS[i], Icon.of(Items.PAPER)
						.name(reason, Theme.ACCENT)
						.field("Length", durationLabel())
						.gap()
						.action("Click", "IP ban for this")
						.build(), click -> confirm(reason));
			}
			duration(PERMANENT, "Permanent", null);
			duration(MONTH, "30 days", 30 * DAY);
			duration(WEEK, "7 days", 7 * DAY);
		}

		if (!current.isEmpty() && Permissions.check(viewer, Nodes.UNPUNISH)) {
			AddressBans.AddressBan newest = current.get(0);
			button(LIFT, Icon.of(Items.TOTEM_OF_UNDYING)
					.name("Lift the IP ban", Theme.GOOD)
					.field("#", String.valueOf(newest.id()))
					.field("By", newest.staffName()
							+ (newest.approvedBy() == null ? "" : ", approved by " + newest.approvedBy()))
					.field("Since", TimeFormat.ago(newest.createdAt()))
					.field("Refused logins", String.valueOf(newest.refused()))
					.gap()
					.lore("The connection only. " + target.name() + "'s own", Theme.MUTED)
					.lore("account ban stays until it is lifted too.", Theme.MUTED)
					.build(), click -> ConfirmMenu.open(viewer, "Lift IP ban",
					Icon.head(target).name("Lift the IP ban taken from " + target.name(), Theme.GOOD)
							.lore("Anybody on that connection can join again.")
							.build(),
					() -> {
						int n = Mods.punish().addressBans().liftFor(target.id(), Mc.name(viewer), null);
						if (n > 0 && server != null) {
							Mods.alerts().onStaffAction(server, Mc.name(viewer)
									+ " lifted the IP ban taken from " + target.name());
						}
						viewer.sendSystemMessage(n > 0
								? Theme.good("Lifted " + n + " IP ban(s) taken from " + target.name() + ".")
								: Theme.warn("There was no IP ban to lift."));
						PlayerActionsMenu.reopen(viewer, target);
					},
					() -> open(viewer, target)));
		}

		backButton(BACK, target.name() + "'s file", () -> PlayerActionsMenu.reopen(viewer, target));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private void duration(int slot, String label, Long ms) {
		boolean chosen = java.util.Objects.equals(durationMs, ms);
		Icon icon = Icon.of(chosen ? Items.CLOCK : Items.PAPER)
				.name(label, chosen ? Theme.GOOD : Theme.MUTED)
				.lore(chosen ? "Chosen." : "Click to choose.", Theme.MUTED);
		if (chosen) icon.glow();
		button(slot, icon.build(), click -> {
			durationMs = ms;
			Sfx.toggleOn(viewer);
			render();
		});
	}

	private String durationLabel() {
		return durationMs == null ? "permanent" : TimeFormat.length(durationMs);
	}

	private void confirm(String reason) {
		Long length = durationMs;
		ConfirmMenu.open(viewer, "IP ban",
				Icon.head(target)
						.name("IP ban " + target.name(), Theme.BAD)
						.field("Reason", reason)
						.field("Length", durationLabel())
						.gap()
						.warn("Everybody on that connection is refused, not only them.")
						.build(),
				() -> {
					MinecraftServer server = Mc.server(viewer);
					var outcome = Mods.punish().addressBans().request(server, Actor.of(viewer),
							target, length, reason, null);
					switch (outcome.kind()) {
						case REFUSED -> {
							viewer.sendSystemMessage(Theme.bad(outcome.message()));
							Sfx.deny(viewer);
						}
						case STAGED -> {
							viewer.sendSystemMessage(Theme.info(outcome.message()));
							Sfx.success(viewer);
						}
						case BANNED -> {
							viewer.sendSystemMessage(Theme.good(outcome.message()));
							Sfx.bigSuccess(viewer);
						}
					}
					PlayerActionsMenu.reopen(viewer, target);
				},
				() -> open(viewer, target));
	}
}
