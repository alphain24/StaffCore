package io.github.alphain24.staffcore.modules.grief;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Works out which player an explosion belongs to when vanilla does not say.
 *
 * <h2>What vanilla already records, and where it stops</h2>
 * An explosion carries its source entity and a damage source. That names a player for TNT lit
 * with flint and steel — and every TNT that chain-reacts off it — and for anything fired by a
 * player. It names nobody for the three things griefers actually use:
 * <ul>
 *   <li><b>TNT lit by redstone or fire.</b> The primed TNT has no owner at all. The player who
 *   placed the block is the one who set it up, so placements are remembered here and the
 *   primed TNT inherits the placer.</li>
 *   <li><b>End crystals.</b> The crystal is the source and is not a living thing, so the
 *   "indirect source" is empty. The player who hit it is in the damage source instead, which
 *   the caller reads before asking this class anything.</li>
 *   <li><b>Beds and respawn anchors.</b> The explosion has no entity at all. It happens inside
 *   the click that caused it, so a click on a block in the same tick, within a block and a
 *   half of the blast, names the player.</li>
 * </ul>
 *
 * <h2>What it will get wrong</h2>
 * The placer of redstone-lit TNT is blamed even if somebody else flipped the lever. The alert
 * says "TNT they placed" rather than "TNT" for exactly that reason, so staff can tell which
 * kind of claim they are reading. TNT from a dispenser and TNT placed before a restart are not
 * attributed at all: a wrong name on a grief alert is worse than no name.
 * <p>
 * Everything here runs on the server thread, where every caller already is.
 */
public final class BlastAttribution {

	/** The player behind a blast, and the words for how, e.g. "end crystal". */
	public record Culprit(UUID id, String name, String how) {}

	/** One block click, kept for the rest of its tick. */
	record Use(String world, BlockPos pos, long tick, Culprit who) {}

	/** Where a TNT block is. Dimension included, because a position alone is not a place. */
	private record Where(String world, long pos) {}

	private record Placement(Culprit who, long at) {}

	/** Enough for a large build of TNT; bounded because nothing else ever clears it. */
	static final int MEMORY = 4096;

	/**
	 * How long a placed TNT block stays attributable. An hour covers a trap set up and sprung
	 * the same session; older than that and the placer is a guess.
	 */
	static final long PLACEMENT_MS = 3_600_000L;

	/**
	 * A bed explodes at its head, and the player may have clicked the foot one block away.
	 * An anchor explodes where it stands. Squared, compared against block centres.
	 */
	static final double USE_REACH_SQ = 1.5 * 1.5;

	private final LinkedHashMap<Where, Placement> placedTnt = bounded();
	private final LinkedHashMap<UUID, Culprit> primedTnt = bounded();
	private final Map<UUID, Use> lastUse = new HashMap<>();

	void onTntPlaced(String world, BlockPos pos, Culprit who, long now) {
		placedTnt.put(new Where(world, pos.asLong()), new Placement(who, now));
	}

	/**
	 * A TNT with no owner has just been primed at {@code pos}. If somebody placed a TNT block
	 * there recently, the primed TNT is theirs.
	 */
	void onTntPrimed(UUID tnt, String world, BlockPos pos, long now) {
		Placement placed = placedTnt.remove(new Where(world, pos.asLong()));
		if (placed == null || now - placed.at() > PLACEMENT_MS) return;

		Culprit who = placed.who();
		primedTnt.put(tnt, new Culprit(who.id(), who.name(), "TNT they placed"));
	}

	/** Who primed TNT belongs to. Removed when read: a TNT explodes once. */
	Culprit takePrimed(UUID tnt) {
		return primedTnt.remove(tnt);
	}

	/** For tests and diagnostics, which must not consume the attribution. */
	Culprit peekPrimed(UUID tnt) {
		return primedTnt.get(tnt);
	}

	void onUse(Use use) {
		lastUse.put(use.who().id(), use);
	}

	/**
	 * The player whose click, this tick, was close enough to have caused a blast centred here.
	 * {@code null} when there is no such click, or when two players' clicks both are: naming
	 * one of two equally likely people is a coin toss on somebody's record.
	 */
	Culprit clickedNear(String world, double x, double y, double z, long tick) {
		Culprit found = null;
		for (Iterator<Use> it = lastUse.values().iterator(); it.hasNext(); ) {
			Use use = it.next();
			if (use.tick() < tick) {
				// Stale: a click from an earlier tick can never explain a blast in this one.
				it.remove();
				continue;
			}
			if (use.tick() != tick || !use.world().equals(world)) continue;

			double dx = use.pos().getX() + 0.5 - x;
			double dy = use.pos().getY() + 0.5 - y;
			double dz = use.pos().getZ() + 0.5 - z;
			if (dx * dx + dy * dy + dz * dz > USE_REACH_SQ) continue;

			if (found != null) return null;
			found = use.who();
		}
		return found;
	}

	void forget(UUID player) {
		lastUse.remove(player);
	}

	void clear() {
		placedTnt.clear();
		primedTnt.clear();
		lastUse.clear();
	}

	private static <K, V> LinkedHashMap<K, V> bounded() {
		return new LinkedHashMap<>(64, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > MEMORY;
			}
		};
	}
}
