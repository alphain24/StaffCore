package io.github.alphain24.staffcore.discord.security;

import io.github.alphain24.staffcore.discord.config.BotToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The last line: no log line containing the bot token reaches the log, whoever wrote it.
 * <p>
 * The companion's own code never logs the token, and redacts exception text before logging it.
 * That covers the code in this project. It does not cover JDA, OkHttp, the WebSocket client, or
 * whatever else ends up on the classpath of a server with forty mods — any of which could log a
 * request, a header or a URL on a bad day. So the server's logging configuration gets a filter
 * that drops any event whose message or exception text contains the token, and counts it; the
 * count is shown in {@code /staff status}, so a withheld line is noticed without being printed.
 * <p>
 * Dropped rather than rewritten. Log4j filters cannot change a message, and a line with a secret
 * cut out of it is still a line somebody might paste somewhere believing it to be clean.
 */
public final class TokenShield extends AbstractFilter {

	private final BotToken token;
	private final AtomicLong withheld = new AtomicLong();

	TokenShield(BotToken token) {
		super(Filter.Result.NEUTRAL, Filter.Result.DENY);
		this.token = token;
	}

	/**
	 * Installs a shield for this token on the server's logging configuration.
	 *
	 * @return the shield, or null when the logging backend is not Log4j and nothing could be
	 *         installed — which the caller reports
	 */
	public static TokenShield install(BotToken token) {
		try {
			if (!(LogManager.getContext(false) instanceof LoggerContext context)) return null;
			TokenShield shield = new TokenShield(token);
			shield.start();
			// On every logger's configuration rather than once for the whole context. A
			// context-wide filter is asked before a message is formatted, with the pattern and its
			// arguments apart, so a token passed as an argument would get past it; a logger's own
			// filter sees the finished event.
			Configuration configuration = context.getConfiguration();
			configuration.getRootLogger().addFilter(shield);
			for (LoggerConfig logger : configuration.getLoggers().values()) {
				if (logger != configuration.getRootLogger()) logger.addFilter(shield);
			}
			context.updateLoggers();
			return shield;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Takes the shield off again, when the bot stops. */
	public void remove() {
		try {
			if (LogManager.getContext(false) instanceof LoggerContext context) {
				Configuration configuration = context.getConfiguration();
				configuration.getRootLogger().removeFilter(this);
				for (LoggerConfig logger : configuration.getLoggers().values()) logger.removeFilter(this);
				context.updateLoggers();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Nothing to take off.
		}
		stop();
	}

	/** Log lines dropped because they contained the token, since the bot started. */
	public long withheld() {
		return withheld.get();
	}

	@Override
	public Result filter(LogEvent event) {
		return contains(event) ? deny() : Result.NEUTRAL;
	}

	boolean contains(LogEvent event) {
		if (event == null) return false;
		if (event.getMessage() != null && token.appearsIn(event.getMessage().getFormattedMessage())) {
			return true;
		}
		for (Throwable t = event.getThrown(); t != null; t = t.getCause()) {
			if (token.appearsIn(t.getMessage()) || token.appearsIn(t.toString())) return true;
			for (Throwable suppressed : t.getSuppressed()) {
				if (token.appearsIn(suppressed.getMessage())) return true;
			}
			if (t.getCause() == t) break;
		}
		return false;
	}

	private Result deny() {
		withheld.incrementAndGet();
		return Result.DENY;
	}
}
