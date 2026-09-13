package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Every punishment one staff member has issued — who it was against, what for, whether it still
 * stands, and the case behind it — newest first.
 * <p>
 * The history screen answers "what has this player had". This answers the other question an
 * owner asks about their team: "what has this person been doing to players". A case is one click
 * from each row, because the evidence for a punishment lives in its case, not in the punishment.
 */
public final class IssuedPunishmentsMenu extends PagedGui<Punishment> {

	private static final int SLOT_KIND = 47;

	/** What to show: every kind, or one. */
	private enum Kind {
		ALL("every kind"), BANS("bans"), MUTES("mutes"), KICKS("kicks"), WARNINGS("warnings");

		final String label;

		Kind(String label) {
			this.label = label;
		}

		boolean matches(Punishment p) {
			return switch (this) {
				case ALL -> true;
				case BANS -> p.type().isBan();
				case MUTES -> p.type().isMute();
				case KICKS -> p.type() == io.github.alphain24.staffcore.modules.punish.PunishmentType.KICK;
				case WARNINGS -> p.type() == io.github.alphain24.staffcore.modules.punish.PunishmentType.WARN;
			};
		}
	}

	/** Null for everybody's. */
	private final String staffName;
	private Kind kind = Kind.ALL;

	public static void open(ServerPlayer viewer, String staffName) {
		Guis.navigate(viewer, Theme.title("Issued", staffName == null ? "everyone" : staffName),
				(id, inv, v) -> new IssuedPunishmentsMenu(id, inv, v, staffName));
	}

	private IssuedPunishmentsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String staffName) {
		super(containerId, playerInventory, viewer);
		this.staffName = staffName;
		render();
	}

	@Override
	protected List<Punishment> entries() {
		return Mods.punish().issuedBy(staffName, 500).stream().filter(kind::matches).toList();
	}

	@Override
	protected ItemStack header() {
		List<Punishment> all = Mods.punish().issuedBy(staffName, 500);
		long inForce = all.stream().filter(Punishment::inForce).filter(p -> p.type().persistent()).count();
		long lifted = all.stream().filter(p -> p.revokedBy() != null).count();
		long withCase = all.stream().filter(Punishment::hasCase).count();

		return Icon.of(Items.NETHERITE_AXE)
				.name(staffName == null ? "Every punishment issued" : "Issued by " + staffName, Theme.ACCENT)
				.field("Showing", kind.label)
				.field("On record", String.valueOf(all.size()))
				.field("Still in force", String.valueOf(inForce))
				.field("Lifted later", String.valueOf(lifted))
				.field("From a case", String.valueOf(withCase))
				.gap()
				.lore("Newest first.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(Punishment p) {
		boolean live = p.inForce() && p.type().persistent();
		Icon icon = Icon.of(p.type().icon())
				.name(p.type().label() + " · " + p.targetName(), live ? p.type().color() : Theme.MUTED)
				.field("Reason", p.reasonOr("No reason given"));
		if (staffName == null) icon.field("By", p.staffName() == null ? "console" : p.staffName());
		icon.field("When", TimeFormat.ago(p.createdAt()) + " (" + TimeFormat.stamp(p.createdAt()) + ")")
				.field("Reference", "#" + p.id());
		if (p.type().persistent()) {
			icon.field("Expires", p.remaining(), p.isPermanent() ? Theme.BAD : Theme.WARN);
		}
		icon.field("Case", p.hasCase() ? p.caseId() : "none", p.hasCase() ? Theme.ACCENT : Theme.MUTED)
				.gap();

		if (live) {
			icon.lore("● Still in force", Theme.BAD).glow();
		} else if (p.revokedBy() != null) {
			icon.lore("○ Lifted by " + p.revokedBy()
					+ (p.revokeReason() == null || p.revokeReason().isBlank() ? "" : " — " + p.revokeReason()),
					Theme.GOOD);
		} else if (p.type().persistent()) {
			icon.lore("○ Expired", Theme.MUTED);
		} else {
			icon.lore("○ One-off", Theme.MUTED);
		}

		icon.gap();
		if (p.hasCase()) icon.action("Click", "open case " + p.caseId() + " and its evidence");
		else icon.action("Click", "open " + p.targetName() + "'s file");
		if (p.hasCase()) icon.action("Right-click", "open " + p.targetName() + "'s file");
		icon.action("Shift-click", "their whole history");
		return icon.build();
	}

	@Override
	protected void onPick(Punishment p, Click click) {
		NameAndId target = new NameAndId(p.targetUuid(), p.targetName());
		Sfx.page(viewer);
		if (click.isShift()) {
			HistoryMenu.open(viewer, target);
		} else if (p.hasCase() && !click.isRight()) {
			CaseMenu.open(viewer, p.caseId());
		} else {
			PlayerActionsMenu.open(viewer, target);
		}
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_KIND, Icon.of(Items.HOPPER)
				.name("Showing " + kind.label, Theme.ACCENT)
				.action("Click", "next kind")
				.build(), click -> {
			kind = Kind.values()[(kind.ordinal() + 1) % Kind.values().length];
			resetPage();
			Sfx.toggleOn(viewer);
			render();
		});
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nothing issued", Theme.MUTED)
				.lore(staffName == null ? "Nobody has punished anybody yet."
						: staffName + " has not issued any " + (kind == Kind.ALL ? "punishments" : kind.label) + ".")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPunishmentsMenu.open(viewer);
	}

	@Override
	protected String backLabel() {
		return "Punishments by staff";
	}
}
