package com.manhwanet.global.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final HealthService healthService = new HealthService(dataSource);

    @Test
    void returnsUpWhenConnectionIsValid() throws SQLException {
        Connection connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(true);

        assertThat(healthService.check()).isEqualTo(HealthResponse.up());
    }

    @Test
    void throwsServiceUnavailableWhenConnectionIsInvalid() throws SQLException {
        Connection connection = mock(Connection.class);
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(false);

        assertThatThrownBy(healthService::check)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void throwsServiceUnavailableWhenConnectionFails() throws SQLException {
        given(dataSource.getConnection()).willThrow(new SQLException("connection refused"));

        assertThatThrownBy(healthService::check)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
