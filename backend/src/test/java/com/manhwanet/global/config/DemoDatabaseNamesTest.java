package com.manhwanet.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** #67 데모 DB 이름 규칙: '_demo' 로 끝나고 개발·테스트 DB 와 달라야 하며, 실제 연결 DB 도 같은지 확인한다 */
class DemoDatabaseNamesTest {

    @Test
    void defaultDemoDatabaseNameIsAccepted() {
        assertThat(DemoDatabaseNames.configuredDemoDatabaseName(new MockEnvironment())).isEqualTo("manhwanet_demo");
        assertThat(DemoDatabaseNames.requireDemoDatabaseName(" portfolio_demo ", "manhwanet", "manhwanet_test")).isEqualTo("portfolio_demo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "manhwanet", "manhwanet_test", "Manhwanet_demo", "manhwanet-demo", "demo_manhwanet",
            "manhwanet_demo;drop", "manhwanet_demo2", "\"x\"_demo"})
    void rejectsNamesThatAreNotDemoDatabases(String name) {
        assertThatThrownBy(() -> DemoDatabaseNames.requireDemoDatabaseName(name, "manhwanet", "manhwanet_test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNullAndTooLongNames() {
        assertThatThrownBy(() -> DemoDatabaseNames.requireDemoDatabaseName(null, "manhwanet", "manhwanet_test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatabaseNames.requireDemoDatabaseName("a".repeat(59) + "_demo", "manhwanet", "manhwanet_test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDemoNameEqualToDevOrTestDatabase() {
        MockEnvironment devClash = new MockEnvironment().withProperty("DEMO_DB_NAME", "shared_demo").withProperty("DB_NAME", "shared_demo");
        assertThatThrownBy(() -> DemoDatabaseNames.configuredDemoDatabaseName(devClash)).isInstanceOf(IllegalStateException.class);
        MockEnvironment testClash = new MockEnvironment().withProperty("TEST_DB_NAME", "manhwanet_demo");
        assertThatThrownBy(() -> DemoDatabaseNames.configuredDemoDatabaseName(testClash)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectedDatabaseMustBeTheConfiguredDemoDatabase() {
        DemoDatabaseNames.requireConnected("manhwanet_demo", "manhwanet_demo");
        assertThatThrownBy(() -> DemoDatabaseNames.requireConnected("manhwanet", "manhwanet_demo")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatabaseNames.requireConnected("manhwanet_test", "manhwanet_demo")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDatabaseNames.requireConnected(null, "manhwanet_demo")).isInstanceOf(IllegalStateException.class);
    }
}
