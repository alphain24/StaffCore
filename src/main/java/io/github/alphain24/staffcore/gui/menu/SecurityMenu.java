package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.SecurityModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * What the automated checks found on one player.
 * <p>
 * Findings are evidence, not verdicts — the screen deliberately has no punish shortcut on
 * a flag, because "the scanner said so" is not a reason. The route to acting on this is
 * back through their file, where their record is visible alongside.
 */
public class SecurityMenu extends PagedGui<SecurityModule.Flag> {

	private static final int SLOT_RESCAN = 47;
	private static final int SLOT_SNAPSHOT = 51;

	private final ServerPlayer target;
	private List<SecurityModule.Flag> cached;

	public static void open(ServerPlayer viewer, ServerPlayer target) {
		if (target == null) return;
		Guis.navigate(viewer, Theme.title("Security", Mc.name(target)),
				(id, inv, v) -> new SecurityMenu(id, inv, v, target));
	}

	private SecurityMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, ServerPlayer target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<SecurityModule.Flag> entries() {
		// Cached per render pass: the scan walks the whole inventory and hits the database,
		// and the header, the icons and the page count all ask for the list.
		if (cached == null) {
			cached = Mods.security().check(target);
		}
		return cached;
	}

	@Override
	protected ItemStack icon(SecurityModule.Flag flag) {
		net.minecraft.world.item.Item item = switch (flag.severity()) {
			case INFO -> Mc.pane(DyeColor.LIME);
			case SUSPICIOUS -> Mc.pane(DyeColor.YELLOW);
			case IMPOSSIBLE -> Mc.pane(DyeColor.RED);
		};

		Icon icon = Icon.of(item)
				.name(flag.kind(), flag.color())
				.paragraph(flag.detail(), Theme.TEXT)
				.gap()
				.field("Severity", flag.severity().name(), flag.color());

		icon.gap().lore(switch (flag.severity()) {
			case INFO -> "Nothing to do.";
			case SUSPICIOUS -> "Worth watching. Not proof on its own.";
			case IMPOSSIBLE -> "This cannot happen in survival.";
		}, flag.color());

		if (flag.severity() == SecurityModule.Severity.IMPOSSIBLE) icon.glow();
		return icon.build();
	}

	@Override
	protected void onPick(SecurityModule.Flag entry, Click click) {
		Sfx.page(viewer);
	}

	@Override
	protected ItemStack header() {
		List<SecurityModule.Flag> flags = entries();
		long impossible = flags.stream()
				.filter(f -> f.severity() == SecurityModule.Severity.IMPOSSIBLE).count();
		long suspicious = flags.stream()
				.filter(f -> f.severity() == SecurityModule.Severity.SUSPICIOUS).count();

		SecurityModule.MiningSample mining = Mods.security().mining(Mc.name(target), 6L * 3_600_000L);

		Icon icon = Icon.head(Mc.profile(target))
				.name(Mc.name(target), Theme.ACCENT)
				.field("Impossible", String.valueOf(impossible), impossible > 0 ? Theme.BAD : Theme.GOOD)
				.field("Suspicious", String.valueOf(suspicious), suspicious > 0 ? Theme.WARN : Theme.GOOD);

		if (mining != null && mining.total() > 0) {
			icon.gap()
					.field("Blocks mined (6h)", String.valueOf(mining.total()))
					.field("Ore fraction", "%.2f%%".formatted(mining.ratio() * 100));
		}

		return icon.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(DyeColor.LIME))
				.name("Clean", Theme.GOOD)
				.lore("Nothing the automated checks can fault.")
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> PlayerActionsMenu.reopen(viewer, Mc.profile(target));
	}

	@Override
	protected String backLabel() {
		return Mc.name(target) + "'s file";
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_RESCAN, Icon.of(Items.SPYGLASS)
				.name("Re-scan", Theme.TEXT)
				.lore("Run the checks again from scratch.")
				.build(), click -> {
			cached = null;
			resetPage();
			Sfx.success(viewer);
			render();
		});

		if (!Permissions.check(viewer, Nodes.INVSEE)) return;

		button(SLOT_SNAPSHOT, Icon.of(Items.ENDER_CHEST)
				.name("Snapshot their inventory", Theme.TEXT)
				.lore("Keep a copy of the evidence before anything changes.")
				.build(), click -> {
			var snap = Mods.inventory().capture(target, "Security check", Mc.name(viewer));
			MinecraftServer server = Mc.server(viewer);
			if (server != null) {
				Mods.alerts().onSecurityFlag(server, Mc.name(target),
						"snapshot taken during a security check by " + Mc.name(viewer));
			}
			viewer.sendSystemMessage(Theme.good("Snapshot taken — " + snap.itemCount() + " stack(s)."));
			Sfx.success(viewer);
		});
	}
}
