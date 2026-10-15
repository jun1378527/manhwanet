package com.manhwanet.collection.collector;

import com.manhwanet.collection.collector.core.CollectorClock;
import com.manhwanet.collection.collector.net.CollectorNetwork;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 수집기 통합 테스트 구성: 루프백 전용 네트워크, 가상 시계, 고정 난수, 테스트 어댑터 */
@TestConfiguration(proxyBeanMethods = false)
public class CollectorTestConfig {

    @Bean
    LoopbackNetwork loopbackNetwork() {
        return new LoopbackNetwork();
    }

    @Bean
    @Primary
    CollectorNetwork testCollectorNetwork(LoopbackNetwork loopbackNetwork) {
        return loopbackNetwork.network();
    }

    @Bean
    VirtualClock virtualClock() {
        return new VirtualClock();
    }

    @Bean
    @Primary
    CollectorClock testCollectorClock(VirtualClock virtualClock) {
        return virtualClock;
    }

    @Bean
    @Primary
    RandomGenerator testCollectorRandom() {
        return new SplittableRandom(46);
    }

    @Bean
    FakeAdapter fakeAdapter() {
        return new FakeAdapter();
    }
}
