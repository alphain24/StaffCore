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

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Cases"), CasesMenu::new);
	}

	private CasesMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<Case> entries() {
		return Mods.cases().store().list(filter, mineOnly ? Mc.name(viewer) : null, 0, 200);
	}

	@Override
	protected ItemStack header() {
		int open = Mods.cases().openCount();

		Icon icon = Icon.of(Items.WRITABLE_BOOK)
				.name("Cases", Theme.ACCENT)
				.field("Open", String.valueOf(open))
				.field("Showing", filter == null ? "everything" : filter.stored())
				.field("Assignee", mineOnly ? Mc.name(viewer) : "anyone")
				.gap()
				.paragraph("Strongest first, then newest.", Theme.MUTED)
				.gap()
				.action("Left-click", "cycle the status filter")
				.action("Right-click", "show only cases assigned to you");

		if (open > 0) icon.glow();
		return icon.build();
	}

	@Override
	protected ItemStack icon(Case subject) {
		String name = subject.subjectName() == null
				? subject.subjectId().toString() : subject.subjectName();

		Icon icon = Icon.of(iconFor(subject))
				.name(subject.id(), Theme.ACCENT)
				.field("Subject", name)
				.field("Severity", String.valueOf(subject.severity()))
				.field("Status", subject.status().stored())
				.field("Assigned", subject.assignedTo() == null ? "nobody" : subject.assignedTo())
				.gap()
				.field("Opened", TimeFormat.ago(subject.openedAt()) + " by " + subject.openedBy());

		if (subject.summary() != null && !subject.summary().isBlank()) {
			icon.gap().paragraph(subject.summary(), Theme.TEXT);
		}

		icon.gap().action("Click", "read the case in chat");

		// Severity is the reason the list is ordered the way it is, so it should be visible
		// without reading the tooltip.
		if (subject.status().isLive() && subject.severity() >= 70) icon.glow();
		return icon.build();
	}

	private net.minecraft.world.item.Item iconFor(Case subject) {
		return switch (subject.status()) {
			case OPEN -> Items.PAPER;
			case INVESTIGATING -> Items.SPYGLASS;
			case ACTIONED -> Items.IRON_BARS;
			case CLEARED -> Mc.dye(net.minecraft.world.item.DyeColor.LIME);
			case STALE -> Items.COBWEB;
		};
	}

	@Override
	protected void onPick(Case subject, Click click) {
		// Closes rather than staying open, and that is the point of the screen ending here:
		// the case view is chat, chat is behind the container, and a case read through a
		// half-covered window is a case somebody skims.
		viewer.closeContainer();
		Sfx.page(viewer);

		var found = Mods.cases().store().byId(subject.id());
		if (found.isEmpty()) {
			// It was on screen a moment ago. Cases are never deleted, so this means storage
			// went away rather than that somebody removed it.
			viewer.sendSystemMessage(Theme.bad("Case " + subject.id() + " could not be read."));
			return;
		}
		io.github.alphain24.staffcore.modules.cases.CaseView.print(
				viewer.createCommandSourceStack(), found.get());
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
			if (click.isRight()) mineOnly = !mineOnly;
			else filter = nextFilter(filter);

			Sfx.toggleOn(viewer);
			render();
		});
	}

	/** Everything, then each status in turn, then back to everything. */
	private static Case.Status nextFilter(Case.Status current) {
		if (current == null) return Case.Status.OPEN;
		Case.Status[] all = Case.Status.values();
		int next = current.ordinal() + 1;
		return next >= all.length ? null : all[next];
	}
}
