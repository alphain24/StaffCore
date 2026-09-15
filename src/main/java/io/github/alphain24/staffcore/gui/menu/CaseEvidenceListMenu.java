package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.EvidenceViewer;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The evidence filed on one case, or the x-ray digs recorded for it — one screen, two lists.
 * <p>
 * Kept off the case screen itself so that screen stays the same shape however much is filed,
 * and paged so the fifteenth piece of evidence is as reachable as the first.
 */
public final class CaseEvidenceListMenu extends PagedGui<CaseEvidence.Item> {

	private static final int SLOT_FILE = 51;

	private final String caseId;
	/** The x-ray digs, or everything else. */
	private final boolean digs;

	public static void open(ServerPlayer viewer, String caseId, boolean digs) {
		Guis.navigate(viewer, Theme.title("Case", caseId, digs ? "Dig info" : "Evidence"),
				(id, inv, v) -> new CaseEvidenceListMenu(id, inv, v, caseId, digs));
	}

	private static void reopen(ServerPlayer viewer, String caseId, boolean digs) {
		Guis.silent(viewer, Theme.title("Case", caseId, digs ? "Dig info" : "Evidence"),
				(id, inv, v) -> new CaseEvidenceListMenu(id, inv, v, caseId, digs));
	}

	private CaseEvidenceListMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId, boolean digs) {
		super(containerId, playerInventory, viewer);
		this.caseId = caseId;
		this.digs = digs;
		render();
	}

	@Override
	protected List<CaseEvidence.Item> entries() {
		return Mods.cases().evidence().forCase(caseId).stream()
				.filter(item -> (item.kind() == CaseEvidence.Kind.XRAY_DIG) == digs)
				.toList();
	}

	@Override
	protected ItemStack header() {
		int count = entries().size();
		Icon icon = Icon.of(digs ? Items.DIAMOND_PICKAXE : Items.CHEST)
				.name((digs ? "Dig info" : "Evidence") + " on case " + caseId, Theme.ACCENT)
				.field(digs ? "Digs" : "Filed", String.valueOf(count))
				.gap();
		if (digs) {
			icon.lore("The digs the x-ray detector recorded. Open one", Theme.MUTED)
					.lore("to watch it back from inside the tunnel.", Theme.MUTED);
		} else {
			icon.lore("Evidence points at a replay or the log; it does not", Theme.MUTED)
					.lore("copy them. It plays for as long as they are kept.", Theme.MUTED);
		}
		return icon.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return digs
				? Icon.of(Items.STRUCTURE_VOID)
						.name("No dig recorded", Theme.MUTED)
						.lore("An x-ray case files the dig that raised it.", Theme.MUTED)
						.build()
				: Icon.of(Items.STRUCTURE_VOID)
						.name("No evidence filed yet", Theme.MUTED)
						.lore("Detectors file a replay and the damage when they open", Theme.MUTED)
						.lore("a case. File your own with the book below.", Theme.MUTED)
						.build();
	}

	@Override
	protected ItemStack icon(CaseEvidence.Item item) {
		Icon icon = Icon.of(CaseMenu.iconFor(item.kind()))
				.name("#" + item.id() + " " + item.kind().label(), Theme.ACCENT)
				.lore(item.describe(), Theme.TEXT);
		if (item.label() != null && !item.label().isBlank()) icon.lore(item.label(), Theme.MUTED);
		return icon.field("Filed", TimeFormat.ago(item.addedAt()) + " by " + item.addedBy())
				.gap()
				.action("Click", "open it")
				.action("Shift-click", "retract it")
				.build();
	}

	@Override
	protected void onPick(CaseEvidence.Item item, Click click) {
		if (click.isShift()) {
			ConfirmMenu.open(viewer, "Retract #" + item.id(),
					Icon.of(CaseMenu.iconFor(item.kind()))
							.name("Retract #" + item.id() + " " + item.kind().label(), Theme.BAD)
							.lore(item.describe(), Theme.MUTED)
							.gap()
							.lore("It stays in the case history as retracted.")
							.build(),
					() -> {
						Mods.cases().evidence().retract(item.id(), Mc.name(viewer));
						reopen(viewer, caseId, digs);
					},
					() -> reopen(viewer, caseId, digs));
			return;
		}
		EvidenceViewer.open(viewer, item);
	}

	@Override
	protected void decorateFooter() {
		if (digs) return;
		button(SLOT_FILE, Icon.of(Items.WRITABLE_BOOK)
				.name("File evidence", Theme.ACCENT)
				.lore("A replay, the block damage around you, where you")
				.lore("stand, or their inventory.")
				.build(), click -> CaseEvidenceMenu.open(viewer, caseId));
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
