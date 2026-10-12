package com.nihongo.logging;

import java.util.regex.Pattern;

/** Defense in depth; operational events must still omit request/message bodies. */
public final class LogRedactor {
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{7,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(Bearer\\s+)[^\\s,;\\]\\\"']+");
    private static final Pattern SECRET = Pattern.compile("(?i)([\\\"']?(?:password|passwd|client[_-]?secret|access[_-]?token|refresh[_-]?token|active[_-]?code|setup[_-]?token|recovery[_-]?token|mfa[_-]?challenge|secret|token|session[_-]?encryption[_-]?key|x-nihongo-internal)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;\\]\\}]+)");
    private static final Pattern COOKIE = Pattern.compile("(?i)((?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern SQL_STRING = Pattern.compile("(?s)'(?:''|\\\\.|[^'\\\\])*'|\"(?:\"\"|\\\\.|[^\"\\\\])*\"");
    private static final Pattern SQL_NUMBER = Pattern.compile("(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])");
    private LogRedactor() {}
    public static String redact(String value) {
        if (value == null) return "";
        String safe = JWT.matcher(value).replaceAll("[REDACTED_JWT]");
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1[REDACTED]");
        safe = SECRET.matcher(safe).replaceAll(match -> match.group().substring(match.group(1).length()).equals("?")
                ? match.group() : match.group(1) + "[REDACTED]");
        safe = COOKIE.matcher(safe).replaceAll("$1[REDACTED]");
        return EMAIL.matcher(safe).replaceAll("[REDACTED_EMAIL]");
    }
    public static String redactSlowSql(String value) {
        // Some JDBC Statement.toString() implementations expand bind values in Hibernate's slow-query message.
        int start = value.indexOf('[');
        String prefix = start < 0 ? "" : value.substring(0, start);
        String sql = start < 0 ? value : value.substring(start);
        sql = SQL_STRING.matcher(sql).replaceAll("?");
        return prefix + SQL_NUMBER.matcher(sql).replaceAll("?");
    }
}
