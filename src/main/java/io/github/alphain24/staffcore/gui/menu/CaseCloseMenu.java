package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.Resolution;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * Closing a case, with the reason as a button rather than a typed word.
 * <p>
 * The reason is what the threshold evidence counts, which is why clearing takes one: "looked
 * and they were innocent" and "nobody got round to it" have to be different rows.
 */
public final class CaseCloseMenu extends Gui {

	private static final int TITLE = 4;
	private static final int[] REASONS = {10, 11, 12, 13, 14, 15, 16};
	private static final int ACTIONED = 22;
	private static final int BACK = 27;
	private static final int CLOSE = 31;

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
		set(TITLE, Icon.of(Items.IRON_DOOR)
				.name("Close case " + caseId, Theme.ACCENT)
				.lore("Cleared: pick why. Actioned: say what was done.")
				.build());

		Resolution[] all = Resolution.values();
		for (int i = 0; i < all.length && i < REASONS.length; i++) {
			Resolution reason = all[i];
			button(REASONS[i], Icon.of(Mc.dye(reason.countsAsNegative() ? net.minecraft.world.item.DyeColor.LIME : net.minecraft.world.item.DyeColor.GRAY))
					.name("Cleared: " + reason.label(), Theme.ACCENT)
					.lore(reason.countsAsNegative()
							? "Counts as the detector being wrong about them."
							: "Not counted either way.", Theme.MUTED)
					.build(), click -> {
				Mods.cases().store().setStatus(caseId, Case.Status.CLEARED, Mc.name(viewer),
						reason.label(), reason);
				viewer.sendSystemMessage(Theme.good("Case " + caseId + " cleared: " + reason.label() + "."));
				Sfx.success(viewer);
				CasesMenu.open(viewer);
			});
		}

		button(ACTIONED, Icon.of(Items.IRON_BARS)
				.name("Actioned…", Theme.ACCENT)
				.lore("Something was done about it. Say what in chat —")
				.lore("the command is typed out for you.")
				.build(), click -> {
			viewer.closeContainer();
			viewer.sendSystemMessage(Theme.info("Finish the line and press enter: ")
					.append(Icon.text("[/staff case " + caseId + " actioned ...]", Theme.ACCENT)
							.withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(
									"/staff case " + caseId + " actioned ")))));
		});

		backButton(BACK, "the case", () -> CaseMenu.open(viewer, caseId));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}
}
