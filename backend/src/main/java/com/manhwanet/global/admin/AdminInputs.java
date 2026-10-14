package com.manhwanet.global.admin;

import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.review.dto.JsonInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** 관리자 입력 검증 도우미. 오류는 모아서 한 번에 400 으로 돌려준다 */
public final class AdminInputs {

    /** 확인 시각이 서버 시각보다 이만큼까지 앞서는 것은 허용한다(시계 차이) */
    public static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    public static final int REASON_MAX_LENGTH = 500;

    private final List<ApiError.FieldError> errors;

    public AdminInputs() {
        this(new java.util.ArrayList<>());
    }

    public AdminInputs(List<ApiError.FieldError> errors) {
        this.errors = errors;
    }

    public void add(String field, String reason) {
        errors.add(new ApiError.FieldError(field, reason));
    }

    public boolean hasError(String field) {
        return errors.stream().anyMatch(e -> e.field().equals(field));
    }

    public int errorCount() {
        return errors.size();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
    }

    public Long version(JsonInteger field) {
        return integer(field, "version", 0, true);
    }

    public Long integer(JsonInteger field, String name, long min, boolean required) {
        if (field == null) {
            if (required) {
                add(name, name + " 은 필수입니다.");
            }
            return null;
        }
        if (!field.isInteger() || field.value() < min) {
            add(name, name + " 은 " + min + " 이상의 정수여야 합니다.");
            return null;
        }
        return field.value();
    }

    public <E extends Enum<E>> E enumValue(String raw, Class<E> type, String field, boolean required) {
        if (raw == null) {
            if (required) {
                add(field, field + " 은 필수입니다.");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            add(field, field + " 은 " + Arrays.stream(type.getEnumConstants()).map(Enum::name)
                    .collect(Collectors.joining(", ")) + " 중 하나여야 합니다.");
            return null;
        }
    }

    /** 앞뒤 공백 제거. 비었으면 null(필수면 오류), 최대 길이 초과면 오류 */
    public String text(String raw, String field, int maxLength, boolean required) {
        String value = raw == null ? null : raw.strip();
        if (value == null || value.isEmpty()) {
            if (required) {
                add(field, field + " 은 필수입니다.");
            }
            return null;
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            add(field, field + " 은 " + maxLength + "자 이하여야 합니다.");
            return null;
        }
        return value;
    }

    /** 관리자 변경 사유(앞뒤 공백 제거, 1~500자). 수정·상태 변경에는 필수, 등록에는 선택 (#40) */
    public String reason(String raw, boolean required) {
        return text(raw, "reason", REASON_MAX_LENGTH, required);
    }

    public Instant verifiedAt(Instant value, String field, boolean required, Clock clock) {
        if (value == null) {
            if (required) {
                add(field, field + " 은 필수입니다.");
            }
            return null;
        }
        if (value.isAfter(clock.instant().plus(FUTURE_SKEW))) {
            add(field, field + " 은 미래 시각일 수 없습니다.");
            return null;
        }
        return value;
    }
}
