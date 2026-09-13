package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Browsing cases. <b>Browsing only</b> — nothing here changes anything.
 * <p>
 * That is a deliberate limit rather than an unfinished screen, and it is worth being explicit
 * because the missing buttons look like an omission. A server-side container UI desyncs: the
 * client's idea of which item is in which slot can differ from the server's for a few hundred
 * milliseconds after any change, and the server resolves a click by slot index. In a screen
 * that lists players and can issue punishments, that is a mis-click away from banning somebody
 * who happened to be one row up.
 * <p>
 * The list is also live — cases open, gain severity and get claimed while somebody is looking
 * at it — so the row under the cursor is exactly the thing most likely to have moved.
 * <p>
 * So clicking a case closes the screen and prints the chat view, where every action is a
 * command with a name, a permission check and an audit line. The GUI is for finding the case;
 * chat is for doing anything about it.
 */
public class CasesMenu extends PagedGui<Case> {

	/** Which slice of the list is being shown. Filtering is the one thing this screen does. */
	private Case.Status filter = null;
	private boolean mineOnly;
	/** Only one kind of case, or every kind. */
	private io.github.alphain24.staffcore.modules.cases.CaseCategory kind = null;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Cases"), CasesMenu::new);
	}

	private CasesMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<Case> entries() {
		return Mods.cases().store().list(filter, mineOnly ? Mc.name(viewer) : null, kind, 0, 200);
	}

	@Override
	protected ItemStack header() {
		int open = Mods.cases().openCount();

		Icon icon = Icon.of(Items.WRITABLE_BOOK)
				.name("Cases", Theme.ACCENT)
				.field("Open", String.valueOf(open))
				.field("Showing", filter == null ? "everything" : filter.stored())
				.field("Kind", kind == null ? "every kind" : kind.label())
				.field("Assignee", mineOnly ? Mc.name(viewer) : "anyone")
				.gap()
				.paragraph("Strongest first, then newest.", Theme.MUTED)
				.gap()
				.action("Left-click", "cycle the status filter")
				.action("Shift-click", "cycle the kind of case")
				.action("Right-click", "show only cases assigned to you");

		if (open > 0) icon.glow();
		return icon.build();
	}

	@Override
	protected ItemStack icon(Case subject) {
		String name = subject.subjectName() == null
				? subject.subjectId().toString() : subject.subjectName();

		Icon icon = Icon.of(iconFor(subject.category()))
				.name(subject.category().label() + " · " + subject.id(), Theme.ACCENT)
				.field("Subject", name)
				.field("Severity", String.valueOf(subject.severity()))
				.field("Status", subject.status().stored())
				.field("Assigned", subject.assignedTo() == null ? "nobody" : subject.assignedTo())
				.gap()
				.field("Opened", TimeFormat.ago(subject.openedAt()) + " by " + subject.openedBy());

		if (subject.summary() != null && !subject.summary().isBlank()) {
			icon.gap().paragraph(subject.summary(), Theme.TEXT);
		}

		icon.gap().action("Click", "open the case");

		// Severity is the reason the list is ordered the way it is, so it should be visible
		// without reading the tooltip.
		if (subject.status().isLive() && subject.severity() >= 70) icon.glow();
		return icon.build();
	}

	/** One look per kind of case, so a list of them can be read at a glance. */
	static net.minecraft.world.item.Item iconFor(
			io.github.alphain24.staffcore.modules.cases.CaseCategory category) {
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
		// Its own screen now: the evidence has to be clickable, and chat cannot open a replay
		// without a typed command. The full chat view is one button away on that screen.
		Sfx.page(viewer);
		CaseMenu.open(viewer, subject.id());
	}

	/**
	 * Re-binds the header as a button so the filter can be cycled.
	 * <p>
	 * {@code PagedGui} paints the header as a plain item, which is right for screens whose
	 * header is only a label. Filtering is the one thing this screen does, so it needs the
	 * slot to be clickable.
	 */
	@Override
	protected void build() {
		super.build();
		button(SLOT_HEADER, header(), click -> {
			if (click.isShift()) kind = nextKind(kind);
			else if (click.isRight()) mineOnly = !mineOnly;
			else filter = nextFilter(filter);

			Sfx.toggleOn(viewer);
			render();
		});
	}

	/** Everything, then each status in turn, then back to everything. */
	private static io.github.alphain24.staffcore.modules.cases.CaseCategory nextKind(
			io.github.alphain24.staffcore.modules.cases.CaseCategory current) {
		var all = io.github.alphain24.staffcore.modules.cases.CaseCategory.values();
		if (current == null) return all[0];
		return current.ordinal() + 1 >= all.length ? null : all[current.ordinal() + 1];
	}

	private static Case.Status nextFilter(Case.Status current) {
		if (current == null) return Case.Status.OPEN;
		Case.Status[] all = Case.Status.values();
		int next = current.ordinal() + 1;
		return next >= all.length ? null : all[next];
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
