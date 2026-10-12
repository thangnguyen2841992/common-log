package com.nihongo.logging;

import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import java.time.Instant;
import java.util.concurrent.*;

/** Retention also runs while a service is quiet, without waiting for its next log event. */
public final class RetainingRollingPolicy<E> extends SizeAndTimeBasedRollingPolicy<E> {
    private static final ScheduledThreadPoolExecutor CLEANER = cleaner();
    private long cleanupIntervalSeconds = 3600;
    private ScheduledFuture<?> cleanup;
    private static ScheduledThreadPoolExecutor cleaner() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "log-retention"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    public void setCleanupIntervalSeconds(long seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Log cleanup interval must be positive");
        cleanupIntervalSeconds = seconds;
    }
    @Override public void start() {
        super.start();
        if (isStarted()) cleanup = CLEANER.scheduleWithFixedDelay(this::cleanArchivesNow,
                cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
    }
    public Future<?> cleanArchivesNow() {
        if (!isStarted()) return CompletableFuture.completedFuture(null);
        return getTimeBasedFileNamingAndTriggeringPolicy().getArchiveRemover().cleanAsynchronously(Instant.now());
    }
    @Override public void stop() {
        if (cleanup != null) cleanup.cancel(false);
        super.stop();
    }
}
