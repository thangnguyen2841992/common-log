package com.nihongo.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FileLoggingTest {
    @TempDir Path temporary;
    LoggerContext configure(boolean sql) throws Exception {
        return configure(sql, "4KB");
    }
    LoggerContext configure(boolean sql, String cap) throws Exception {
        return configure(sql, cap, 3600);
    }
    LoggerContext configure(boolean sql, String cap, int intervalSeconds) throws Exception {
        var context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        context.putProperty("LOG_ROOT", temporary.toString()); context.putProperty("SERVICE_NAME", "test-service");
        context.putProperty("SQL_LOG_LEVEL", sql ? "DEBUG" : "OFF");
        context.putProperty("LOG_MAX_FILE_SIZE", "1KB"); context.putProperty("LOG_TOTAL_SIZE_CAP", cap);
        context.putProperty("LOG_CLEANUP_INTERVAL_SECONDS", Integer.toString(intervalSeconds));
        var configurator = new JoranConfigurator(); configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream("<configuration><include resource=\"nihongo/logging/appenders.xml\"/></configuration>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(context.getStatusManager().getCopyOfStatusList().stream().noneMatch(s -> s.getLevel() == Status.ERROR),
                () -> context.getStatusManager().getCopyOfStatusList().toString());
        return context;
    }
    String file(String name) throws Exception { return Files.readString(temporary.resolve("test-service/" + name + ".log")); }

    @Test void routesFilesAndDisablesSqlAndParameterLoggingByDefault() throws Exception {
        var context = configure(false, "1MB");
        try {
            context.getLogger("nihongo.kafka").info("event=published eventId=event-1 password=private-value email=user@example.test");
            context.getLogger("nihongo.socket").info("event=connected socketId=connection-1");
            context.getLogger("org.hibernate.SQL").debug("select never_logged from user");
            context.getLogger("org.hibernate.orm.jdbc.bind").trace("sensitive_parameter");
            context.getLogger("app").error("failure", new IllegalStateException("refreshToken=private-refresh-value"));
        } finally { context.stop(); }
        assertTrue(file("kafka").contains("event-1")); assertFalse(file("kafka").contains("private-value"));
        assertFalse(file("kafka").contains("user@example.test"));
        assertTrue(file("socket").contains("connection-1")); assertFalse(file("kafka").contains("connection-1"));
        assertFalse(file("sql").contains("never_logged")); assertFalse(file("sql").contains("sensitive_parameter"));
        assertFalse(file("application").contains("private-refresh-value"));
    }
    @Test void sqlTraceRecordsQueriesWithPlaceholdersWithoutBindValues() throws Exception {
        var context = configure(true);
        try {
            context.getLogger("org.hibernate.SQL").debug("select user_id from user where email=?");
            context.getLogger("org.hibernate.orm.jdbc.bind").trace("user@example.test");
            context.getLogger("org.hibernate.SQL_SLOW").info("Slow query took 1200 milliseconds [select user_id from user where first_name='Private name' and phone_number=84123456789]");
        } finally { context.stop(); }
        assertTrue(file("sql").contains("where email=?")); assertFalse(file("sql").contains("user@example.test"));
        assertTrue(file("sql").contains("1200 milliseconds")); assertFalse(file("sql").contains("Private name")); assertFalse(file("sql").contains("84123456789"));
    }
    @Test void deletesExpiredArchivesAtStartupAndPreservesUnrelatedFiles() throws Exception {
        Path archives = temporary.resolve("test-service/archive"); Files.createDirectories(archives);
        Path expired = archives.resolve("kafka." + LocalDate.now(ZoneOffset.UTC).minusDays(20) + ".0.log.gz");
        Files.writeString(expired, "old log"); Path unrelated = archives.resolve("do-not-delete.txt"); Files.writeString(unrelated, "keep");
        var context = configure(false);
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (Files.exists(expired) && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse(Files.exists(expired)); assertTrue(Files.exists(unrelated));
        } finally { context.stop(); }
    }
    @Test void rollsAndCompressesFilesAndCapsArchiveSize() throws Exception {
        var context = configure(false, "1MB");
        try {
            var logger = context.getLogger("nihongo.kafka");
            for (int period = 0; period < 12; period++) {
                for (int line = 0; line < 12; line++) logger.info("event=test eventId={} data={}", UUID.randomUUID(), UUID.randomUUID());
                Thread.sleep(130);
            }
        } finally { context.stop(); }
        Path archives = temporary.resolve("test-service/archive");
        try (var files = Files.list(archives)) {
            assertTrue(files.anyMatch(p -> p.toString().endsWith(".gz")), "Size rollover must create a compressed archive");
        }
        Path largeArchive = archives.resolve("kafka." + LocalDate.now(ZoneOffset.UTC).minusDays(1) + ".99.log.gz");
        byte[] random = new byte[8192]; new java.security.SecureRandom().nextBytes(random);
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(largeArchive))) { gzip.write(random); }
        // Compression/queue draining completes during stop. A fresh policy sees every final archive.
        var maintenance = configure(false);
        try {
        var async = (ch.qos.logback.classic.AsyncAppender) maintenance.getLogger("nihongo.kafka").getAppender("KAFKA");
        var appender = (ch.qos.logback.core.rolling.RollingFileAppender<?>) async.getAppender("KAFKA_FILE");
        ((RetainingRollingPolicy<?>) appender.getRollingPolicy()).cleanArchivesNow().get(5, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long size;
        do {
            try (var files = Files.list(archives)) {
                var rolled = files.filter(p -> p.getFileName().toString().startsWith("kafka.") && p.toString().endsWith(".gz")).toList();
                size = 0; for (var path : rolled) { try { size += Files.size(path); } catch (NoSuchFileException concurrentlyRemoved) { } }
            }
            if (size > 4096) Thread.sleep(20);
        } while (size > 4096 && System.nanoTime() < deadline);
        assertTrue(size <= 4096, "Archive cleanup must respect totalSizeCap");
        assertFalse(Files.exists(largeArchive), "Size cap must delete an oversized archive inside the retention period");
        } finally { maintenance.stop(); }
    }
    @Test void redactsTokensCookiesSecretsAndPreventsForgedLogLines() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJvd25lciJ9.c2lnbmF0dXJlLWlzLXByaXZhdGU";
        String input = "Authorization: Bearer " + jwt + "\nCookie: accessToken=" + jwt;
        String safe = LogRedactor.redact(input);
        assertFalse(safe.contains(jwt));
        assertFalse(LogRedactor.redact("{\"password\":\"two word secret\",\"activeCode\":\"activation\"}").contains("two word secret"));
        assertFalse(LogRedactor.redact("X-Nihongo-Internal=private-internal-key").contains("private-internal-key"));
        assertFalse(LogRedactor.redact("recoveryToken=private-recovery-token").contains("private-recovery-token"));
        assertFalse(LogRedactor.redact("secret=PRIVATEAUTHENTICATORKEY").contains("PRIVATEAUTHENTICATORKEY"));
        assertEquals("org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invokeProvider", LogRedactor.redact("org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invokeProvider"));
        assertEquals("select user_id from user where password=?", LogRedactor.redact("select user_id from user where password=?"));
        var event = new ch.qos.logback.classic.spi.LoggingEvent(); event.setMessage("request\nFORGED ENTRY");
        assertEquals("request\\nFORGED ENTRY", new SafeMessageConverter().convert(event));
    }
    @Test void cleansArchivesPeriodicallyEvenWithoutNewLogEvents() throws Exception {
        var context = configure(false, "4KB", 1);
        try {
            var async = (ch.qos.logback.classic.AsyncAppender) context.getLogger("nihongo.kafka").getAppender("KAFKA");
            var appender = (ch.qos.logback.core.rolling.RollingFileAppender<?>) async.getAppender("KAFKA_FILE");
            ((RetainingRollingPolicy<?>) appender.getRollingPolicy()).cleanArchivesNow().get(5, java.util.concurrent.TimeUnit.SECONDS);
            Path archive = temporary.resolve("test-service/archive/kafka." + LocalDate.now(ZoneOffset.UTC).minusDays(1) + ".0.log.gz");
            Files.createDirectories(archive.getParent());
            byte[] data = new byte[8192]; new java.security.SecureRandom().nextBytes(data);
            try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(archive))) { gzip.write(data); }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (Files.exists(archive) && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse(Files.exists(archive), "Scheduled cleanup must run without additional log writes");
        } finally { context.stop(); }
    }
}
