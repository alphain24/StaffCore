package io.github.alphain24.staffcore.discord.evidence;

import io.github.alphain24.staffcore.api.DiscordReplayTrack;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A replay drawn from above: where the player went, and what they broke and placed.
 * <p>
 * A replay in game is watched from inside it; Discord can only show a picture. So the picture is a map,
 * north up, with the path going from blue at the start to yellow at the end, a green dot where it began
 * and a red one where it ended, red squares for blocks broken, green for blocks placed, and cyan
 * diamonds for ore broken — the one mark an x-ray review looks for first. Gaps longer than ten seconds,
 * or jumps further than sixteen blocks, are not joined up: a teleport or a relog is a gap, not a path.
 * <p>
 * <b>No text in the picture.</b> Drawing text needs fonts, which a server's Java often does not have;
 * shapes need nothing. Everything worth reading — the window, the scale, the counts — goes in the message
 * the picture is attached to, from {@link #summary}.
 * <p>
 * Drawn on the companion's thread and never kept: position history has a retention of its own, and a
 * saved picture of it would outlive that.
 */
public final class ReplayMap {
	private ReplayMap() {}

	public static final int SIZE = 900;
	private static final int MARGIN = 30;
	private static final long GAP_MS = 10_000L;
	private static final double JUMP = 16.0;

	private static final Color BACKGROUND = new Color(0x1E1F22);
	private static final Color GRID = new Color(0x2E3035);
	private static final Color SCALE_BAR = new Color(0xDDDDDD);
	private static final Color START = new Color(0x5BE87A);
	private static final Color END = new Color(0xFF5C57);
	private static final Color BROKE = new Color(0xE0563C);
	private static final Color PLACED = new Color(0x5BE87A);
	private static final Color ORE = new Color(0x3FE0F0);

	/** What a map shows, for the message it is attached to. */
	public record Drawn(byte[] png, String world, int gridBlocks, int scaleBlocks, int minX, int maxX, int minZ,
			int maxZ, long otherWorldPoints) {}

	/** The world the map is drawn in: the one the player spent most samples in. */
	static String mainWorld(DiscordReplayTrack track) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (DiscordReplayTrack.Point point : track.points()) counts.merge(point.world(), 1, Integer::sum);
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/**
	 * Draws it.
	 *
	 * @throws IOException  when the image cannot be written
	 * @throws LinkageError or {@link InternalError} on a Java without graphics support; callers say so
	 */
	public static Drawn render(DiscordReplayTrack track) throws IOException {
		String main = mainWorld(track);
		String world = main != null ? main : track.changes().isEmpty() ? "" : track.changes().get(0).world();
		List<DiscordReplayTrack.Point> points = track.points().stream().filter(p -> p.world().equals(world)).toList();
		List<DiscordReplayTrack.Change> changes = track.changes().stream().filter(c -> world.equals(c.world())).toList();

		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (DiscordReplayTrack.Point p : points) {
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minZ = Math.min(minZ, p.z());
			maxZ = Math.max(maxZ, p.z());
		}
		for (DiscordReplayTrack.Change c : changes) {
			minX = Math.min(minX, c.x());
			maxX = Math.max(maxX, c.x() + 1);
			minZ = Math.min(minZ, c.z());
			maxZ = Math.max(maxZ, c.z() + 1);
		}
		// A player who stood still is still drawn, with some ground around them.
		double spanX = Math.max(maxX - minX, 16);
		double spanZ = Math.max(maxZ - minZ, 16);
		double centreX = (minX + maxX) / 2;
		double centreZ = (minZ + maxZ) / 2;
		double span = Math.max(spanX, spanZ) * 1.1 + 8;
		double scale = (SIZE - 2.0 * MARGIN) / span;
		double left = centreX - span / 2;
		double top = centreZ - span / 2;

		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(BACKGROUND);
			g.fillRect(0, 0, SIZE, SIZE);

			int grid = niceStep(span / 8);
			g.setColor(GRID);
			g.setStroke(new BasicStroke(1f));
			for (double x = Math.floor(left / grid) * grid; x <= left + span; x += grid) {
				double sx = MARGIN + (x - left) * scale;
				g.draw(new Line2D.Double(sx, MARGIN, sx, SIZE - MARGIN));
			}
			for (double z = Math.floor(top / grid) * grid; z <= top + span; z += grid) {
				double sy = MARGIN + (z - top) * scale;
				g.draw(new Line2D.Double(MARGIN, sy, SIZE - MARGIN, sy));
			}

			// The path, coloured by time, broken at gaps and jumps.
			g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			long start = points.isEmpty() ? track.from() : points.get(0).at();
			long end = points.isEmpty() ? track.to() : points.get(points.size() - 1).at();
			for (int i = 1; i < points.size(); i++) {
				DiscordReplayTrack.Point a = points.get(i - 1);
				DiscordReplayTrack.Point b = points.get(i);
				if (b.at() - a.at() > GAP_MS || Math.hypot(b.x() - a.x(), b.z() - a.z()) > JUMP) continue;
				g.setColor(along(end == start ? 1 : (double) (b.at() - start) / (end - start)));
				g.draw(new Line2D.Double(MARGIN + (a.x() - left) * scale, MARGIN + (a.z() - top) * scale,
						MARGIN + (b.x() - left) * scale, MARGIN + (b.z() - top) * scale));
			}

			// Blocks, on top of the path, ore last so it is never hidden.
			double cell = Math.max(3, scale);
			for (int pass = 0; pass < 2; pass++) {
				for (DiscordReplayTrack.Change c : changes) {
					boolean ore = c.broke() && isOre(c.block());
					if ((pass == 1) != ore) continue;
					double sx = MARGIN + (c.x() + 0.5 - left) * scale;
					double sy = MARGIN + (c.z() + 0.5 - top) * scale;
					if (ore) {
						double r = Math.max(5, cell * 0.9);
						Path2D.Double diamond = new Path2D.Double();
						diamond.moveTo(sx, sy - r);
						diamond.lineTo(sx + r, sy);
						diamond.lineTo(sx, sy + r);
						diamond.lineTo(sx - r, sy);
						diamond.closePath();
						g.setColor(ORE);
						g.fill(diamond);
					} else {
						g.setColor(c.broke() ? BROKE : PLACED);
						g.fill(new Rectangle2D.Double(sx - cell / 2, sy - cell / 2, cell, cell));
					}
				}
			}

			if (!points.isEmpty()) {
				dot(g, points.get(0), left, top, scale, START);
				dot(g, points.get(points.size() - 1), left, top, scale, END);
			}

			// A scale bar along the bottom, its length given in the message.
			int bar = niceStep(span / 4);
			g.setColor(SCALE_BAR);
			g.setStroke(new BasicStroke(3f));
			double barEnd = MARGIN + bar * scale;
			g.draw(new Line2D.Double(MARGIN, SIZE - MARGIN / 2.0, barEnd, SIZE - MARGIN / 2.0));
			g.draw(new Line2D.Double(MARGIN, SIZE - MARGIN / 2.0 - 5, MARGIN, SIZE - MARGIN / 2.0 + 5));
			g.draw(new Line2D.Double(barEnd, SIZE - MARGIN / 2.0 - 5, barEnd, SIZE - MARGIN / 2.0 + 5));

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
			return new Drawn(out.toByteArray(), world, grid, bar, (int) Math.floor(minX), (int) Math.ceil(maxX),
					(int) Math.floor(minZ), (int) Math.ceil(maxZ), track.points().size() - points.size());
		} finally {
			g.dispose();
		}
	}

	/** The words that go with a map. */
	public static String summary(DiscordReplayTrack track, Drawn drawn) {
		long broke = track.changes().stream().filter(DiscordReplayTrack.Change::broke).count();
		long placed = track.changes().size() - broke;
		Map<String, Integer> ores = new HashMap<>();
		for (DiscordReplayTrack.Change c : track.changes()) {
			if (c.broke() && isOre(c.block())) ores.merge(shortId(c.block()), 1, Integer::sum);
		}
		double walked = 0;
		List<DiscordReplayTrack.Point> points = track.points();
		for (int i = 1; i < points.size(); i++) {
			DiscordReplayTrack.Point a = points.get(i - 1);
			DiscordReplayTrack.Point b = points.get(i);
			if (a.world().equals(b.world()) && b.at() - a.at() <= GAP_MS) {
				double d = Math.hypot(b.x() - a.x(), b.z() - a.z());
				if (d <= JUMP) walked += d;
			}
		}
		StringBuilder out = new StringBuilder();
		out.append("**").append(track.playerName()).append("** from <t:").append(track.from() / 1000)
				.append(":f> to <t:").append(track.to() / 1000).append(":t> in `").append(drawn.world()).append("`");
		out.append("\nx ").append(drawn.minX()).append(" to ").append(drawn.maxX()).append(", z ")
				.append(drawn.minZ()).append(" to ").append(drawn.maxZ()).append(" · north is up · grid ")
				.append(drawn.gridBlocks()).append(" blocks · white bar ").append(drawn.scaleBlocks()).append(" blocks");
		out.append("\nAbout ").append(Math.round(walked)).append(" blocks moved · ").append(broke).append(" broken · ")
				.append(placed).append(" placed");
		if (!ores.isEmpty()) {
			out.append(" · ore broken: ");
			ores.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6)
					.forEach(e -> out.append(e.getValue()).append("× ").append(e.getKey()).append(", "));
			out.setLength(out.length() - 2);
		}
		out.append("\nThe path runs blue, through purple, to yellow over time; green dot start, red dot end; red "
				+ "squares broken, green placed, cyan diamonds ore.");
		if (track.runs() > 1) out.append(" Gaps in the line are real: they stopped, relogged or teleported.");
		if (drawn.otherWorldPoints() > 0) out.append("\nTime spent in other dimensions is not drawn.");
		if (track.truncated()) out.append("\nThe movement was longer than a replay reconstructs, so the end is missing.");
		if (track.changesTruncated()) out.append("\nOnly the first ").append(DiscordReplayTrack.MAX_CHANGES)
				.append(" block changes are drawn.");
		return out.toString();
	}

	static boolean isOre(String block) {
		return block != null && (block.endsWith("_ore") || block.endsWith("ancient_debris"));
	}

	private static String shortId(String block) {
		return block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
	}

	/** 1, 2 or 5 times a power of ten, at least the given size. */
	static int niceStep(double atLeast) {
		double base = Math.pow(10, Math.floor(Math.log10(Math.max(1, atLeast))));
		for (int m : new int[] {1, 2, 5, 10}) {
			if (base * m >= atLeast) return (int) Math.max(1, base * m);
		}
		return (int) (base * 10);
	}

	/** Blue, violet, magenta, yellow: never grey in the middle, and never the red of a broken block. */
	private static final int[] PATH = {0x3B82F6, 0x8B5CF6, 0xD946EF, 0xFACC15};

	/** The path's colour a fraction of the way through the window. */
	static Color along(double t) {
		double f = Math.max(0, Math.min(1, t)) * (PATH.length - 1);
		int i = Math.min((int) f, PATH.length - 2);
		double part = f - i;
		int from = PATH[i];
		int to = PATH[i + 1];
		return new Color(mix(from >> 16, to >> 16, part), mix(from >> 8, to >> 8, part), mix(from, to, part));
	}

	private static int mix(int a, int b, double part) {
		int x = a & 0xFF;
		int y = b & 0xFF;
		return (int) Math.round(x + (y - x) * part);
	}

	private static void dot(Graphics2D g, DiscordReplayTrack.Point p, double left, double top, double scale, Color colour) {
		double sx = MARGIN + (p.x() - left) * scale;
		double sy = MARGIN + (p.z() - top) * scale;
		g.setColor(colour);
		g.fill(new Ellipse2D.Double(sx - 7, sy - 7, 14, 14));
		g.setColor(BACKGROUND);
		g.setStroke(new BasicStroke(2f));
		g.draw(new Ellipse2D.Double(sx - 7, sy - 7, 14, 14));
	}

	/** A file name for the picture. */
	public static String fileName(DiscordReplayTrack track) {
		return "replay-" + track.playerName().replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT) + ".png";
	}
}
