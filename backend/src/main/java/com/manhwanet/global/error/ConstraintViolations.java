package com.manhwanet.global.error;

import java.util.Collection;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DataIntegrityViolationException 에서 위반된 DB 제약 이름을 꺼낸다.
 * JPA 는 Hibernate 가 이름을 담고, JDBC 는 PostgreSQL 오류의 제약 필드를 읽는다(#57).
 * 드라이버는 실행 시에만 있으므로 그 필드는 리플렉션으로 확인하고, 메시지 문자열로 짐작하지 않는다.
 */
public final class ConstraintViolations {

    private static final String POSTGRES_EXCEPTION = "org.postgresql.util.PSQLException";

    private ConstraintViolations() {
    }

    public static Optional<String> constraintName(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return Optional.of(violation.getConstraintName());
            }
            Optional<String> postgres = postgresConstraint(current);
            if (postgres.isPresent()) {
                return postgres;
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    /** PostgreSQL JDBC 가 서버 오류에 실은 제약 이름. 그 필드가 없으면 비어 있다 */
    private static Optional<String> postgresConstraint(Throwable exception) {
        if (!POSTGRES_EXCEPTION.equals(exception.getClass().getName())) {
            return Optional.empty();
        }
        try {
            Object serverError = exception.getClass().getMethod("getServerErrorMessage").invoke(exception);
            if (serverError == null) {
                return Optional.empty();
            }
            Object constraint = serverError.getClass().getMethod("getConstraint").invoke(serverError);
            if (constraint instanceof String name && !name.isBlank()) {
                return Optional.of(name);
            }
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    public static boolean isOneOf(DataIntegrityViolationException exception, Collection<String> constraintNames) {
        return constraintName(exception)
                .map(name -> constraintNames.stream().anyMatch(name::equalsIgnoreCase))
                .orElse(false);
    }
}
