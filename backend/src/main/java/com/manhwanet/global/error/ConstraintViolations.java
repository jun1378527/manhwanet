package com.manhwanet.global.error;

import java.util.Collection;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DataIntegrityViolationException 에서 위반된 DB 제약 이름을 꺼낸다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static Optional<String> constraintName(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return Optional.ofNullable(violation.getConstraintName());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    public static boolean isOneOf(DataIntegrityViolationException exception, Collection<String> constraintNames) {
        return constraintName(exception)
                .map(name -> constraintNames.stream().anyMatch(name::equalsIgnoreCase))
                .orElse(false);
    }
}
