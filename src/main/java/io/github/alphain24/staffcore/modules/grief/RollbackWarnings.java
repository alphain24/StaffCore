package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * The things worth saying out loud before somebody confirms a rollback.
 * <p>
 * A rollback is the one command here that rewrites the world, and the two ways it goes wrong
 * are both invisible from the command line: it is far bigger than the person running it
 * pictured, or it lands somewhere it should not. Neither is caught by the confirm prompt on
 * its own, because a prompt that says "are you sure" to every rollback teaches everybody to
 * press yes to every rollback.
 * <p>
 * So these fire only on the cases that are actually unusual. A warning that appears every time
 * is a warning nobody reads, and the whole value here is that seeing one means something.
 *
 * <h2>Claimed regions</h2>
 * The brief asks for a warning on claimed regions as well as spawn. StaffCore has no claims of
 * its own and does not depend on any claims mod, so the only protected region the server itself
 * knows about is vanilla spawn protection — which is what is checked. A GriefPrevention or
 * FTB Chunks claim is invisible from here, and a check that silently covered none of them while
 * appearing to cover claims would be worse than not offering it. Named in Known limits.
 */
public final class RollbackWarnings {
	private RollbackWarnings() {}

	/** One thing worth reading before confirming. */
	public record Warning(String text, boolean severe) {}

	/**
	 * Everything unusual about this rollback, in the order it matters.
	 *
	 * @param changes how many block changes the preview found, or 0 if not yet known
	 */
	public static List<Warning> forArea(ServerLevel level, BlockPos centre, int radius,
			int changes) {

		List<Warning> out = new ArrayList<>();
		StaffConfig cfg = StaffConfig.get();

		if (cfg.rollbackWarnBlocks > 0 && changes >= cfg.rollbackWarnBlocks) {
			out.add(new Warning(changes + " block changes — this is a large rollback. Above "
					+ cfg.rollbackWarnBlocks + " it is worth checking the radius and the window "
					+ "are the ones you meant, because a rollback of the wrong area is undone "
					+ "by another rollback of the wrong area.", changes >= cfg.rollbackWarnBlocks * 4));
		}

		BlockPos spawn = spawnOf(level);
		if (spawn != null) {
			int protection = protectionRadius(level.getServer());
			double reach = (double) radius + protection;

			if (spawn.distSqr(centre) <= reach * reach) {
				out.add(new Warning("This overlaps spawn" + (protection > 0
						? " and its " + protection + "-block protected area" : "")
						+ ". Spawn is built by staff over months and logged the same way griefing "
						+ "is, so a wide rollback here undoes building work rather than damage.",
						true));
			}
		}
		return out;
	}

	/** The world spawn, or null in a dimension that has none. */
	private static BlockPos spawnOf(ServerLevel level) {
		try {
			var respawn = level.getRespawnData();
			if (respawn == null) return null;
			return respawn.dimension().equals(level.dimension()) ? respawn.pos() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Vanilla's {@code spawn-protection}, or 0 where there is none.
	 * <p>
	 * Only a dedicated server has the setting; an integrated one has no such notion, and
	 * treating its absence as zero is correct rather than a fallback.
	 */
	private static int protectionRadius(MinecraftServer server) {
		return server instanceof DedicatedServer dedicated ? dedicated.spawnProtectionRadius() : 0;
	}
}
