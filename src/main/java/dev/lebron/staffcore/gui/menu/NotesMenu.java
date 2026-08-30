package dev.lebron.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.notes.NotesModule;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import dev.lebron.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Staff notes on a player — the informal half of their record.
 * <p>
 * Notes carry an author and a timestamp and cannot be edited, only deleted. That is
 * deliberate: the value of a note six months later is that it says what someone actually
 * thought at the time.
 */
public class NotesMenu extends PagedGui<NotesModule.Note> {

	private static final int SLOT_ADD = 47;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Notes", target.name()),
				(id, inv, v) -> new NotesMenu(id, inv, v, target));
	}

	private NotesMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<NotesModule.Note> entries() {
		return Mods.notes().list(target.id());
	}

	@Override
	protected ItemStack icon(NotesModule.Note note) {
		Icon icon = Icon.of(Items.PAPER)
				.name(note.author(), Theme.ACCENT)
				.paragraph(note.text(), Theme.TEXT)
				.gap()
				.field("Written", TimeFormat.ago(note.createdAt()))
				.field("Exact", TimeFormat.stamp(note.createdAt()));

		if (Permissions.check(viewer, Nodes.NOTES_REMOVE)) {
			icon.gap().action("Shift-click", "delete this note");
		}
		return icon.build();
	}

	@Override
	protected void onPick(NotesModule.Note note, Click click) {
		if (!click.isShift()) {
			Sfx.page(viewer);
			return;
		}
		if (!Permissions.check(viewer, Nodes.NOTES_REMOVE)) {
			viewer.sendSystemMessage(Theme.bad("You do not have " + Nodes.NOTES_REMOVE + "."));
			Sfx.deny(viewer);
			return;
		}

		ConfirmMenu.open(viewer, "Delete note",
				Icon.of(Items.PAPER)
						.name("Delete this note", Theme.BAD)
						.field("Author", note.author())
						.paragraph(note.text(), Theme.MUTED)
						.build(),
				() -> {
					if (Mods.notes().remove(note.id())) {
						viewer.sendSystemMessage(Theme.info("Note deleted."));
						Sfx.success(viewer);
					} else {
						viewer.sendSystemMessage(Theme.bad("That note was already gone."));
						Sfx.deny(viewer);
					}
					open(viewer, target);
				},
				() -> open(viewer, target));
	}

	@Override
	protected ItemStack header() {
		return Icon.head(target)
				.name(target.name() + "'s notes", Theme.ACCENT)
				.field("Notes", String.valueOf(entries().size()))
				.gap()
				.lore("Newest first.")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.WRITABLE_BOOK)
				.name("No notes yet", Theme.MUTED)
				.lore("Nobody has written anything about " + target.name() + ".")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> PlayerActionsMenu.reopen(viewer, target);
	}

	@Override
	protected String backLabel() {
		return target.name() + "'s file";
	}

	@Override
	protected void decorateFooter() {
		if (!Permissions.check(viewer, Nodes.NOTES)) return;

		set(SLOT_ADD, Icon.of(Items.WRITABLE_BOOK)
				.name("Add a note", Theme.GOOD)
				.paragraph("Notes are free text, so they need a command — a chest menu has "
						+ "nowhere to type.", Theme.MUTED)
				.gap()
				.field("Command", "/staff notes " + target.name() + " add <text>")
				.build());
	}
}
