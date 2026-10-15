package com.manhwanet.collection.collector;

import com.manhwanet.collection.collector.net.AddressPolicy;
import com.manhwanet.collection.collector.net.CollectorNetwork;
import com.manhwanet.collection.collector.net.HostResolver;
import com.manhwanet.collection.collector.net.SocketConnector;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 전용 네트워크(운영 코드에는 없음). *.mock.test 이름만 지정한 주소로 해석하고 나머지는 해석하지 않는다(실제 DNS·사이트 호출 없음).
 * 127.0.0.1 만 허용하고, 평문 소켓으로 모의 서버 포트에 연결한다.
 */
public final class LoopbackNetwork {

    private final Map<String, InetAddress> hosts = new ConcurrentHashMap<>();
    private volatile int port;
    private final AtomicInteger connects = new AtomicInteger();
    private final java.util.List<String> connectedAddresses = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CollectorNetwork network() {
        HostResolver resolver = host -> {
            InetAddress address = hosts.get(host);
            if (address == null) {
                throw new UnknownHostException("test resolver: " + host);
            }
            return List.of(address);
        };
        AddressPolicy policy = address -> address.getHostAddress().equals("127.0.0.1");
        SocketConnector connector = (host, ignoredPort, address, timeout) -> {
            connects.incrementAndGet();
            connectedAddresses.add(address.getHostAddress());
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), timeout);
            return socket;
        };
        return new CollectorNetwork(resolver, policy, connector);
    }

    public void reset(int port) {
        this.port = port;
        hosts.clear();
        connects.set(0);
        connectedAddresses.clear();
    }

    public void map(String host, String ip) {
        try {
            hosts.put(host, InetAddress.getByName(ip));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public java.util.List<String> connectedAddresses() {
        return java.util.List.copyOf(connectedAddresses);
    }

    public int connects() {
        return connects.get();
    }
}
