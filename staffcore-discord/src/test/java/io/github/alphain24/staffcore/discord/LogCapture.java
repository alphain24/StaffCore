package io.github.alphain24.staffcore.discord;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every log line written while it is open, as the text a log file would hold: the message and the
 * full stack trace of anything thrown.
 * <p>
 * Attached to the root logger at every level, so a test asserting "the token is in no log line"
 * is reading what would really have been written rather than what one logger chose to print.
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {

	private final List<String> lines = new CopyOnWriteArrayList<>();
	private final Level previous;

	private LogCapture() {
		super("capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		Configuration configuration = context.getConfiguration();
		LoggerConfig root = configuration.getRootLogger();
		previous = root.getLevel();
		start();
		root.addAppender(this, Level.ALL, null);
		root.setLevel(Level.ALL);
		context.updateLoggers();
	}

	static LogCapture open() {
		return new LogCapture();
	}

	@Override
	public void append(LogEvent event) {
		StringBuilder text = new StringBuilder(event.getLoggerName()).append(' ')
				.append(event.getMessage().getFormattedMessage());
		if (event.getThrown() != null) {
			StringWriter trace = new StringWriter();
			event.getThrown().printStackTrace(new PrintWriter(trace));
			text.append('\n').append(trace);
		}
		lines.add(text.toString());
	}

	List<String> lines() {
		return lines;
	}

	@Override
	public void close() {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		LoggerConfig root = context.getConfiguration().getRootLogger();
		root.removeAppender(getName());
		root.setLevel(previous);
		context.updateLoggers();
		stop();
	}
}
