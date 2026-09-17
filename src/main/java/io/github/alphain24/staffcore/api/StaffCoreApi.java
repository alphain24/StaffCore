package io.github.alphain24.staffcore.api;

import io.github.alphain24.staffcore.api.internal.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * StaffCore's published API: the one door a companion mod uses.
 * <p>
 * Everything in this package is the contract. Everything outside it — the modules, the storage,
 * the permission resolution — is StaffCore's own business and may change without notice, which
 * is why a companion that reaches past this package fails its own build. {@code api.internal}
 * belongs to StaffCore too, despite the name.
 *
 * <h2>What a companion gets</h2>
 * <ul>
 *   <li>Events, through {@link #addListener}: punishments, reports, signals, staff actions and
 *       staff chat, as they happen.</li>
 *   <li>A place in {@code /staff status}, through {@link #addStatus}, so an owner can see whether
 *       the companion is connected without reading its log.</li>
 *   <li>For the Discord companion, a place in the staff panel, through {@link #reportDiscordBot}.</li>
 *   <li>A folder for its files, {@link #configFolder}.</li>
 * </ul>
 * Acting — punishing, noting, claiming a report — is added to this package item by item, and
 * every path through it runs the same permission checks, rate limits and approvals the game's
 * own commands do.
 */
public final class StaffCoreApi {
	private StaffCoreApi() {}

	/**
	 * Bumped when something here changes in a way a companion has to know about.
	 * <p>
	 * 2: events for cases and appeals, a case on reports, a target on staff actions, and the report,
	 * note, freeze and staff chat calls on {@link DiscordAccess}.
	 * <p>
	 * 3: appeals from Discord — who filed, the conversation, stale verdicts, and the appeal calls on
	 * {@link DiscordAccess}.
	 * <p>
	 * 4: the slash command reads and writes on {@link DiscordAccess}, {@link #reportDiscordBot} for the
	 * staff panel, and {@link #configFolder}.
	 * <p>
	 * 5: the 1.2.0 additions — the wait a rejection sets, and what follows in {@link DiscordAccess}.
	 * <p>
	 * 6: the 1.3.0 additions — {@link StaffCoreEvent.CaseUpdated} for the cases channel, and the case
	 * card's calls on {@link DiscordAccess}.
	 */
	public static final int VERSION = 6;

	private static final List<StaffCoreListener> LISTENERS = new CopyOnWriteArrayList<>();
	private static final Map<String, Supplier<List<String>>> STATUS = new ConcurrentHashMap<>();
	private static volatile boolean discordCompanion;
	private static volatile boolean discordPosting;
	private static volatile boolean discordAppeals;
	private static volatile Supplier<DiscordBotStatus> discordBot;

	/** Starts telling this listener about events. */
	public static void addListener(StaffCoreListener listener) {
		if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
	}

	public static void removeListener(StaffCoreListener listener) {
		LISTENERS.remove(listener);
	}

	/** Whether anybody is listening; publishing is skipped entirely when nobody is. */
	public static boolean hasListeners() {
		return !LISTENERS.isEmpty();
	}

	/**
	 * Says that a Discord companion is configured and its bot is starting, so staff can link their
	 * accounts: {@code /staff discord link} refuses to hand out a code while nothing could redeem it.
	 */
	public static void declareDiscordCompanion() {
		discordCompanion = true;
	}

	public static boolean discordCompanionPresent() {
		return discordCompanion;
	}

	/**
	 * Says that the companion posts punishments, reports and alerts to Discord itself.
	 * <p>
	 * StaffCore's own webhook goes quiet from that moment, so a server with both configured does not
	 * post everything twice. Separate from {@link #declareDiscordCompanion}, because a bot that links
	 * accounts and posts nothing must not silence the webhook that does.
	 */
	public static void declareDiscordPosting() {
		discordPosting = true;
	}

	public static boolean discordPostingHandled() {
		return discordPosting;
	}

	/**
	 * Says that the companion takes ban appeals in Discord, so the ban screen can tell a player to use
	 * {@code /appeal} with their code rather than to find a staff member.
	 */
	public static void declareDiscordAppeals() {
		discordAppeals = true;
	}

	public static boolean discordAppealsTaken() {
		return discordAppeals;
	}

	/**
	 * Says how the Discord bot is, for the Discord section of the staff panel. Asked each time the
	 * section is opened, on the server thread, so it must be quick; if it throws, the panel says the
	 * bot could not report rather than failing to open.
	 */
	public static void reportDiscordBot(Supplier<DiscordBotStatus> status) {
		discordBot = status;
	}

	/** How the Discord bot is, or {@link DiscordBotStatus#NOT_INSTALLED} when nothing has said. */
	public static DiscordBotStatus discordBot() {
		Supplier<DiscordBotStatus> supplier = discordBot;
		if (supplier == null) {
			// A companion too old or too new to report this still puts lines in /staff status.
			Supplier<List<String>> lines = STATUS.get("Discord");
			if (lines == null) return DiscordBotStatus.NOT_INSTALLED;
			List<String> said = status().stream().filter(l -> l.startsWith("Discord: "))
					.map(l -> l.substring("Discord: ".length())).toList();
			return new DiscordBotStatus(DiscordBotStatus.Phase.FAILED,
					said.isEmpty() ? "installed, and not reporting" : said.get(0),
					said.size() > 1 ? said.subList(1, said.size()) : List.of(), List.of(), -1);
		}
		try {
			DiscordBotStatus status = supplier.get();
			return status != null ? status : new DiscordBotStatus(DiscordBotStatus.Phase.FAILED,
					"the bot did not say how it is", List.of(), List.of(), -1);
		} catch (RuntimeException e) {
			// The class and nothing else, as for status lines.
			return new DiscordBotStatus(DiscordBotStatus.Phase.FAILED,
					"status failed (" + e.getClass().getSimpleName() + ")", List.of(), List.of(), -1);
		}
	}

	/**
	 * {@code config/staffcore/}, where a companion keeps its files beside StaffCore's own. Made if it
	 * is not there; the server's {@code config/} itself if it cannot be made.
	 */
	public static java.nio.file.Path configFolder() {
		java.nio.file.Path root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
		java.nio.file.Path folder = root.resolve(io.github.alphain24.staffcore.config.ConfigFolder.NAME);
		try {
			java.nio.file.Files.createDirectories(folder);
			return folder;
		} catch (java.io.IOException | RuntimeException e) {
			return root;
		}
	}

	/**
	 * Adds lines to {@code /staff status} and the startup report under this owner's name.
	 * Asked for each time status is shown, on the server thread, so it must be quick and must not
	 * throw — though if it does, the status still prints.
	 */
	public static void addStatus(String owner, Supplier<List<String>> lines) {
		if (owner != null && lines != null) STATUS.put(owner, lines);
	}

	public static void removeStatus(String owner) {
		if (owner != null) STATUS.remove(owner);
	}

	/** Every companion's status lines, owner first. */
	public static List<String> status() {
		List<String> out = new ArrayList<>();
		STATUS.forEach((owner, supplier) -> {
			try {
				List<String> lines = supplier.get();
				if (lines == null || lines.isEmpty()) {
					out.add(owner + ": no status");
				} else {
					for (String line : lines) out.add(owner + ": " + line);
				}
			} catch (RuntimeException e) {
				// The class and nothing else. A companion's message could carry anything, a
				// secret included, and StaffCore's log is not the place to find out.
				out.add(owner + ": status failed (" + e.getClass().getSimpleName() + ")");
			}
		});
		return out;
	}

	/** The listeners, for the event thread. */
	static List<StaffCoreListener> listeners() {
		return LISTENERS;
	}

	/** Hands an event to the event thread. For StaffCore's own code. */
	public static void publish(StaffCoreEvent event) {
		if (event == null || LISTENERS.isEmpty()) return;
		EventBus.publish(event, LISTENERS);
	}
}
