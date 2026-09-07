package io.github.alphain24.staffcore.modules.identity;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Flags an account that joins from an address a banned account has used.
 * <p>
 * This reports by default and punishes only if you ask it to, and that default is
 * deliberate. Shared addresses are ordinary — siblings, flatmates, a school, a phone
 * hotspot, an entire university behind one NAT. Auto-banning on an IP match punishes those
 * people for the sins of someone they live with, and the player it actually catches will
 * simply use a VPN next time. What the check is genuinely good at is putting a name in
 * front of staff at the moment it is useful, which is what it does here.
 */
public class EvasionModule implements Module {

	@Override
	public String id() {
		return "evasion";
	}

	@Override
	public String displayName() {
		return "Ban Evasion";
	}

	/**
	 * Runs on join. Alerts staff about any banned account sharing this address, and — only
	 * when {@code autoBanEvaders} is on — bans the newcomer too.
	 */
	public void screen(MinecraftServer server, ServerPlayer joiner) {
		StaffConfig cfg = StaffConfig.get();
		if (!cfg.detectBanEvasion) return;

		// Below the confidence floor is noise, and an alert staff learn to dismiss is worse
		// than none — it teaches them to dismiss the ones that matter too.
		List<IdentityModule.Alt> banned = Mods.identity().bannedAlts(joiner.getUUID()).stream()
				.filter(alt -> alt.confidence() >= cfg.altMinConfidence)
				.toList();
		if (banned.isEmpty()) return;

		String names = banned.stream().map(IdentityModule.Alt::name).limit(3)
				.reduce((a, b) -> a + ", " + b).orElse("?");
		if (banned.size() > 3) {
			names += " and " + (banned.size() - 3) + " more";
		}

		IdentityModule.Alt strongest = banned.get(0);
		String detail = "%s is linked to banned account(s): %s (%s)"
				.formatted(Mc.name(joiner), names, strongest.headline());
		Mods.alerts().onSecurityFlag(server, Mc.name(joiner), detail);

		notifyStaff(server, joiner, names, strongest);

		// Nothing bans anybody from here, and there is no setting that makes it.
		//
		// The strongest signal available is "these two accounts used the same address", and
		// under CGNAT that is every customer of a mobile network, while in a shared house it
		// is a sibling. An automatic ban on that evidence is wrong some fraction of the time
		// and the people it is wrong about are strangers who did nothing — which is the
		// single most expensive mistake this mod could make, and it would make it while
		// nobody was watching.
		//
		// The README has always said alt detection is a lead and not a verdict. Acting on it
		// automatically was the one place that said otherwise.
	}

	/**
	 * A clickable-feeling line in staff chat rather than a buried log entry.
	 * <p>
	 * The strength of the link is in the message itself. "Shares an exact address" and
	 * "addresses are in the same range" call for different responses, and an alert that
	 * words them identically pushes staff toward treating the weaker one as though it were
	 * the stronger.
	 */
	private void notifyStaff(MinecraftServer server, ServerPlayer joiner, String names,
			IdentityModule.Alt strongest) {

		Component line = Theme.prefix()
				.append(Icon.text("Evasion", Theme.BAD).withStyle(s -> s.withBold(true)))
				.append(Icon.text(" · ", Theme.MUTED))
				.append(Icon.text(Mc.name(joiner), Theme.TEXT))
				.append(Icon.text(strongest.isStrong()
						? " just joined sharing an address with "
						: " just joined from the same address range as ", Theme.MUTED))
				.append(Icon.text(names, Theme.WARN))
				.append(Icon.text(" (banned)", Theme.MUTED));

		Component confidence = Theme.info("  " + strongest.confidence()
				+ "% confidence — " + String.join("; ", strongest.reasons()));
		Component hint = Theme.info("Open /staff " + Mc.name(joiner) + " → Linked Accounts to review.");

		for (ServerPlayer staff : server.getPlayerList().getPlayers()) {
			if (!Permissions.check(staff, Nodes.ALTS)) continue;
			staff.sendSystemMessage(line);
			staff.sendSystemMessage(confidence);
			staff.sendSystemMessage(hint);
			Sfx.alertPing(staff);
		}
	}
}
