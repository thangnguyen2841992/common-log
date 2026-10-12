package com.nihongo.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SafeThrowableConverter extends ThrowableProxyConverter {
    @Override public String convert(ILoggingEvent event) { return LogRedactor.redact(super.convert(event)); }
}
