package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseClosing;
import io.github.alphain24.staffcore.modules.cases.Resolution;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Closing a case: what was done about it, or why nothing needed doing.
 * <pre>
 *   .  .  .  .  CASE .  .  .  .
 *   A  .  ■  ■  ■    ■  ■  .  A     Actioned — with a punishment they got, or something else
 *   C  .  ■  ■  ■    ■  ■  .  C     Cleared — with the reason why
 *   ←  .  .  .  .    .  .  .  ✕
 * </pre>
 * Actioned is a punishment picked from the ones issued since the case opened, so the case history
 * says what they got and the reason for it without anybody retyping it. The reason for clearing
 * is a button because it is what the threshold evidence counts: "looked and they were innocent"
 * and "nobody got round to it" have to be different rows.
 */
public final class CaseCloseMenu extends Gui {

	private static final int TITLE = 4;
	private static final int ACTIONED = 9;
	private static final int CLEARED = 18;
	private static final int BACK = 27;
	private static final int CLOSE = 35;

	/** What a staff member can clear a case for. Going stale is the server's call, not theirs. */
	private static final Resolution[] CLEARABLE = {
			Resolution.INVESTIGATED_INNOCENT, Resolution.INVESTIGATED_UNCLEAR,
			Resolution.NOT_INVESTIGATED, Resolution.SUBJECT_LEFT, Resolution.DUPLICATE};

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId, "Close"),
				(id, inv, v) -> new CaseCloseMenu(id, inv, v, caseId));
	}

	private CaseCloseMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId) {
		super(containerId, playerInventory, viewer, 4);
		this.caseId = caseId;
		render();
	}

	@Override
	protected void build() {
		backButton(BACK, "the case", () -> CaseMenu.open(viewer, caseId));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());

		Case found = Mods.cases().store().byId(caseId).orElse(null);
		if (found == null) {
			set(TITLE, Icon.of(Items.BARRIER).name("Case " + caseId + " could not be read", Theme.BAD).build());
			fillEmpty(Theme.filler());
			return;
		}
		String subject = found.subjectName() == null ? "them" : found.subjectName();

		set(TITLE, Icon.of(Items.IRON_DOOR)
				.name("Close case " + caseId, Theme.ACCENT)
				.field("About", subject)
				.field("Status", found.status().stored())
				.gap()
				.lore("Actioned: pick the punishment they got, and its", Theme.MUTED)
				.lore("type and reason go into the case history.", Theme.MUTED)
				.lore("Cleared: pick why nothing needed doing.", Theme.MUTED)
				.build());

		actioned(found, subject);
		cleared(found);
		fillEmpty(Theme.filler());
	}

	private void actioned(Case found, String subject) {
		label(ACTIONED, DyeColor.RED, "Actioned", "Something was done about it.");

		List<Punishment> issued = CaseClosing.issuedSinceOpened(found);
		int shown = Math.min(issued.size(), 4);
		for (int i = 0; i < shown; i++) {
			Punishment p = issued.get(i);
			ItemStack icon = Icon.of(p.type().icon())
					.name("Actioned: " + CaseClosing.label(p), Theme.BAD)
					.field("Reason", p.reasonOr("no reason given"))
					.field("By", p.staffName() == null ? "console" : p.staffName())
					.field("Issued", TimeFormat.ago(p.createdAt()))
					.gap()
					.lore("Closes the case with this punishment, and puts", Theme.MUTED)
					.lore("its type and reason in the case history.", Theme.MUTED)
					.action("Click", "close the case with it")
					.build();
			button(ACTIONED + 2 + i, icon, click -> {
				String me = Mc.name(viewer);
				if (CaseClosing.actionedWith(found, p, me)) {
					viewer.sendSystemMessage(Theme.good("Case " + caseId + " actioned: "
							+ CaseClosing.describe(p) + "."));
					Sfx.success(viewer);
				} else {
					viewer.sendSystemMessage(Theme.bad("The case could not be closed."));
					Sfx.error(viewer);
				}
				CasesMenu.open(viewer);
			});
		}

		if (issued.isEmpty()) {
			set(ACTIONED + 2, Icon.of(Mc.pane(DyeColor.GRAY))
					.name("Nothing issued yet", Theme.MUTED)
					.lore(subject + " has not been punished since this case opened.", Theme.MUTED)
					.build());
			if (Permissions.check(viewer, Nodes.PUNISH)) {
				NameAndId who = new NameAndId(found.subjectId(),
						found.subjectName() == null ? found.subjectId().toString() : found.subjectName());
				button(ACTIONED + 3, Icon.of(Items.IRON_SWORD)
						.name("Punish " + subject + " first", Theme.BAD)
						.lore("Whatever you issue is linked to this case. Come", Theme.MUTED)
						.lore("back here after and close the case with it.", Theme.MUTED)
						.action("Click", "open the punishment screen")
						.build(), click -> {
					io.github.alphain24.staffcore.gui.PunishFromCase.remember(viewer.getUUID(),
							found.id(), found.subjectId());
					PunishMenu.open(viewer, who);
				});
			}
		}

		button(ACTIONED + 6, Icon.of(Items.WRITABLE_BOOK)
				.name("Something else was done…", Theme.ACCENT)
				.lore("Not a punishment — say what, in chat. The", Theme.MUTED)
				.lore("command is typed out for you.", Theme.MUTED)
				.action("Click", "write it")
				.build(), click -> {
			viewer.closeContainer();
			viewer.sendSystemMessage(Theme.info("Finish the line and press enter: ")
					.append(Icon.text("[/staff case " + caseId + " actioned ...]", Theme.ACCENT)
							.withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(
									"/staff case " + caseId + " actioned ")))));
		});
	}

	private void cleared(Case found) {
		label(CLEARED, DyeColor.LIME, "Cleared", "Nothing needed doing, and why.");

		for (int i = 0; i < CLEARABLE.length; i++) {
			Resolution reason = CLEARABLE[i];
			button(CLEARED + 2 + i, Icon.of(Mc.dye(reason.countsAsNegative() ? DyeColor.LIME : DyeColor.GRAY))
					.name("Cleared: " + reason.label(), Theme.ACCENT)
					.lore(reason.countsAsNegative()
							? "Counts as the detector being wrong about them."
							: "Not counted either way.", Theme.MUTED)
					.action("Click", "close the case")
					.build(), click -> {
				Mods.cases().store().setStatus(found.id(), Case.Status.CLEARED, Mc.name(viewer),
						reason.label(), reason);
				viewer.sendSystemMessage(Theme.good("Case " + caseId + " cleared: " + reason.label() + "."));
				Sfx.success(viewer);
				CasesMenu.open(viewer);
			});
		}
	}

	private void label(int row, DyeColor colour, String name, String what) {
		ItemStack label = Icon.of(Mc.pane(colour)).name(name, Theme.ACCENT).lore(what, Theme.MUTED).build();
		set(row, label);
		set(row + 8, label.copy());
	}
}
