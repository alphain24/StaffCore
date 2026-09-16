package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * How long a rejected appeal makes the player wait before appealing that punishment again.
 * <p>
 * Chosen by whoever rejects it, because the right wait depends on the appeal: a copy-pasted "unban
 * me" earns a month, an honest account that simply did not convince earns a few days. The server's
 * {@code appealCooldownDays} is the one already marked. The player's ban screen shows the new appeal
 * code and the day it starts working.
 */
public class AppealWaitMenu extends Gui {

	/** The waits offered, in days. */
	static final List<Integer> CHOICES = List.of(0, 1, 3, 7, 14, 30, 90);
	private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};
	private static final int BACK = 18;

	private final String subject;
	private final IntConsumer chosen;
	private final Runnable back;

	/**
	 * @param subject whose appeal, for the title
	 * @param chosen  given the wait picked, in days
	 * @param back    where Back goes
	 */
	public static void open(ServerPlayer viewer, String subject, IntConsumer chosen, Runnable back) {
		Guis.navigate(viewer, Theme.title("Reject appeal", "Wait"),
				(id, inv, v) -> new AppealWaitMenu(id, inv, v, subject, chosen, back));
	}

	private AppealWaitMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, String subject,
			IntConsumer chosen, Runnable back) {
		super(containerId, playerInventory, viewer, 3);
		this.subject = subject;
		this.chosen = chosen;
		this.back = back;
		render();
	}

	@Override
	protected void build() {
		int preset = StaffConfig.get().appealCooldownDays;
		for (int i = 0; i < CHOICES.size(); i++) {
			int days = CHOICES.get(i);
			boolean marked = days == preset;
			Icon icon = Icon.of(days == 0 ? Items.CLOCK : Mc.concrete(days >= 30 ? DyeColor.RED
							: days >= 7 ? DyeColor.ORANGE : DyeColor.YELLOW))
					.count(Math.max(1, days))
					.name(label(days), marked ? Theme.ACCENT : Theme.TEXT)
					.lore("Before " + subject + " can appeal this again.", Theme.MUTED)
					.gap()
					.lore(days == 0 ? "Their ban screen shows a new code at once."
							: "Their ban screen shows a new code, and the day it starts working.", Theme.MUTED);
			if (marked) icon.gap().lore("This server's usual wait.", Theme.ACCENT).glow();
			button(SLOTS[i], icon.build(), click -> {
				Sfx.click(viewer);
				chosen.accept(days);
			});
		}
		backButton(BACK, "the appeals", back);
		fillEmpty(Theme.filler());
	}

	static String label(int days) {
		return days == 0 ? "No wait" : days == 1 ? "1 day" : days + " days";
	}
}
