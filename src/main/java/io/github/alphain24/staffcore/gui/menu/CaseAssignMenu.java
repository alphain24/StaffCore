package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Hands a case to somebody: every staff member online, with how many open cases each already
 * has, fewest first.
 * <p>
 * Online only, for the same reason the automatic assignment is: a case given to somebody who is
 * not here is a case nobody is working while the list says somebody is. Anybody else can still
 * be named with {@code /staff case <id> assign <name>}.
 */
public final class CaseAssignMenu extends PagedGui<String> {

	private static final int SLOT_NOBODY = 46;

	private final String caseId;
	private Map<String, Integer> loads = Map.of();

	public static void open(ServerPlayer viewer, String caseId) {
		Guis.navigate(viewer, Theme.title("Assign", caseId),
				(id, inv, v) -> new CaseAssignMenu(id, inv, v, caseId));
	}

	private CaseAssignMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			String caseId) {
		super(containerId, playerInventory, viewer);
		this.caseId = caseId;
		render();
	}

	@Override
	protected List<String> entries() {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return List.of();
		loads = Mods.cases().store().liveLoads();

		List<String> staff = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Permissions.check(player, Nodes.STAFF_GUI)) staff.add(Mc.name(player));
		}
		staff.sort(Comparator.<String>comparingInt(this::loadOf)
				.thenComparing(String.CASE_INSENSITIVE_ORDER));
		return staff;
	}

	private int loadOf(String name) {
		int total = 0;
		for (var entry : loads.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) total += entry.getValue();
		}
		return total;
	}

	@Override
	protected ItemStack header() {
		Case found = Mods.cases().store().byId(caseId).orElse(null);
		return Icon.of(Items.NAME_TAG)
				.name("Assign case " + caseId, Theme.ACCENT)
				.field("Now", found == null || found.assignedTo() == null ? "nobody" : found.assignedTo())
				.gap()
				.lore("Staff online, fewest open cases first.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(String name) {
		MinecraftServer server = Mc.server(viewer);
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayerByName(name);
		Icon icon = online != null ? Icon.head(online.nameAndId()) : Icon.of(Items.PLAYER_HEAD);
		return icon.name(name, Theme.ACCENT)
				.field("Open cases", String.valueOf(loadOf(name)))
				.gap()
				.action("Click", "assign the case to " + (name.equals(Mc.name(viewer)) ? "yourself" : name))
				.build();
	}

	@Override
	protected void onPick(String name, Click click) {
		String me = Mc.name(viewer);
		if (!Mods.cases().store().assign(caseId, name, me)) {
			viewer.sendSystemMessage(Theme.bad("The case could not be assigned. The server log says why."));
			Sfx.deny(viewer);
			return;
		}
		MinecraftServer server = Mc.server(viewer);
		ServerPlayer target = server == null ? null : server.getPlayerList().getPlayerByName(name);
		if (target != null && target != viewer) {
			target.sendSystemMessage(Theme.info(me + " assigned you case ").append(Link.caseId(caseId)));
		}
		viewer.sendSystemMessage(Theme.good("Case " + caseId + " is now " + name + "'s."));
		Sfx.success(viewer);
		CaseMenu.returnTo(viewer, caseId);
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_NOBODY, Icon.of(Items.BARRIER)
				.name("Unassign", Theme.ACCENT)
				.lore("Nobody has it. With automatic assignment on, it goes", Theme.MUTED)
				.lore("to whoever online has the fewest on the next pass.", Theme.MUTED)
				.build(), click -> {
			Mods.cases().store().assign(caseId, null, Mc.name(viewer));
			Sfx.click(viewer);
			CaseMenu.returnTo(viewer, caseId);
		});
	}

	@Override
	protected Runnable backTarget() {
		return () -> CaseMenu.returnTo(viewer, caseId);
	}

	@Override
	protected String backLabel() {
		return "Case " + caseId;
	}
}
