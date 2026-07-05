package dev.stackverse.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;

final class LogSetup {
    private LogSetup() {
    }

    static void configure() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        if ("text".equalsIgnoreCase(System.getenv().getOrDefault("LOG_FORMAT", "json"))) {
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%logger] %msg%n");
            encoder.start();
            appender.setEncoder(encoder);
        } else {
            LogstashEncoder encoder = new LogstashEncoder();
            encoder.setContext(context);
            encoder.start();
            appender.setEncoder(encoder);
        }
        appender.start();

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(level(System.getenv().getOrDefault("LOG_LEVEL", "info")));
        root.addAppender(appender);
    }

    private static Level level(String raw) {
        return switch (raw.toLowerCase()) {
            case "error" -> Level.ERROR;
            case "warn" -> Level.WARN;
            case "debug" -> Level.DEBUG;
            default -> Level.INFO;
        };
    }
}
