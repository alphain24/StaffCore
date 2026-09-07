package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.identity.IdentityModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's session and death history, newest first.
 * <p>
 * Two views behind one screen because they answer the same question from opposite ends:
 * "were they even online when this happened?" and "where did they lose their things?".
 * Deaths carry coordinates, so an argument about lost items can be checked rather than
 * argued about.
 */
public class LogsMenu extends PagedGui<LogsMenu.Row> {

	private static final int SLOT_MODE = 47;
	private static final int LIMIT = 200;

	/** A session or a death, flattened so one paged list can show either. */
	public record Row(boolean death, String primary, String detail, String where, long at) {}

	private enum Mode { SESSIONS, DEATHS }

	private final NameAndId target;
	private Mode mode = Mode.SESSIONS;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Logs", target.name()),
				(id, inv, v) -> new LogsMenu(id, inv, v, target));
	}

	private LogsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<Row> entries() {
		List<Row> out = new ArrayList<>();
		if (mode == Mode.SESSIONS) {
			for (IdentityModule.Session s : Mods.identity().sessions(target.id(), LIMIT)) {
				out.add(new Row(false, s.action(), s.ip() == null ? "unknown address" : s.ip(), null, s.at()));
			}
		} else {
			for (IdentityModule.Death d : Mods.identity().deaths(target.id(), LIMIT)) {
				out.add(new Row(true, d.cause(),
						d.killer() == null ? "no killer" : "killed by " + d.killer(),
						"%s %d, %d, %d".formatted(shortWorld(d.world()), d.x(), d.y(), d.z()),
						d.at()));
			}
		}
		return out;
	}

	private static String shortWorld(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	@Override
	protected ItemStack icon(Row row) {
		if (row.death()) {
			return Icon.of(Items.SKELETON_SKULL)
					.name("Died", Theme.BAD)
					.paragraph(row.primary(), Theme.TEXT)
					.gap()
					.field("Detail", row.detail())
					.field("Where", row.where())
					.field("When", TimeFormat.ago(row.at()))
					.field("Exact", TimeFormat.stamp(row.at()))
					.gap()
					.action("Click", "teleport to where they died")
					.build();
		}

		boolean joined = "JOIN".equals(row.primary());
		return Icon.of(Mc.dye(joined ? net.minecraft.world.item.DyeColor.LIME : net.minecraft.world.item.DyeColor.GRAY))
				.name(joined ? "Joined" : "Left", joined ? Theme.GOOD : Theme.MUTED)
				.field("Address", row.detail())
				.field("When", TimeFormat.ago(row.at()))
				.field("Exact", TimeFormat.stamp(row.at()))
				.build();
	}

	@Override
	protected void onPick(Row row, Click click) {
		if (!row.death() || row.where() == null) {
			Sfx.page(viewer);
			return;
		}
		if (!Permissions.check(viewer, Nodes.TP)) {
			Sfx.deny(viewer);
			return;
		}

		// "<world> x, y, z" — parse back out the three numbers.
		String[] parts = row.where().replace(",", "").split("\\s+");
		if (parts.length < 4) {
			Sfx.deny(viewer);
			return;
		}
		try {
			double x = Double.parseDouble(parts[1]) + 0.5;
			double y = Double.parseDouble(parts[2]) + 1.0;
			double z = Double.parseDouble(parts[3]) + 0.5;
			Mods.teleport().toPosition(viewer, x, y, z);
			viewer.sendSystemMessage(Theme.info("Teleported to where " + target.name() + " died."));
			viewer.closeContainer();
		} catch (NumberFormatException e) {
			Sfx.deny(viewer);
		}
	}

	@Override
	protected ItemStack header() {
		return Icon.head(target)
				.name(target.name() + "'s logs", Theme.ACCENT)
				.field("Showing", mode == Mode.SESSIONS ? "joins and leaves" : "deaths")
				.field("Entries", String.valueOf(entries().size()))
				.gap()
				.lore("Newest first, capped at " + LIMIT + ".")
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name(mode == Mode.SESSIONS ? "No sessions recorded" : "No deaths recorded", Theme.MUTED)
				.lore("Logging began when StaffCore was installed.")
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
		button(SLOT_MODE, Icon.of(mode == Mode.SESSIONS ? Items.SKELETON_SKULL : Items.CLOCK)
				.name(mode == Mode.SESSIONS ? "Show deaths" : "Show sessions", Theme.TEXT)
				.lore(mode == Mode.SESSIONS
						? "Switch to where and how they have died."
						: "Switch to when they joined and left.")
				.build(), click -> {
			mode = mode == Mode.SESSIONS ? Mode.DEATHS : Mode.SESSIONS;
			resetPage();
			Sfx.page(viewer);
			render();
		});
	}
}
