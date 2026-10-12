package com.nihongo.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SafeMessageConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if ("org.hibernate.SQL_SLOW".equals(event.getLoggerName())) message = LogRedactor.redactSlowSql(message);
        return LogRedactor.redact(message).replace("\r", "\\r").replace("\n", "\\n");
    }
}
