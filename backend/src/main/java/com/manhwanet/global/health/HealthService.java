package com.manhwanet.global.health;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 애플리케이션과 데이터베이스 연결 상태를 확인한다.
     *
     * @throws BusinessException 데이터베이스에 연결할 수 없는 경우 (SERVICE_UNAVAILABLE)
     */
    public HealthResponse check() {
        if (!isDatabaseAvailable()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return HealthResponse.up();
    }

    private boolean isDatabaseAvailable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
