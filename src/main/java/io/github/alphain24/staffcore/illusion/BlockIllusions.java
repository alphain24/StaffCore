package io.github.alphain24.staffcore.illusion;

import io.github.alphain24.staffcore.StaffCore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every block this server is lying to a client about, in one place.
 *
 * <h2>The bug this exists to fix, and why it took three features to see it</h2>
 * Three things here show one player a block that is not there: canary decoys, the replay
 * overlay, and the rollback preview. All three did it the same way and all three had the same
 * defect, which nobody noticed because each looked correct on its own.
 * <p>
 * A {@link ClientboundBlockUpdatePacket} is <b>a delta against the chunk the client is already
 * holding</b>, not a fact about the world. It is not stored anywhere, it is not re-applied, and
 * the server keeps no record that the client's copy now differs. The moment the server sends
 * that chunk again — {@code PlayerChunkSender.sendChunk} builds
 * {@code ClientboundLevelChunkWithLightPacket} straight from the honest {@code LevelChunk} — the
 * client takes the real data and every delta for that chunk is gone. The server goes on
 * believing it drew them.
 * <p>
 * That happens constantly and for reasons nobody chose: leaving view distance and coming back,
 * a relog, a dimension change, any resend the server does for its own purposes. It presented as
 * decoys being "hit or miss", reported by a person with an x-ray pack, months after every
 * server-side test passed.
 *
 * <h2>Why bulk anti-xray does not have this problem</h2>
 * Because it rewrites the chunk's palette during serialisation, so the lie <em>is</em> the
 * chunk. Volume is the usual reason given for doing it that way — a per-block packet for every
 * hidden ore would be absurd — but persistence is the other half, and it is the half that
 * matters here. StaffCore is not hiding thousands of blocks; it is showing a handful. The right
 * answer is not to move to palette rewriting, it is to re-assert the handful at the moment they
 * are lost.
 *
 * <h2>An event, not a poll</h2>
 * The first fix re-sent everything every five seconds. That is wrong in four ways, and only the
 * first is about tidiness:
 * <ul>
 *   <li>It polls a problem that has an exact event.</li>
 *   <li>It costs packets proportional to blocks × players, forever, for a picture that has
 *       usually not changed.</li>
 *   <li>It leaves a window — up to five seconds — in which the client is showing the truth.</li>
 *   <li><b>A flickering ore is a tell.</b> An x-ray user who notices that some ores blink and
 *       others do not learns to distrust the ones that blink, which loses exactly the users
 *       worth catching. A decoy that is wrong occasionally is worse than no decoy, because it
 *       teaches the lesson.</li>
 * </ul>
 * So {@link #onChunkSent} is driven by a mixin on the chunk-send path and re-asserts
 * immediately, in the same call, for that chunk only.
 *
 * <h2>Indexed by chunk, because of where it runs</h2>
 * Chunks are sent constantly, to every player, for the life of the server, and the
 * overwhelming majority hold nothing of ours. So the outer lookup is by chunk key and the
 * common case is one map lookup returning null. Anything proportional to "how many illusions
 * this player has" rather than "how many are in this chunk" would be a per-chunk cost on the
 * busiest path the server has.
 */
public final class BlockIllusions {
	private BlockIllusions() {}

	/**
	 * Which feature owns a lie.
	 * <p>
	 * Present so one feature can take its own illusions down without disturbing another's. A
	 * staff member can be watching a replay while holding live canaries, and clearing the
	 * replay must not hand their client the truth about a decoy.
	 */
	public enum Source {
		/** A fake ore in solid rock. Nobody legitimate can see it, so nobody legitimate digs to it. */
		CANARY,
		/** The world as it was, drawn along a replayed path. */
		REPLAY,
		/** What a rollback would restore, before anybody confirms it. */
		PREVIEW
	}

	private record Held(Source source, BlockState shown) {}

	/**
	 * Which chunk, in which world.
	 *
	 * <h2>The world is not decoration</h2>
	 * A chunk key is x and z. A {@link ClientboundBlockUpdatePacket} carries a position and a
	 * state and <b>no dimension at all</b> — it applies wherever the client happens to be.
	 * <p>
	 * So keying by chunk alone meant a decoy in the Overworld at chunk (4, -9) was re-asserted
	 * the moment its owner loaded chunk (4, -9) in the Nether, and drawn there: a fake ore in
	 * netherrack, at coordinates nobody chose, put back by the hook every time that chunk
	 * arrived. All three features at once, exactly as the first bug in this class was.
	 * <p>
	 * The defect was in what the key was <em>missing</em> rather than in anything it contained,
	 * which is why reading the store did not show it.
	 */
	private record Where(String world, long chunk) {}

	/** viewer -> where -> position -> what they are being shown. */
	private static final Map<UUID, Map<Where, Map<BlockPos, Held>>> SHOWN =
			new ConcurrentHashMap<>();

	/**
	 * The chunk a position is in, as one long.
	 * <p>
	 * {@code ChunkPos.pack} — this is {@code asLong} in most versions and was renamed in 26.2.
	 * See the version-sensitive list in decisions.md.
	 */
	private static Where key(ServerLevel level, BlockPos pos) {
		return new Where(io.github.alphain24.staffcore.compat.Mc.dimensionId(level),
				ChunkPos.pack(pos));
	}

	// ---------------------------------------------------------------- showing

	/**
	 * Shows one player one block that is not there, and remembers that it did.
	 * <p>
	 * Remembering is the whole point. Sending the packet was never the hard part.
	 */
	public static void show(ServerPlayer viewer, ServerLevel level, Source source, BlockPos pos,
			BlockState shown) {

		if (viewer == null || viewer.connection == null || level == null || pos == null
				|| shown == null) {
			return;
		}

		SHOWN.computeIfAbsent(viewer.getUUID(), k -> new ConcurrentHashMap<>())
				.computeIfAbsent(key(level, pos), k -> new LinkedHashMap<>())
				.put(pos.immutable(), new Held(source, shown));

		viewer.connection.send(new ClientboundBlockUpdatePacket(pos, shown));
	}

	/**
	 * Replaces everything one source is showing this viewer.
	 * <p>
	 * Anything that source had drawn and is not in the new set is told the truth again, so a
	 * caller redrawing a moving picture does not leave a trail behind it. Other sources are
	 * left alone.
	 */
	public static void replace(ServerPlayer viewer, ServerLevel level, Source source,
			Map<BlockPos, BlockState> blocks) {

		if (viewer == null || viewer.connection == null) return;

		for (BlockPos gone : mine(viewer.getUUID(), source).keySet()) {
			if (!blocks.containsKey(gone)) hide(viewer, level, source, gone);
		}
		blocks.forEach((pos, state) -> show(viewer, level, source, pos, state));
	}

	/**
	 * Stops showing one block, and tells the client what is really there.
	 * <p>
	 * The truth is read from the world as it is now rather than from a remembered "before".
	 * Anything that changed while the illusion was up would otherwise be corrected to a state
	 * that is also wrong, and the point of taking an illusion down is that the client stops
	 * disagreeing with the server.
	 */
	public static void hide(ServerPlayer viewer, ServerLevel level, Source source, BlockPos pos) {
		if (viewer == null || pos == null) return;

		Map<Where, Map<BlockPos, Held>> chunks = SHOWN.get(viewer.getUUID());
		if (chunks == null || level == null) return;

		Where where = key(level, pos);
		Map<BlockPos, Held> here = chunks.get(where);
		if (here == null) return;

		Held held = here.get(pos);
		if (held == null || held.source() != source) return;

		here.remove(pos);
		if (here.isEmpty()) chunks.remove(where);

		if (viewer.connection != null && level != null) {
			viewer.connection.send(new ClientboundBlockUpdatePacket(level, pos));
		}
	}

	/**
	 * Takes down everything one source is showing this viewer, in every world, leaving the
	 * other sources up.
	 * <p>
	 * Every world, not only the one they are standing in. A replay that followed somebody
	 * through a portal has illusions on both sides of it, and leaving the far side registered
	 * would have the chunk-send hook draw them again the next time that player went back.
	 * <p>
	 * The correcting packet goes only to the world they are actually in. There is nothing to
	 * correct anywhere else: the client is not there, and it is sent the honest chunk when it
	 * arrives.
	 */
	public static void clear(ServerPlayer viewer, ServerLevel level, Source source) {
		if (viewer == null) return;

		Map<Where, Map<BlockPos, Held>> chunks = SHOWN.get(viewer.getUUID());
		if (chunks == null) return;

		String here = level == null ? null
				: io.github.alphain24.staffcore.compat.Mc.dimensionId(level);

		for (Map.Entry<Where, Map<BlockPos, Held>> entry : Map.copyOf(chunks).entrySet()) {
			boolean sameWorld = entry.getKey().world().equals(here);

			for (Map.Entry<BlockPos, Held> shown : Map.copyOf(entry.getValue()).entrySet()) {
				if (shown.getValue().source() != source) continue;

				entry.getValue().remove(shown.getKey());
				if (sameWorld && viewer.connection != null) {
					viewer.connection.send(
							new ClientboundBlockUpdatePacket(level, shown.getKey()));
				}
			}
			if (entry.getValue().isEmpty()) chunks.remove(entry.getKey());
		}
	}

	// ------------------------------------------------------------ re-asserting

	/**
	 * Re-sends every illusion this player has in one chunk, immediately after the chunk went out.
	 * <p>
	 * Called from the chunk-send hook, on the server thread, once per chunk per player. The
	 * usual answer is that there is nothing here and the cost is a single failed map lookup.
	 * <p>
	 * Ordering matters and is the reason this is a hook rather than a scheduled task: these
	 * packets have to reach the client <em>after</em> the chunk that erased them. Injecting at
	 * the return of the send guarantees that on one connection, because a connection delivers
	 * in order.
	 *
	 * @return how many illusions were re-asserted, for tests and diagnostics
	 */
	public static int onChunkSent(ServerPlayer viewer, ServerLevel level, int chunkX,
			int chunkZ) {
		return onChunkSent(viewer, level, ChunkPos.pack(chunkX, chunkZ));
	}

	/**
	 * As {@link #onChunkSent(ServerPlayer, int, int)}, taking the packed key the caller already
	 * has.
	 * <p>
	 * The hook has a {@code ChunkPos} in hand and its {@code x} and {@code z} fields are
	 * private in 26.2, so this spares it unpacking a key only to have it packed again.
	 */
	public static int onChunkSent(ServerPlayer viewer, ServerLevel level, long chunkKey) {
		if (SHOWN.isEmpty() || viewer == null || viewer.connection == null || level == null) {
			return 0;
		}

		Map<Where, Map<BlockPos, Held>> chunks = SHOWN.get(viewer.getUUID());
		if (chunks == null || chunks.isEmpty()) return 0;

		Map<BlockPos, Held> here = chunks.get(new Where(
				io.github.alphain24.staffcore.compat.Mc.dimensionId(level), chunkKey));
		if (here == null || here.isEmpty()) return 0;

		int sent = 0;
		for (Map.Entry<BlockPos, Held> entry : Map.copyOf(here).entrySet()) {
			viewer.connection.send(
					new ClientboundBlockUpdatePacket(entry.getKey(), entry.getValue().shown()));
			sent++;
		}
		REASSERTED.addAndGet(sent);
		return sent;
	}

	private static final java.util.concurrent.atomic.AtomicLong REASSERTED =
			new java.util.concurrent.atomic.AtomicLong();

	/**
	 * How many illusions have been re-asserted after a chunk send since the server started.
	 * <p>
	 * Worth reporting because zero is meaningful. If this stays at zero on a server with
	 * canaries out and players moving, the hook is not firing — and the symptom of that is
	 * decoys that quietly stop working rather than anything that looks like an error.
	 */
	public static long reasserted() {
		return REASSERTED.get();
	}

	// ------------------------------------------------------------- lifecycle

	/**
	 * Drops everything remembered for one player without sending anything.
	 * <p>
	 * For a disconnect. There is nobody to correct, and a reconnecting client is sent honest
	 * chunks from scratch — so the illusions are genuinely gone rather than merely forgotten,
	 * and the features that own them re-establish whatever they still want.
	 */
	public static void forget(UUID viewer) {
		if (viewer != null) SHOWN.remove(viewer);
	}

	/** What one source is currently showing one viewer. Never null. */
	public static Map<BlockPos, BlockState> mine(UUID viewer, Source source) {
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		Map<Where, Map<BlockPos, Held>> chunks = SHOWN.get(viewer);
		if (chunks == null) return out;

		for (Map<BlockPos, Held> here : chunks.values()) {
			here.forEach((pos, held) -> {
				if (held.source() == source) out.put(pos, held.shown());
			});
		}
		return out;
	}

	public static int countFor(UUID viewer, Source source) {
		return mine(viewer, source).size();
	}

	/** How many chunks hold something for this viewer. For the per-chunk cost assertion. */
	public static int chunksFor(UUID viewer) {
		Map<Where, Map<BlockPos, Held>> chunks = SHOWN.get(viewer);
		return chunks == null ? 0 : chunks.size();
	}

	/** Only for tests and a deliberate reset. Sends nothing. */
	public static void forgetAll() {
		SHOWN.clear();
		REASSERTED.set(0);
	}

	static {
		StaffCore.LOGGER.debug("[Illusions] ready");
	}
}
