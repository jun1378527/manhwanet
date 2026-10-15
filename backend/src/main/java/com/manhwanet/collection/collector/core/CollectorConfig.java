package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.collector.net.CollectorNetwork;
import com.manhwanet.collection.collector.net.HttpTransport;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 수집기 구성 요소. 이 빈들은 요청을 스스로 보내지 않는다(스케줄·시작 시 실행 없음).
 * 수집은 collector 프로필의 {@code CollectCommand} 가 명시적으로 부를 때만 시작한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfig {

    @Bean
    CollectorNetwork collectorNetwork() {
        return CollectorNetwork.production();
    }

    @Bean
    CollectorClock collectorClock() {
        return CollectorClock.system();
    }

    @Bean
    RandomGenerator collectorRandom() {
        return new java.security.SecureRandom();
    }

    @Bean
    HttpTransport collectorHttpTransport(CollectorNetwork collectorNetwork) {
        return new HttpTransport(collectorNetwork);
    }

    @Bean
    PlatformThrottle platformThrottle(CollectorClock collectorClock) {
        return new PlatformThrottle(collectorClock);
    }
}
