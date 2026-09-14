package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.util.PlayerLookup;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

/**
 * The rollback preview and confirmation, for any screen that offers one.
 * <p>
 * It used to live inside the grief log. A case about griefing wants the same button, and a
 * second copy would have been a second place for the warnings to drift — the confirm screen is
 * the one moment staff read before overwriting somebody's build, and every path to it has to
 * say the same things.
 */
public final class RollbackPreview {
	private RollbackPreview() {}

	/**
	 * What to roll back.
	 *
	 * @param who      one player's changes, or null for everybody's
	 * @param windowMs how far back from now
	 * @param extraWarnings said on the confirm screen after the standard ones
	 * @param breaksOnly    put back what was broken and taken; leave placements and puts
	 */
	public record Scope(ServerLevel level, String who, BlockPos centre, int radius, long windowMs,
			List<String> extraWarnings, boolean breaksOnly) {

		public Scope(ServerLevel level, String who, BlockPos centre, int radius, long windowMs,
				List<String> extraWarnings) {
			this(level, who, centre, radius, windowMs, extraWarnings, false);
		}
	}

	/**
	 * Previews, and on confirm runs.
	 *
	 * @param afterApply given the result once it ran; reopen whatever screen fits
	 * @param onCancel   the way back when staff decide against it
	 */
	public static void open(ServerPlayer viewer, Scope scope,
			Consumer<GriefModule.RollbackResult> afterApply, Runnable onCancel) {

		ServerLevel level = scope.level();
		String who = scope.who();
		// The identity is passed to the preview as well as to the run, so the region lock is
		// taken while this staff member is deciding rather than only while blocks are moving.
		// The window somebody else can change the answer in is the human one.
		GriefModule.RollbackResult preview = Mods.grief().rollback(level, who, scope.centre(),
				scope.radius(), scope.windowMs(), true, Actor.of(viewer), scope.breaksOnly());

		if (preview.reverted() == 0) {
			viewer.sendSystemMessage(Theme.warn(who == null
					? "Nothing to roll back here."
					: "Nothing of " + who + "'s to roll back here."));
			Sfx.deny(viewer);
			return;
		}

		BlockPos centre = scope.centre();
		Icon summary = (who == null ? Icon.of(Items.TNT) : profileIcon(viewer, who))
				.name(who == null ? "Roll back this area" : "Roll back " + who, Theme.BAD)
				.field("Scope", who == null ? "every player" : who)
				.field("Undoing", scope.breaksOnly() ? "only what was broken" : "breaks and placements")
				.field("Changes to undo", String.valueOf(preview.reverted()))
				.field("Radius", scope.radius() + " blocks")
				.field("Window", TimeFormat.duration(scope.windowMs()))
				.field("World", Mc.dimensionName(level))
				.field("Centre", "%d, %d, %d".formatted(centre.getX(), centre.getY(), centre.getZ()));

		// What, not just how many. "412 changes" says how big the operation is and nothing
		// about whether it is the right one; the item list is what staff actually check
		// against before overwriting somebody's build.
		if (!preview.restoring().isEmpty()) {
			summary.gap().lore("Putting back:", Theme.TEXT);
			preview.restoring().stream().limit(6).forEach(item ->
					summary.lore("  " + item.count() + "× " + shortId(item.itemId()), Theme.MUTED));
			if (preview.restoring().size() > 6) {
				summary.lore("  … and " + (preview.restoring().size() - 6) + " more kinds", Theme.MUTED);
			}
		}

		// The same warnings the chat preview prints, on the screen where this is decided.
		// A confirm button with nothing unusual said next to it is a confirm button people
		// press without reading, which is exactly what makes the unusual case dangerous.
		var warnings = io.github.alphain24.staffcore.modules.grief.RollbackWarnings.forArea(
				level, centre, scope.radius(), preview.reverted());
		if (!warnings.isEmpty() || !scope.extraWarnings().isEmpty()) summary.gap();
		for (var warning : warnings) summary.warn(warning.text());
		for (String warning : scope.extraWarnings()) summary.warn(warning);

		if (preview.skipped() > 0) {
			summary.gap().warn(preview.skipped() + " entry/entries reference blocks that no longer exist.");
		}
		summary.gap().warn("Anything built on top of these blocks is overwritten.");
		if (preview.itemsReturned() > 0) {
			summary.lore(preview.itemsReturned() + " container change(s) will also be undone.", Theme.MUTED);
		}
		if (preview.itemsDeferred() > 0) {
			summary.warn(preview.itemsDeferred() + " stack(s) have nowhere to go — those chests are full.");
		}
		if (StaffConfig.get().rollbackReclaimsDrops) {
			summary.lore("Dropped items from restored blocks are reclaimed.", Theme.MUTED);
			if (StaffConfig.get().rollbackChasesBankedLoot) {
				summary.lore("Loot stashed in chests elsewhere is followed.", Theme.MUTED);
			}
		}

		// Drawn in the world, to this staff member only, while the confirm screen is up.
		// The list above says what; this says where — and "where" is the question that
		// actually decides whether the radius is right. Only in the world they are standing
		// in: a ghost wall drawn over the wrong dimension's terrain is worse than none.
		if (viewer.level() == level) {
			int drawn = Mods.grief().preview().show(viewer, level, preview.proposed());
			if (drawn > 0) {
				summary.gap().lore("Shown in the world in front of you — nobody else", Theme.ACCENT)
						.lore("can see it, and nothing has been written yet.", Theme.ACCENT);
				viewer.sendSystemMessage(Theme.info("Previewing " + drawn
						+ " block(s) around you. Close the menu to clear it."));
			}
		} else {
			summary.gap().lore("You are in another world, so the preview is not", Theme.MUTED)
					.lore("drawn. Go to the scene first to see it.", Theme.MUTED);
		}

		ConfirmMenu.open(viewer, "Rollback", summary.build(),
				() -> {
					Mods.grief().preview().clear(viewer);
					afterApply.accept(apply(viewer, scope));
				},
				() -> {
					Mods.grief().preview().clear(viewer);
					onCancel.run();
				});
	}

	/** Runs it and says what happened. */
	private static GriefModule.RollbackResult apply(ServerPlayer viewer, Scope scope) {
		String who = scope.who();
		BlockPos centre = scope.centre();
		GriefModule.RollbackResult result = Mods.grief().rollback(scope.level(), who, centre,
				scope.radius(), scope.windowMs(), false, Actor.of(viewer), scope.breaksOnly());

		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			Mods.alerts().onStaffAction(server, "%s rolled back %d change(s) by %s at %d, %d, %d"
					.formatted(Mc.name(viewer), result.reverted(), who == null ? "everyone" : who,
							centre.getX(), centre.getY(), centre.getZ()));
		}

		viewer.sendSystemMessage(Theme.good("Reverted " + result.reverted() + " change(s)."));
		if (result.dropsRemoved() > 0) {
			viewer.sendSystemMessage(Theme.info(
					"Reclaimed " + result.dropsRemoved() + " dropped item(s) so nothing was duplicated."));
		}
		if (result.bankedRemoved() > 0) {
			viewer.sendSystemMessage(Theme.info(
					"Took back " + result.bankedRemoved() + " item(s) stashed in chests elsewhere."));
		}
		if (result.debitsQueued() > 0) {
			viewer.sendSystemMessage(Theme.info(result.debitsQueued()
					+ " item(s) are owed by an offline player — collected when they next log in."));
		}
		if (result.itemsReturned() > 0) {
			viewer.sendSystemMessage(Theme.info(
					"Put " + result.itemsReturned() + " stack(s) back into containers."));
		}
		// The rows for these were left un-retired on purpose, so saying so turns a silent
		// shortfall into something staff can act on.
		if (result.itemsDeferred() > 0) {
			viewer.sendSystemMessage(Theme.warn(result.itemsDeferred()
					+ " stack(s) would not fit — clear space and run the rollback again."));
		}
		Sfx.bigSuccess(viewer);
		return result;
	}

	private static Icon profileIcon(ServerPlayer viewer, String name) {
		MinecraftServer server = Mc.server(viewer);
		if (server != null) {
			var profile = PlayerLookup.profile(server, name);
			if (profile.isPresent()) return Icon.head(profile.get());
		}
		return Icon.of(Items.PLAYER_HEAD);
	}

	static String shortId(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}
}
