package com.manhwanet.collection.collector.net;

import java.util.Objects;

/**
 * 수집기 네트워크 구성. 운영 기본값({@link #production()})은 DNS 해석 → 공개 주소 검사 → TLS(인증서·호스트 이름 확인) 연결이다.
 * 로컬 주소 허용·평문 연결은 운영 코드에 설정 값으로 두지 않았다. 테스트는 테스트 소스에서만 이 구성을 바꾼다.
 */
public record CollectorNetwork(HostResolver resolver, AddressPolicy addressPolicy, SocketConnector connector) {

    public CollectorNetwork {
        Objects.requireNonNull(resolver);
        Objects.requireNonNull(addressPolicy);
        Objects.requireNonNull(connector);
    }

    public static CollectorNetwork production() {
        return new CollectorNetwork(new DnsHostResolver(), new PublicAddressPolicy(), TlsSocketConnector.system());
    }
}
