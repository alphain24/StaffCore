package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseCategory;
import io.github.alphain24.staffcore.modules.cases.CaseStore;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The case board: open cases and solved ones, newest first.
 * <p>
 * Two tabs rather than a status filter to cycle through. "What still needs doing" and "what
 * was decided" are the two questions anybody opens this list with, and cycling five statuses to
 * get from one to the other made both of them a chore. Newest first, because the case that
 * opened a minute ago is the one somebody has just been told about.
 * <p>
 * Clicking a case opens it. Everything that changes a case happens on that screen, where the
 * case is re-read at the moment of the click — the list here can be seconds stale, and a row
 * that moved under the cursor must never be the one something is done to.
 */
public class CasesMenu extends PagedGui<Case> {

	private static final int SLOT_OPEN_TAB = 46;
	private static final int SLOT_SOLVED_TAB = 47;
	private static final int SLOT_KIND = 51;
	private static final int SLOT_MINE = 52;

	private CaseStore.Board board = CaseStore.Board.OPEN;
	private boolean mineOnly;
	/** Only one kind of case, or every kind. */
	private CaseCategory kind = null;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Cases"), CasesMenu::new);
	}

	private CasesMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<Case> entries() {
		return Mods.cases().store().board(board, mineOnly ? Mc.name(viewer) : null, kind, 0, 280);
	}

	@Override
	protected ItemStack header() {
		boolean open = board == CaseStore.Board.OPEN;
		Icon icon = Icon.of(open ? Items.WRITABLE_BOOK : Items.WRITTEN_BOOK)
				.name(open ? "Open cases" : "Solved cases", Theme.ACCENT)
				.field("Kind", kind == null ? "every kind" : kind.label())
				.field("Assignee", mineOnly ? Mc.name(viewer) : "anyone")
				.gap()
				.paragraph(open
						? "Open and investigating, newest opened first."
						: "Actioned, cleared and gone stale, most recently closed first.",
						Theme.MUTED);
		if (open && Mods.cases().openCount() > 0) icon.glow();
		return icon.build();
	}

	@Override
	protected ItemStack icon(Case subject) {
		String name = subject.subjectName() == null
				? subject.subjectId().toString() : subject.subjectName();

		Icon icon = Icon.of(iconFor(subject.category()))
				.name(subject.category().label() + " · " + subject.id(), Theme.ACCENT)
				.field("Subject", name)
				.field("Status", subject.status().stored())
				.field("Severity", String.valueOf(subject.severity()))
				.field("Assigned", subject.assignedTo() == null ? "nobody" : subject.assignedTo())
				.gap()
				.field("Opened", TimeFormat.ago(subject.openedAt()) + " by " + subject.openedBy());
		if (subject.closedAt() != null) {
			icon.field("Closed", TimeFormat.ago(subject.closedAt())
					+ (subject.closedBy() == null ? "" : " by " + subject.closedBy()));
		}
		if (subject.resolutionReason() != null) {
			icon.field("Outcome", subject.resolutionReason().label());
		}

		if (subject.summary() != null && !subject.summary().isBlank()) {
			icon.gap().paragraph(subject.summary(), Theme.TEXT);
		}

		icon.gap().action("Click", "open the case");

		if (subject.status().isLive() && subject.severity() >= 70) icon.glow();
		return icon.build();
	}

	/** One look per kind of case, so a list of them can be read at a glance. */
	static net.minecraft.world.item.Item iconFor(CaseCategory category) {
		return switch (category) {
			case GRIEFING -> Items.TNT;
			case CHEATING -> Items.DIAMOND_ORE;
			case ILLEGAL_ITEMS -> Items.BARRIER;
			case BAN_EVASION -> Items.NAME_TAG;
			case CHAT -> Items.WRITABLE_BOOK;
			case OTHER -> Items.PAPER;
		};
	}

	@Override
	protected void onPick(Case subject, Click click) {
		Sfx.page(viewer);
		CaseMenu.open(viewer, subject.id());
	}

	@Override
	protected void decorateFooter() {
		int open = Mods.cases().store().boardCount(CaseStore.Board.OPEN);
		int solved = Mods.cases().store().boardCount(CaseStore.Board.SOLVED);

		button(SLOT_OPEN_TAB, tab(Items.WRITABLE_BOOK, "Open cases", open,
				board == CaseStore.Board.OPEN), click -> show(CaseStore.Board.OPEN));
		button(SLOT_SOLVED_TAB, tab(Items.WRITTEN_BOOK, "Solved cases", solved,
				board == CaseStore.Board.SOLVED), click -> show(CaseStore.Board.SOLVED));

		button(SLOT_KIND, Icon.of(kind == null ? Items.COMPASS : iconFor(kind))
				.name("Kind: " + (kind == null ? "every kind" : kind.label()), Theme.ACCENT)
				.action("Click", "next kind")
				.action("Right-click", "previous kind")
				.build(), click -> {
			kind = click.isRight() ? previousKind(kind) : nextKind(kind);
			resetPage();
			Sfx.toggleOn(viewer);
			render();
		});

		button(SLOT_MINE, Icon.of(mineOnly ? Items.NAME_TAG : Items.PAPER)
				.name(mineOnly ? "Showing only yours" : "Showing everybody's", Theme.ACCENT)
				.action("Click", mineOnly ? "show everybody's" : "show only cases assigned to you")
				.build(), click -> {
			mineOnly = !mineOnly;
			resetPage();
			Sfx.toggleOn(viewer);
			render();
		});
	}

	private ItemStack tab(net.minecraft.world.item.Item item, String label, int count, boolean selected) {
		Icon icon = Icon.of(item)
				.name(label + " (" + count + ")", selected ? Theme.GOOD : Theme.ACCENT)
				.lore(selected ? "Showing now." : "Click to show.", Theme.MUTED);
		if (selected) icon.glow();
		return icon.build();
	}

	private void show(CaseStore.Board which) {
		if (board == which) return;
		board = which;
		resetPage();
		Sfx.page(viewer);
		render();
	}

	private static CaseCategory nextKind(CaseCategory current) {
		CaseCategory[] all = CaseCategory.values();
		if (current == null) return all[0];
		return current.ordinal() + 1 >= all.length ? null : all[current.ordinal() + 1];
	}

	private static CaseCategory previousKind(CaseCategory current) {
		CaseCategory[] all = CaseCategory.values();
		if (current == null) return all[all.length - 1];
		return current.ordinal() == 0 ? null : all[current.ordinal() - 1];
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffSections.security(viewer);
	}

	@Override
	protected String backLabel() {
		return "Security";
	}
}
