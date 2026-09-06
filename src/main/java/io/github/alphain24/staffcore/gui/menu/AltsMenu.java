package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.identity.IdentityModule;
import io.github.alphain24.staffcore.util.PlayerLookup;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Accounts that have connected from the same address as this one.
 * <p>
 * Read this as a lead, never as proof. Shared addresses are ordinary — siblings, flatmates,
 * a school, a phone hotspot, an entire campus behind one NAT — so the screen deliberately
 * has no punish button on an entry. Banned accounts are pulled to the top and marked,
 * because that is the case worth a human looking at; everything else is context.
 */
public class AltsMenu extends PagedGui<IdentityModule.Alt> {

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Linked", target.name()),
				(id, inv, v) -> new AltsMenu(id, inv, v, target));
	}

	private AltsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer, NameAndId target) {
		super(containerId, playerInventory, viewer);
		this.target = target;
		render();
	}

	@Override
	protected List<IdentityModule.Alt> entries() {
		return Mods.identity().altsOf(target.id());
	}

	@Override
	protected ItemStack icon(IdentityModule.Alt alt) {
		MinecraftServer server = Mc.server(viewer);
		boolean online = server != null && server.getPlayerList().getPlayer(alt.uuid()) != null;

		Icon icon = headFor(server, alt)
				.name(alt.name(), alt.banned() ? Theme.BAD : Theme.TEXT)
				.field("Confidence", alt.confidence() + "%", confidenceColor(alt.confidence()))
				.field("Link", alt.match() == IdentityModule.Match.EXACT_IP
						? "Same address" : "Same address range")
				.field(alt.match() == IdentityModule.Match.EXACT_IP ? "Address" : "Their address",
						alt.sharedIp())
				.field("Last seen", TimeFormat.ago(alt.lastSeen()))
				.field("UUID", alt.uuid().toString());

		// The reasoning, not just the number. A score staff cannot interrogate is one they
		// either over-trust or ignore, and both are worse than showing the working.
		if (!alt.reasons().isEmpty()) {
			icon.gap();
			for (String reason : alt.reasons()) {
				icon.lore("• " + reason, Theme.MUTED);
			}
		}

		icon.gap().state(online, "Online now", "Offline");

		if (alt.banned()) {
			icon.warn("This account is banned").glow();
		}
		icon.gap().action("Click", "open their file");
		return icon.build();
	}

	private static int confidenceColor(int confidence) {
		if (confidence >= 70) return Theme.BAD;
		if (confidence >= 45) return Theme.WARN;
		return Theme.MUTED;
	}

	private Icon headFor(MinecraftServer server, IdentityModule.Alt alt) {
		if (server != null) {
			var profile = PlayerLookup.profile(server, alt.name());
			if (profile.isPresent()) return Icon.head(profile.get());
		}
		return Icon.of(Items.PLAYER_HEAD);
	}

	@Override
	protected void onPick(IdentityModule.Alt alt, Click click) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		var profile = PlayerLookup.profile(server, alt.name());
		if (profile.isEmpty()) {
			viewer.sendSystemMessage(Theme.warn("Could not resolve " + alt.name() + "."));
			Sfx.deny(viewer);
			return;
		}
		PlayerActionsMenu.open(viewer, profile.get());
	}

	@Override
	protected ItemStack header() {
		List<IdentityModule.Alt> all = entries();
		long banned = all.stream().filter(IdentityModule.Alt::banned).count();

		Icon icon = Icon.head(target)
				.name(target.name() + "'s linked accounts", Theme.ACCENT)
				.field("Linked", String.valueOf(all.size()))
				.field("Of those, banned", String.valueOf(banned), banned > 0 ? Theme.BAD : Theme.MUTED);

		String ip = Mods.identity().lastAddress(target.id());
		if (ip != null) icon.field("Last address", ip);

		return icon.gap()
				.paragraph("A shared address is a lead, not proof. Households, schools and "
						+ "mobile networks all put different people behind one.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Mc.pane(net.minecraft.world.item.DyeColor.LIME))
				.name("No linked accounts", Theme.GOOD)
				.lore("Nobody else has connected from their address.")
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
}
