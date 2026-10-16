package com.manhwanet.demo;

import com.manhwanet.collection.collector.net.CollectorNetwork;
import com.manhwanet.collection.collector.net.HostResolver;
import com.manhwanet.collection.collector.net.SocketConnector;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * demo-seed 프로필 전용 수집기 구성(#67). 이 클래스는 데모 소스 세트(src/demo)에만 있어 bootJar·웹 서버에 들어가지 않는다.
 * 운영 수집기의 "공개 주소만 연결" 규칙은 그대로이고, 데모 명령에서만 데모 호스트 하나를 127.0.0.1 모의 서버로 보낸다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo-seed")
public class DemoCollectorConfig {

    /** 데모 모의 서버 포트(수집 단계에서 정함) */
    static final class DemoPort {
        private volatile int port;

        void set(int port) {
            this.port = port;
        }

        int get() {
            if (port <= 0) {
                throw new IllegalStateException("demo mock site is not running");
            }
            return port;
        }
    }

    @Bean
    DemoPort demoPort() {
        return new DemoPort();
    }

    @Bean
    @Primary
    CollectorNetwork demoCollectorNetwork(DemoPort port) {
        HostResolver resolver = host -> {
            if (!DemoAdapter.HOST.equals(host)) {
                throw new UnknownHostException("demo resolver only knows " + DemoAdapter.HOST);
            }
            return List.of(InetAddress.getLoopbackAddress());
        };
        SocketConnector connector = (host, ignoredPort, address, timeout) -> {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port.get()), timeout);
            return socket;
        };
        return new CollectorNetwork(resolver, address -> address.isLoopbackAddress(), connector);
    }

    @Bean
    DemoAdapter demoAdapter() {
        return new DemoAdapter();
    }
}
