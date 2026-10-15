package com.manhwanet.collection.collector.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;

/** Retry-After 해석: 초(정수) 또는 HTTP 날짜. 음수·형식 오류는 빈 값, 과거 날짜는 0, 상한 86,400초 */
public final class RetryAfter {

    public static final long MAX_SECONDS = 86_400;

    private RetryAfter() {
    }

    public static OptionalLong seconds(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return OptionalLong.empty();
        }
        String value = header.strip();
        if (value.chars().allMatch(Character::isDigit)) {
            if (value.length() > 9) {
                return OptionalLong.of(MAX_SECONDS);
            }
            return OptionalLong.of(Math.min(MAX_SECONDS, Long.parseLong(value)));
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long secs = Duration.between(now, at).toSeconds();
            return OptionalLong.of(Math.max(0, Math.min(MAX_SECONDS, secs)));
        } catch (DateTimeParseException e) {
            return OptionalLong.empty();
        }
    }
}
