package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Why. The last step before confirmation.
 * <p>
 * Presets only — a chest GUI has no text field, and that turns out to be a feature: a
 * fixed vocabulary makes punishment history searchable and comparable across a staff team
 * in a way that free text never is. Anyone who genuinely needs a bespoke reason can use
 * the command form, which is spelled out on the last icon.
 */
public class ReasonMenu extends Gui {

	private static final int HEADER = 4;
	private static final int[] SLOTS = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34
	};
	private static final int CUSTOM = 40;
	private static final int BACK = 36;
	private static final int CLOSE = 44;

	private final PunishDraft draft;

	public static void open(ServerPlayer viewer, PunishDraft draft) {
		Guis.navigate(viewer, Theme.title("Punish", draft.target().name(), "Reason"),
				(id, inv, v) -> new ReasonMenu(id, inv, v, draft));
	}

	private ReasonMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, PunishDraft draft) {
		super(containerId, playerInventory, viewer, 5);
		this.draft = draft;
		render();
	}

	@Override
	protected void build() {
		set(HEADER, Icon.head(draft.target())
				.name("Why?", Theme.ACCENT)
				.field("Punishment", draft.resolvedType().label(), draft.resolvedType().color())
				.field("Duration", draft.durationLabel())
				.field("Target", draft.target().name())
				.build());

		List<String> reasons = StaffConfig.get().presetReasons;
		for (int i = 0; i < reasons.size() && i < SLOTS.length; i++) {
			place(SLOTS[i], reasons.get(i));
		}

		// Clicking fills the command in rather than only naming it. A preset is a shortcut
		// and must never be the only option, and an option somebody has to retype from a
		// tooltip while a player waits is one they take the nearest preset instead of.
		String command = "/staff " + commandFor() + " " + draft.target().name()
				+ (draft.base().supportsDuration() ? " " + durationSpec() : "") + " ";

		button(CUSTOM, Icon.of(Items.NAME_TAG)
				.name("Something else?", Theme.ACCENT)
				.paragraph("The panel offers presets so history stays comparable across a "
						+ "team. Click to fill in the command with your own reason:", Theme.MUTED)
				.gap()
				.field("Command", command + "<reason>")
				.gap()
				.lore("Add presets in config/staffcore.json.")
				.build(),
				click -> {
					Sfx.click(viewer);
					viewer.closeContainer();
					viewer.sendSystemMessage(io.github.alphain24.staffcore.gui.Link.suggest(
							"[type your own reason]", command,
							Theme.ACCENT, "Fills the command in. Nothing happens until you "
									+ "add a reason and send it."));
				});

		backButton(BACK, draft.base().supportsDuration() ? "the duration list" : "the punish menu",
				() -> {
					if (draft.base().supportsDuration()) DurationMenu.open(viewer, draft);
					else ManualPunishMenu.reopen(viewer, draft.target());
				});
		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}

	/**
	 * The duration already chosen, in the form the command parses.
	 * <p>
	 * A duration picked on the previous screen and then retyped by hand is a duration that
	 * can differ from the one on screen, which is the sort of discrepancy that only surfaces
	 * in an appeal.
	 */
	private String durationSpec() {
		Long ms = draft.durationMs();
		if (ms == null) return "perm";

		long seconds = ms / 1000;
		if (seconds % 604_800 == 0) return (seconds / 604_800) + "w";
		if (seconds % 86_400 == 0) return (seconds / 86_400) + "d";
		if (seconds % 3_600 == 0) return (seconds / 3_600) + "h";
		if (seconds % 60 == 0) return (seconds / 60) + "m";
		return seconds + "s";
	}

	private String commandFor() {
		return switch (draft.base()) {
			case WARN -> "warn";
			case KICK -> "kick";
			case MUTE -> draft.durationMs() == null ? "mute" : "tempmute";
			case BAN -> draft.durationMs() == null ? "ban" : "tempban";
			default -> "punish";
		};
	}

	private void place(int slot, String reason) {
		button(slot, Icon.of(Items.PAPER)
				.name(reason, Theme.TEXT)
				.gap()
				.action("Click", "review and confirm")
				.build(), click -> {
			Sfx.click(viewer);
			confirm(draft.withReason(reason));
		});
	}

	// -------------------------------------------------------------------- confirm

	private void confirm(PunishDraft finished) {
		ItemStack summary = Icon.of(finished.resolvedType().icon())
				.name(finished.resolvedType().label() + " " + finished.target().name(),
						finished.resolvedType().color())
				.field("Reason", finished.reason())
				.field("Duration", finished.durationLabel())
				.field("Issued by", Mc.name(viewer))
				.gap()
				.warn(consequence(finished))
				.build();

		ConfirmMenu.open(viewer, finished.resolvedType().label(), summary,
				() -> apply(finished),
				() -> open(viewer, finished));
	}

	private static String consequence(PunishDraft d) {
		return switch (d.base()) {
			case WARN -> "They get a message. Nothing else changes.";
			case KICK -> "They are disconnected immediately.";
			case MUTE -> "They cannot talk until it expires or is lifted.";
			case BAN -> "They are disconnected and cannot rejoin.";
			default -> "";
		};
	}

	private void apply(PunishDraft finished) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		Punishment result = Mods.punish().apply(server, finished.target(), Mc.name(viewer),
				finished.base(), finished.durationMs(), finished.reason(), null, null,
				io.github.alphain24.staffcore.permission.Actor.of(viewer));

		if (result == null) {
			viewer.sendSystemMessage(Theme.bad("The punishment could not be saved — check the server log."));
			Sfx.error(viewer);
		} else {
			viewer.sendSystemMessage(Theme.good(
					result.type().label() + " applied to " + finished.target().name() + "."));
			Sfx.bigSuccess(viewer);
		}
		PlayerActionsMenu.reopen(viewer, finished.target());
	}
}
