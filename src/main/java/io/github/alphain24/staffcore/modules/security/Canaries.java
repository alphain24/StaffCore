package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A handful of ores that are not there, shown to one player, in rock nothing can see into.
 *
 * <h2>Why this is worth having when the detector already exists</h2>
 * {@link XrayDetector} reads the break log and scores how much somebody's mining looks like
 * cheating. It is inference: good inference, with the evidence attached, and still a judgement
 * about a pattern. A canary is not a judgement. The block was never there, only this player was
 * told about it, and no legitimate route to it exists — so walking to it is not evidence that
 * somebody probably cheated, it is somebody acting on information they could only have had one
 * way.
 * <p>
 * That is why the threshold is three and not thirty. The detector needs volume before it can
 * say anything; this needs almost none.
 *
 * <h2>Why there is no chunk mixin</h2>
 * The obvious implementation rewrites the block palette as a chunk is serialised, which is what
 * a bulk anti-xray does because it has to change thousands of blocks at once. For six blocks
 * per player, a plain {@link ClientboundBlockUpdatePacket} does the same job: the chunk arrives
 * honestly, and one packet afterwards tells the client that one position is something else.
 * <p>
 * Worth being explicit about, because the mixin version was the plan. It would have been this
 * mod's most version-fragile hook, sitting in the middle of chunk serialisation, for no gain
 * over a packet that has existed unchanged for years.
 *
 * <h2>The false positive this is built around</h2>
 * A decoy is placed in fully encased rock, so reaching it means breaking a neighbour first.
 * The moment anybody breaks a neighbour — the owner, another player, an explosion — the decoy
 * is retired and the real block sent back. Without that rule, an ordinary miner tunnels past,
 * exposes a diamond ore that is not there, mines it, and gets reported for x-ray. It also
 * removes the ghost block, which is the same fix for a different reason.
 * <p>
 * What is left is a player who breaks a decoy while every one of its six neighbours is still
 * standing. There is no way to see that block, and no reason to dig at it.
 */
public final class Canaries {
	private Canaries() {}

	/**
	 * One decoy.
	 *
	 * @param shown what the client was told is there, kept so the hit can say what they went for
	 */
	public record Canary(UUID owner, String world, BlockPos pos, BlockState shown, long sentAt) {}

	/**
	 * Decoys per player. Small, bounded by {@code canaryDensity}, and dropped on disconnect.
	 * <p>
	 * In memory rather than in the database on purpose. A decoy is only real while the client
	 * believes it, and a client that reconnects has been sent the honest chunk again — so a
	 * canary that survived a restart would be a position we think is fake and the player sees
	 * as stone, which is a false positive waiting for somebody to mine there.
	 */
	private static final Map<UUID, Map<BlockPos, Canary>> LIVE = new ConcurrentHashMap<>();

	/** Hits this session, per player. Reset on disconnect, like the decoys themselves. */
	private static final Map<UUID, Integer> HITS = new ConcurrentHashMap<>();

	// ------------------------------------------------------------------ placement

	/** Whether decoys should exist at all right now. */
	public static boolean enabled() {
		StaffConfig cfg = StaffConfig.get();
		return cfg.canaryBlocks && cfg.canaryDensity > 0 && !AntiXrayCompanion.present();
	}

	/**
	 * Tops a player up to the configured number of decoys.
	 * <p>
	 * Called on a slow timer rather than per tick. Every candidate position costs seven block
	 * reads and the answer is usually no, so this is bounded twice: a fixed number of attempts
	 * per call, and a cap on how many decoys can exist.
	 */
	public static void maintain(ServerPlayer player) {
		if (!enabled() || player == null) return;
		if (!(player.level() instanceof ServerLevel level)) return;

		Map<BlockPos, Canary> mine = LIVE.computeIfAbsent(player.getUUID(),
				k -> new LinkedHashMap<>());
		int wanted = StaffConfig.get().canaryDensity;
		if (mine.size() >= wanted) return;

		// Ten attempts, not "until we have enough". A player standing in a cave or above the
		// depth limit has no valid positions at all, and a loop that kept looking would spend
		// the whole tick discovering that every time it ran.
		for (int attempt = 0; attempt < 10 && mine.size() < wanted; attempt++) {
			BlockPos candidate = pick(level, player);
			if (candidate == null || mine.containsKey(candidate)) continue;
			placeAt(player, level, candidate);
		}
	}

	/**
	 * Places one decoy at a known position, and tells the client about it.
	 * <p>
	 * The same path {@link #maintain} uses once it has chosen somewhere, exposed so a test can
	 * put a decoy in a place it built rather than waiting for the random search to find one.
	 * Separating the choosing from the placing is what lets the tests exercise the real
	 * placement instead of a copy of it that can drift.
	 *
	 * @return true when a decoy now exists there
	 */
	public static boolean placeAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (player == null || level == null || pos == null) return false;

		BlockState shown = decoyFor(level.getBlockState(pos));
		if (shown == null) return false;

		BlockPos fixed = pos.immutable();
		LIVE.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>())
				.put(fixed, new Canary(player.getUUID(), Mc.dimensionId(level), fixed, shown,
						System.currentTimeMillis()));

		if (player.connection != null) {
			player.connection.send(new ClientboundBlockUpdatePacket(fixed, shown));
		}
		return true;
	}

	/**
	 * Whether this position is one a decoy could go in.
	 * <p>
	 * The predicate {@link #pick} applies, and the only one — a test asking this question is
	 * asking the real thing rather than a restatement of it.
	 */
	public static boolean wouldPlaceAt(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos)) return false;
		return isPlainStone(level.getBlockState(pos)) && fullyEncased(level, pos);
	}

	/**
	 * A position that is worth lying about, or null.
	 * <p>
	 * Random within the radius rather than swept, because a sweep produces decoys in a
	 * pattern, and a pattern is something somebody eventually notices and avoids.
	 */
	private static BlockPos pick(ServerLevel level, ServerPlayer player) {
		StaffConfig cfg = StaffConfig.get();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		BlockPos from = player.blockPosition();

		int x = from.getX() + random.nextInt(-cfg.canaryRadius, cfg.canaryRadius + 1);
		int z = from.getZ() + random.nextInt(-cfg.canaryRadius, cfg.canaryRadius + 1);
		int y = random.nextInt(level.getMinY() + 8, Math.min(cfg.canaryMaxY, from.getY() + 8) + 1);
		BlockPos pos = new BlockPos(x, y, z);

		// Never touch a chunk that is not already loaded. Loading one to place a decoy would
		// make this feature a source of chunk loading, which is the last thing a server needs
		// from something whose whole point is to be unnoticeable.
		return wouldPlaceAt(level, pos) ? pos : null;
	}

	/**
	 * Only ordinary stone, so a decoy never overwrites something that was interesting.
	 * <p>
	 * Placing one over a real ore would hide it; over anything with a block entity would show
	 * the client a chest that is not there. The whitelist is the safe direction — an unknown
	 * modded block is skipped rather than lied about.
	 */
	private static boolean isPlainStone(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.NETHERRACK) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE);
	}

	/** Six solid neighbours: no face this could be seen through, and no way in but through one. */
	private static boolean fullyEncased(ServerLevel level, BlockPos pos) {
		for (Direction face : Direction.values()) {
			BlockPos next = pos.relative(face);
			if (!level.isLoaded(next)) return false;

			BlockState neighbour = level.getBlockState(next);
			if (neighbour.isAir() || !neighbour.canOcclude()) return false;
		}
		return true;
	}

	/**
	 * What to show, matched to the rock around it.
	 * <p>
	 * Deepslate diamond in deepslate, diamond in stone, ancient debris in netherrack. A
	 * diamond ore in a netherrack wall is a decoy that says "this is fake" to anybody who
	 * looks, which defeats it for exactly the players it is aimed at.
	 */
	private static BlockState decoyFor(BlockState real) {
		if (real.is(Blocks.DEEPSLATE)) return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
		if (real.is(Blocks.NETHERRACK)) return Blocks.ANCIENT_DEBRIS.defaultBlockState();
		if (isPlainStone(real)) return Blocks.DIAMOND_ORE.defaultBlockState();
		return null;
	}

	// ------------------------------------------------------------------- contact

	/** What a break turned out to be. */
	public enum Contact { NOTHING, HIT, RETIRED }

	/**
	 * Called for every block break, by anybody.
	 * <p>
	 * Two questions in one pass, and the order matters. Breaking a decoy is a hit. Breaking
	 * anything <em>next to</em> a decoy retires it — whoever did it, including another player
	 * and including the owner mining honestly nearby, because after that break the position is
	 * reachable and the next person to touch it has a reason to.
	 */
	public static Contact onBreak(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
		if (LIVE.isEmpty() || level == null) return Contact.NOTHING;

		String world = Mc.dimensionId(level);
		Contact result = Contact.NOTHING;

		for (Map.Entry<UUID, Map<BlockPos, Canary>> owned : LIVE.entrySet()) {
			Canary hit = owned.getValue().get(pos);

			if (hit != null && hit.world().equals(world)) {
				owned.getValue().remove(pos);
				// Only the owner can have acted on it: nobody else was told it was there.
				// Another player breaking it is a coincidence, and recording that as evidence
				// against them would be the worst thing this class could do.
				if (breaker != null && breaker.getUUID().equals(owned.getKey())) {
					record(level, breaker, hit);
					result = Contact.HIT;
				} else {
					// Somebody else broke it. No hit, and the owner's client still has to be
					// told, because it is holding a block that no longer exists either way.
					resync(level, owned.getKey(), hit, null);
				}
				continue;
			}

			// Retirement, in every direction. The resync goes to the owner rather than to
			// whoever broke the neighbour — they are the only client holding the lie.
			for (Direction face : Direction.values()) {
				Canary near = owned.getValue().get(pos.relative(face));
				if (near == null || !near.world().equals(world)) continue;

				owned.getValue().remove(near.pos());
				resync(level, owned.getKey(), near, breaker);
				if (result == Contact.NOTHING) result = Contact.RETIRED;
			}
		}
		return result;
	}

	/**
	 * Explosions destroy blocks without any break event, so they are handled separately.
	 * <p>
	 * Missing this would leave decoys standing in the middle of a crater with nothing solid
	 * around them — visible to anybody who walked past, and a false positive for whoever mined
	 * the obvious diamond in the rubble.
	 */
	public static void onExplosion(ServerLevel level, Collection<BlockPos> destroyed) {
		if (LIVE.isEmpty() || level == null || destroyed == null) return;
		for (BlockPos pos : destroyed) onBreak(level, null, pos);
	}

	/**
	 * Puts the real block back on the owner's client.
	 * <p>
	 * Sent from the world rather than from a remembered state, so what arrives is whatever is
	 * actually there now — which after a break is air, and after an explosion may be anything.
	 */
	private static void resync(ServerLevel level, UUID owner, Canary canary, ServerPlayer known) {
		// The player is passed in where the caller already has them, and looked up otherwise.
		// Not an optimisation: the lookup goes through the player list, and a player who is
		// mid-disconnect or otherwise not listed would silently get no resync — leaving the
		// one client that believes the lie still believing it.
		ServerPlayer player = known != null && known.getUUID().equals(owner) ? known
				: level.getServer() == null ? null
						: level.getServer().getPlayerList().getPlayer(owner);

		if (player != null && player.connection != null) {
			player.connection.send(new ClientboundBlockUpdatePacket(level, canary.pos()));
		}
	}

	/**
	 * Records a hit, and opens a case at the threshold.
	 * <p>
	 * The resync goes first. Whatever else happens, the client is holding a block that does
	 * not exist, and leaving it there while a database write happens is how a player ends up
	 * swinging at air.
	 */
	private static void record(ServerLevel level, ServerPlayer player, Canary canary) {
		resync(level, player.getUUID(), canary, player);

		int count = HITS.merge(player.getUUID(), 1, Integer::sum);
		StaffCore.LOGGER.info("[Canary] {} broke a decoy at {} ({} this session)",
				Mc.name(player), canary.pos().toShortString(), count);

		// Severity scales with the count, and stays below the auto-open threshold until the
		// configured number. One hit is a thing to have on file; three is an investigation.
		int threshold = Math.max(1, StaffConfig.get().canaryCaseThreshold);
		int confidence = count >= threshold
				? Math.min(99, 80 + (count - threshold) * 5)
				: Math.max(10, (100 / threshold) * count / 2);

		io.github.alphain24.staffcore.module.Mods.cases().emit(level.getServer(),
				io.github.alphain24.staffcore.modules.cases.Signal.Type.XRAY,
				player.getUUID(), Mc.name(player), confidence,
				"broke a decoy ore that was never there, at " + canary.pos().toShortString()
						+ " (" + count + " this session)", "canary");

		// Nothing here punishes. A canary is about as conclusive as this mod gets, which is
		// exactly why a human confirming it costs nothing worth saving.
	}

	// ------------------------------------------------------------------ lifecycle

	/** Drops everything for one player. On disconnect: the client is no longer holding a lie. */
	public static void forget(UUID player) {
		if (player == null) return;
		LIVE.remove(player);
		HITS.remove(player);
	}

	/** How many decoys this player currently has out. For the diagnostic. */
	public static int liveFor(UUID player) {
		Map<BlockPos, Canary> mine = LIVE.get(player);
		return mine == null ? 0 : mine.size();
	}

	/** Hits this session. For the player context panel. */
	public static int hitsFor(UUID player) {
		return HITS.getOrDefault(player, 0);
	}

	/** Every decoy currently out, for tests and the diagnostic. */
	public static List<Canary> all() {
		List<Canary> out = new ArrayList<>();
		for (Map<BlockPos, Canary> owned : LIVE.values()) out.addAll(owned.values());
		return out;
	}

	/** Drops everything. Only for tests and a deliberate reset. */
	public static void forgetAll() {
		LIVE.clear();
		HITS.clear();
	}
}
