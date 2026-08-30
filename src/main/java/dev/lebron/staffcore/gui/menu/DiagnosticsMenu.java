package dev.lebron.staffcore.gui.menu;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.diagnostic.StartupCheck;
import dev.lebron.staffcore.gui.Guis;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.PagedGui;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Whether StaffCore is actually working, without reading the log.
 * <p>
 * Almost every mixin here is deliberately non-fatal: an update that moves an injection point
 * costs you that feature rather than your server. The price of that trade is that a broken
 * feature looks exactly like a working one from inside the game — vanish stops hiding people
 * and nothing anywhere says so, which is how a moderation tool becomes untrustworthy.
 * <p>
 * The startup check already answers this; it just answered it once, into a log that scrolled
 * past during boot. An admin wondering an hour later whether vanish is working had nowhere to
 * look. Here it is, permanently, in the place they already are.
 */
public class DiagnosticsMenu extends PagedGui<StartupCheck.Finding> {

	private static final int SLOT_STORAGE = 47;
	private static final int SLOT_BACKUP = 51;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Diagnostics"),
				(id, inv, v) -> new DiagnosticsMenu(id, inv, v));
	}

	static void reopen(ServerPlayer viewer) {
		Guis.silent(viewer, Theme.title("Diagnostics"),
				(id, inv, v) -> new DiagnosticsMenu(id, inv, v));
	}

	private DiagnosticsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<StartupCheck.Finding> entries() {
		// Broken first. A screen that lists thirty working things and buries the one that is
		// not has answered the wrong question.
		return StartupCheck.report().stream()
				.sorted((a, b) -> Integer.compare(rank(a), rank(b)))
				.toList();
	}

	private static int rank(StartupCheck.Finding finding) {
		return switch (finding.health()) {
			case MISSING, NOT_APPLIED -> 0;
			case UNVERIFIED -> 1;
			case WORKING -> 2;
		};
	}

	@Override
	protected ItemStack header() {
		List<StartupCheck.Finding> all = StartupCheck.report();
		long broken = all.stream().filter(StartupCheck.Finding::isBroken).count();

		Icon icon = Icon.of(broken > 0 ? Items.REDSTONE_TORCH : Items.BEACON)
				.name("Diagnostics", broken > 0 ? Theme.BAD : Theme.GOOD)
				.field("Hooks", String.valueOf(all.size()))
				.field("Working", String.valueOf(all.size() - broken),
						broken > 0 ? Theme.TEXT : Theme.GOOD);

		if (broken > 0) {
			icon.field("Broken", String.valueOf(broken), Theme.BAD);
		}

		icon.gap();
		if (all.isEmpty()) {
			icon.warn("The startup check has not run yet.");
		} else if (broken > 0) {
			icon.lore("Broken features are listed first. The server", Theme.MUTED)
					.lore("runs normally without them.", Theme.MUTED);
		} else {
			icon.lore("Every hook StaffCore needs is present and", Theme.MUTED)
					.lore("verified applied.", Theme.MUTED);
		}
		return icon.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nothing checked yet", Theme.MUTED)
				.lore("The startup check runs once the world is up.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(StartupCheck.Finding finding) {
		Icon icon = Icon.of(iconFor(finding))
				.name(finding.feature(), colourFor(finding))
				.field("State", label(finding.health()), colourFor(finding))
				.field("Hooks", finding.className() + "#" + finding.method());

		icon.gap();
		// Wrapped by hand: a Mixin failure message is one very long line, and an icon that
		// runs off the side of the screen is not a diagnostic anybody can read.
		for (String line : wrap(finding.detail(), 46)) {
			icon.lore(line, Theme.MUTED);
		}

		if (finding.isBroken()) {
			icon.gap().warn("This feature is not working on this version.");
		}
		return icon.build();
	}

	private static List<String> wrap(String text, int width) {
		List<String> lines = new java.util.ArrayList<>();
		if (text == null || text.isBlank()) return lines;

		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+")) {
			if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
			if (lines.size() >= 5) break;   // enough to diagnose; the log has the rest
		}
		if (!line.isEmpty() && lines.size() < 6) lines.add(line.toString());
		return lines;
	}

	private static net.minecraft.world.item.Item iconFor(StartupCheck.Finding finding) {
		return switch (finding.health()) {
			case WORKING -> Mc.dye(net.minecraft.world.item.DyeColor.LIME);
			case UNVERIFIED -> Mc.dye(net.minecraft.world.item.DyeColor.YELLOW);
			case MISSING, NOT_APPLIED -> Mc.dye(net.minecraft.world.item.DyeColor.RED);
		};
	}

	private static int colourFor(StartupCheck.Finding finding) {
		return switch (finding.health()) {
			case WORKING -> Theme.GOOD;
			case UNVERIFIED -> Theme.WARN;
			case MISSING, NOT_APPLIED -> Theme.BAD;
		};
	}

	private static String label(StartupCheck.Health health) {
		return switch (health) {
			case WORKING -> "Working";
			case UNVERIFIED -> "Unverified";
			case MISSING -> "Target missing";
			case NOT_APPLIED -> "Mixin did not apply";
		};
	}

	@Override
	protected void onPick(StartupCheck.Finding finding, Click click) {
		// Nothing to do to a hook. The row is the answer.
		Sfx.click(viewer);
	}

	/** Storage health sits here too, because "is StaffCore working" includes its database. */
	@Override
	protected void decorateFooter() {
		boolean ready = StaffCore.storage().isReady();
		int backups = StaffCore.storage().backups().size();

		set(SLOT_STORAGE, Icon.of(ready ? Items.CHEST : Items.BARRIER)
				.name("Storage", ready ? Theme.GOOD : Theme.BAD)
				.field("State", ready ? "ready" : "unavailable", ready ? Theme.GOOD : Theme.BAD)
				.field("Backups", String.valueOf(backups))
				.gap()
				.lore(ready
						? "Punishments, logs and snapshots are being kept."
						: "Every persistent feature is disabled.", Theme.MUTED)
				.build());

		if (!Permissions.check(viewer, Nodes.RELOAD)) return;

		button(SLOT_BACKUP, Icon.of(Items.WRITABLE_BOOK)
				.name("Back up the database", Theme.TEXT)
				.gap()
				.lore("One is written on every server start. This is", Theme.MUTED)
				.lore("for just before you try something.", Theme.MUTED)
				.action("Click", "write one now")
				.build(), click -> {
			var out = StaffCore.storage().backup("requested by " + Mc.name(viewer));
			if (out == null) {
				viewer.sendSystemMessage(Theme.bad("Backup failed. The server log says why."));
				Sfx.error(viewer);
			} else {
				viewer.sendSystemMessage(Theme.good("Backup written: " + out.getFileName()));
				MinecraftServer server = Mc.server(viewer);
				if (server != null) {
					Mods.alerts().onStaffAction(server, Mc.name(viewer) + " took a database backup");
				}
				Sfx.success(viewer);
			}
			reopen(viewer);
		});
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffPanelMenu.open(viewer);
	}
}
