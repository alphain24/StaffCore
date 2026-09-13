package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseEvidence;
import io.github.alphain24.staffcore.modules.cases.EvidenceViewer;
import io.github.alphain24.staffcore.modules.cases.Signal;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * One case: what kind it is, who it is about, what the detectors said, and the evidence to
 * open — the replay, the block damage, the place.
 * <p>
 * The chat view is still there and still complete; this is the screen for acting on a case
 * without typing its id.
 */
public final class CaseMenu extends Gui {

	private static final int HEADER = 4;
	private static final int CLAIM = 10;
	private static final int INVESTIGATING = 11;
	private static final int CLOSE_CASE = 12;
	private static final int CATEGORY = 14;
	private static final int SIGNALS = 15;
	private static final int READ_IN_CHAT = 16;
	private static final int[] EVIDENCE = {
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34};
	private static final int BACK = 45;
	private static final int FILE_EVIDENCE = 47;
	private static final int CLOSE = 49;

	private final String caseId;

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Case", caseId),
				(id, inv, v) -> new CaseMenu(id, inv, v, caseId));
	}

	static void reopen(ServerPlayer viewer, String caseId) {
		Guis.silent(viewer, Theme.title("Case", caseId),
				(id, inv, v) -> new CaseMenu(id, inv, v, caseId));
	}

	private CaseMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, String caseId) {
		super(containerId, playerInventory, viewer, 6);
		this.caseId = caseId;
		render();
	}

	@Override
	protected void build() {
		Case found = Mods.cases().store().byId(caseId).orElse(null);
		if (found == null) {
			set(22, Icon.of(Items.BARRIER)
					.name("Case " + caseId + " could not be read", Theme.BAD)
					.lore("Cases are never deleted, so storage is unavailable.")
					.build());
			backButton(BACK, "Cases", () -> CasesMenu.open(viewer));
			button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
			fillEmpty(Theme.filler());
			return;
		}

		set(HEADER, header(found));
		actions(found);
		evidence(found);

		backButton(BACK, "Cases", () -> CasesMenu.open(viewer));
		button(FILE_EVIDENCE, Icon.of(Items.WRITABLE_BOOK)
				.name("File evidence", Theme.ACCENT)
				.lore("A replay of " + name(found) + ", the block damage")
				.lore("around you, where you stand, or their inventory.")
				.build(), click -> CaseEvidenceMenu.open(viewer, found.id()));
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	private ItemStack header(Case found) {
		Icon icon = Icon.of(CasesMenu.iconFor(found.category()))
				.name(found.category().label() + " case " + found.id(), Theme.ACCENT)
				.field("Subject", name(found))
				.field("Status", found.status().stored())
				.field("Severity", String.valueOf(found.severity()))
				.field("Assigned", found.assignedTo() == null ? "nobody" : found.assignedTo())
				.field("Opened", TimeFormat.ago(found.openedAt()) + " by " + found.openedBy());
		if (found.summary() != null && !found.summary().isBlank()) {
			icon.gap().paragraph(found.summary(), Theme.TEXT);
		}
		if (found.status().isLive() && found.severity() >= 70) icon.glow();
		return icon.build();
	}

	private void actions(Case found) {
		String me = Mc.name(viewer);

		button(CLAIM, Icon.of(Items.NAME_TAG)
				.name(me.equals(found.assignedTo()) ? "Assigned to you" : "Claim", Theme.ACCENT)
				.lore(me.equals(found.assignedTo()) ? "Click to unassign yourself."
						: "Assign this case to yourself.")
				.build(), click -> {
			Mods.cases().store().assign(found.id(), me.equals(found.assignedTo()) ? null : me, me);
			Sfx.click(viewer);
			render();
		});

		button(INVESTIGATING, Icon.of(Items.SPYGLASS)
				.name("Mark investigating", found.status() == Case.Status.INVESTIGATING
						? Theme.MUTED : Theme.ACCENT)
				.lore("Somebody is working on it.")
				.build(), click -> {
			if (found.status() != Case.Status.INVESTIGATING) {
				Mods.cases().store().setStatus(found.id(), Case.Status.INVESTIGATING, me, null);
				Sfx.click(viewer);
			}
			render();
		});

		button(CLOSE_CASE, Icon.of(Items.IRON_DOOR)
				.name("Close the case", Theme.ACCENT)
				.lore("Cleared, with the reason why — or actioned.")
				.build(), click -> CaseCloseMenu.open(viewer, found.id()));

		CaseCategory[] all = CaseCategory.values();
		button(CATEGORY, Icon.of(CasesMenu.iconFor(found.category()))
				.name("Kind: " + found.category().label(), Theme.ACCENT)
				.lore("Reports are sorted by their words, which can be wrong.")
				.gap()
				.action("Click", "next kind")
				.action("Right-click", "previous kind")
				.build(), click -> {
			int step = click.isRight() ? all.length - 1 : 1;
			CaseCategory next = all[(found.category().ordinal() + step) % all.length];
			if (Mods.cases().store().setCategory(found.id(), next, me)) {
				Sfx.click(viewer);
			} else {
				viewer.sendSystemMessage(Theme.bad(name(found) + " already has an open "
						+ next.label() + " case. Add to that one instead."));
				Sfx.deny(viewer);
			}
			render();
		});

		List<Signal> signals = Mods.cases().store().signalsFor(found.id());
		Icon signalIcon = Icon.of(Items.PAPER)
				.name("What the detectors said (" + signals.size() + ")", Theme.ACCENT);
		signals.stream().limit(6).forEach(signal -> signalIcon.lore(TimeFormat.ago(signal.occurredAt())
				+ "  " + signal.type().label() + "  " + signal.confidence() + "%", Theme.TEXT));
		if (signals.size() > 6) signalIcon.lore("… and " + (signals.size() - 6) + " more", Theme.MUTED);
		set(SIGNALS, signalIcon.build());

		button(READ_IN_CHAT, Icon.of(Items.BOOK)
				.name("Read the whole case in chat", Theme.ACCENT)
				.lore("Every signal, note and event, oldest first.")
				.build(), click -> {
			viewer.closeContainer();
			io.github.alphain24.staffcore.modules.cases.CaseView.print(
					viewer.createCommandSourceStack(), found);
		});
	}

	private void evidence(Case found) {
		List<CaseEvidence.Item> items = Mods.cases().evidence().forCase(found.id());
		if (items.isEmpty()) {
			set(EVIDENCE[3], Icon.of(Items.GLASS_PANE)
					.name("No evidence filed yet", Theme.MUTED)
					.lore("Detectors file a replay and the damage when they")
					.lore("open a case. File your own with the book below.")
					.build());
			return;
		}

		for (int i = 0; i < items.size() && i < EVIDENCE.length; i++) {
			CaseEvidence.Item item = items.get(i);
			Icon icon = Icon.of(iconFor(item.kind()))
					.name("#" + item.id() + " " + item.kind().label(), Theme.ACCENT)
					.lore(item.describe(), Theme.TEXT);
			if (item.label() != null && !item.label().isBlank()) icon.lore(item.label(), Theme.MUTED);
			icon.field("Filed", TimeFormat.ago(item.addedAt()) + " by " + item.addedBy())
					.gap()
					.action("Click", "open it")
					.action("Shift-click", "retract it");

			button(EVIDENCE[i], icon.build(), click -> {
				if (click.isShift()) {
					ConfirmMenu.open(viewer, "Retract #" + item.id(),
							new ItemStack(iconFor(item.kind())),
							() -> {
								Mods.cases().evidence().retract(item.id(), Mc.name(viewer));
								reopen(viewer, found.id());
							},
							() -> reopen(viewer, found.id()));
					return;
				}
				EvidenceViewer.open(viewer, item);
			});
		}
		if (items.size() > EVIDENCE.length) {
			viewer.sendSystemMessage(Theme.info("Case " + found.id() + " has " + items.size()
					+ " pieces of evidence; the screen shows the first " + EVIDENCE.length
					+ ". /staff case " + found.id() + " evidence lists them all."));
		}
	}

	static Item iconFor(CaseEvidence.Kind kind) {
		return switch (kind) {
			case REPLAY -> Items.RECOVERY_COMPASS;
			case BLOCKS -> Items.TNT;
			case LOCATION -> Items.ENDER_PEARL;
			case SNAPSHOT -> Items.CHEST;
			case XRAY_DIG -> Items.DIAMOND_PICKAXE;
		};
	}

	private static String name(Case found) {
		return found.subjectName() == null ? found.subjectId().toString() : found.subjectName();
	}
}
