package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Signal;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** What the detectors said about one case, newest first, with how sure each was. */
public final class CaseSignalsMenu extends PagedGui<Signal> {

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId, "Signals"),
				(id, inv, v) -> new CaseSignalsMenu(id, inv, v, caseId));
	}

	private CaseSignalsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId) {
		super(containerId, playerInventory, viewer);
		this.caseId = caseId;
		render();
	}

	@Override
	protected List<Signal> entries() {
		return Mods.cases().store().signalsFor(caseId);
	}

	@Override
	protected ItemStack header() {
		return Icon.of(Items.PAPER)
				.name("Detector signals on case " + caseId, Theme.ACCENT)
				.field("Signals", String.valueOf(entries().size()))
				.gap()
				.lore("What each detector saw and how sure it was.", Theme.MUTED)
				.lore("A signal is a lead, not a verdict.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("No signals", Theme.MUTED)
				.lore("This case was opened by hand or from a report.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(Signal signal) {
		Icon icon = Icon.of(Items.PAPER)
				.name(signal.type().label(), signal.confidence() >= 70 ? Theme.BAD : Theme.ACCENT)
				.field("Confidence", signal.confidence() + "%",
						signal.confidence() >= 70 ? Theme.BAD : Theme.MUTED)
				.field("When", TimeFormat.ago(signal.occurredAt()));
		String detail = signal.evidenceJson();
		if (detail != null && !detail.isBlank() && !detail.equals("{}")) {
			icon.gap().paragraph(detail.length() > 160 ? detail.substring(0, 157) + "…" : detail,
					Theme.MUTED);
			icon.gap().action("Click", "print the whole detail in chat");
		}
		return icon.build();
	}

	@Override
	protected void onPick(Signal signal, Click click) {
		String detail = signal.evidenceJson();
		if (detail == null || detail.isBlank() || detail.equals("{}")) {
			Sfx.deny(viewer);
			return;
		}
		viewer.sendSystemMessage(Theme.info(signal.type().label() + " — " + signal.confidence()
				+ "%, " + TimeFormat.ago(signal.occurredAt()) + ": " + detail));
		Sfx.click(viewer);
	}

	@Override
	protected Runnable backTarget() {
		return () -> CaseMenu.open(viewer, caseId);
	}

	@Override
	protected String backLabel() {
		return "the case";
	}
}
