package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.PagedGui;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.PunishmentModule;
import io.github.alphain24.staffcore.util.PlayerLookup;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Everybody who has punished anybody, most recently active first — the way into what each of
 * them issued.
 * <p>
 * Taken from the punishments themselves rather than from the staff list, so a former staff
 * member whose bans are still in force is on it, and so is the console.
 */
public final class StaffPunishmentsMenu extends PagedGui<PunishmentModule.Issuer> {

	private static final int SLOT_EVERYONE = 47;

	public static void open(ServerPlayer viewer) {
		Guis.navigate(viewer, Theme.title("Punishments by staff"), StaffPunishmentsMenu::new);
	}

	private StaffPunishmentsMenu(int containerId, Inventory playerInventory, ServerPlayer viewer) {
		super(containerId, playerInventory, viewer);
		render();
	}

	@Override
	protected List<PunishmentModule.Issuer> entries() {
		return Mods.punish().issuers();
	}

	@Override
	protected ItemStack header() {
		return Icon.of(Items.NETHERITE_AXE)
				.name("Punishments by staff", Theme.ACCENT)
				.lore("Who has issued what, against whom, with the case")
				.lore("and its evidence one click away.")
				.gap()
				.lore("Most recently active first.", Theme.MUTED)
				.build();
	}

	@Override
	protected ItemStack icon(PunishmentModule.Issuer issuer) {
		MinecraftServer server = Mc.server(viewer);
		Icon icon = server == null ? Icon.of(Items.PLAYER_HEAD)
				: PlayerLookup.profile(server, issuer.staffName()).map(Icon::head)
						.orElse(Icon.of(Items.PLAYER_HEAD));
		return icon.name(issuer.staffName(), Theme.ACCENT)
				.field("Issued", String.valueOf(issuer.issued()))
				.field("Bans and mutes in force", String.valueOf(issuer.inForce()),
						issuer.inForce() > 0 ? Theme.WARN : Theme.MUTED)
				.field("Last", TimeFormat.ago(issuer.lastAt()))
				.gap()
				.action("Click", "see everything they issued")
				.action("Right-click", "only the bans still in force")
				.build();
	}

	@Override
	protected void onPick(PunishmentModule.Issuer issuer, Click click) {
		Sfx.page(viewer);
		if (click.isRight()) BannedPlayersMenu.open(viewer, issuer.staffName());
		else IssuedPunishmentsMenu.open(viewer, issuer.staffName());
	}

	@Override
	protected void decorateFooter() {
		button(SLOT_EVERYONE, Icon.of(Items.BOOKSHELF)
				.name("Everybody's, in one list", Theme.ACCENT)
				.lore("Every punishment on the server, newest first.")
				.build(), click -> IssuedPunishmentsMenu.open(viewer, null));
	}

	@Override
	protected ItemStack emptyIcon() {
		return Icon.of(Items.STRUCTURE_VOID)
				.name("Nobody has punished anybody", Theme.MUTED)
				.build();
	}

	@Override
	protected Runnable backTarget() {
		return () -> StaffSections.punishments(viewer);
	}

	@Override
	protected String backLabel() {
		return "Punishments";
	}
}
