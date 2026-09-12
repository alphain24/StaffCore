package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * A yes/no gate in front of anything irreversible.
 * <p>
 * Confirm sits on the left and cancel on the right with the subject between them, and the
 * two are five slots apart. That gap is the entire point: a misclick on a menu that has
 * just changed under you should land on filler, not on "ban".
 */
public class ConfirmMenu extends Gui {

	private static final int CONFIRM = 11;
	private static final int SUBJECT = 13;
	private static final int CANCEL = 15;

	private final ItemStack subject;
	private final Runnable onConfirm;
	private final Runnable onCancel;

	/**
	 * When this screen was built.
	 * <p>
	 * A confirm screen holds a live action and can sit open indefinitely — through a lunch
	 * break, through the player logging off, through another staff member handling it. What
	 * the screen describes is the server as it was when the screen opened, so past
	 * {@code confirmExpirySeconds} the button refuses and says why rather than running an
	 * irreversible action against a world that has moved on.
	 */
	private final long openedAt = System.currentTimeMillis();

	public static void open(ServerPlayer viewer, String what, ItemStack subject,
			Runnable onConfirm, Runnable onCancel) {
		Guis.navigate(viewer, Theme.title("Confirm", what),
				(id, inv, v) -> new ConfirmMenu(id, inv, v, subject, onConfirm, onCancel));
	}

	private ConfirmMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			ItemStack subject, Runnable onConfirm, Runnable onCancel) {
		super(containerId, playerInventory, viewer, 3);
		this.subject = subject;
		this.onConfirm = onConfirm;
		this.onCancel = onCancel;
		render();
	}

	@Override
	protected void build() {
		set(SUBJECT, Icon.of(subject).glow().build());

		button(CONFIRM, Theme.confirmButton("Do it."), click -> {
			if (!io.github.alphain24.staffcore.command.StaffSession.stillFresh(openedAt)) {
				Sfx.deny(viewer);
				viewer.sendSystemMessage(Theme.bad(
						"This confirmation has expired. What it described was the server as it "
								+ "was when the screen opened; run the command again."));
				Guis.close(viewer);
				return;
			}
			Sfx.click(viewer);
			onConfirm.run();
		});

		button(CANCEL, Theme.cancelButton(), click -> {
			Sfx.back(viewer);
			onCancel.run();
		});

		fillEmpty(Theme.filler());
	}

	/**
	 * Never stepped back into. Rebuilding this screen from history would present "Do it." for
	 * an action that already ran — and with a fresh opening time, so the staleness check above
	 * would pass it. Whatever the action opens next takes this screen's place instead.
	 */
	@Override
	protected boolean returnable() {
		return false;
	}

	/** Backing out with Escape counts as cancelling, not as confirming. */
	@Override
	protected void onClosed() {
		// Intentionally does nothing: the caller's cancel path is for the cancel button.
		// Closing the window simply drops the pending action on the floor, which is the
		// safest possible interpretation of "the staff member walked away".
	}
}
