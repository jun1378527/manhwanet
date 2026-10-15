package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.collection.collector.net.PublicAddressPolicy;
import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 목적지 IP 검사(운영 기본값): localhost·사설망·링크 로컬·메타데이터·문서용 주소를 막는다 */
class PublicAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10",
            "169.254.169.254", "100.64.0.1", "100.100.100.200", "192.0.0.1", "192.0.2.5", "198.18.0.1", "198.51.100.1",
            "203.0.113.9", "224.0.0.1", "239.1.1.1", "240.0.0.1", "255.255.255.255", "192.88.99.1",
            "::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::127.0.0.1", "fc00::1",
            "fd00:ec2::254", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1", "2001::1", "2001:10::1", "64:ff9b::a9fe:a9fe",
            "64:ff9b:1::1", "2002:0a00:0001::1", "100::1"})
    void blocksNonPublicAddresses(String ip) throws Exception {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "211.249.220.24", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111",
            "2404:6800:4004:80f::200e", "64:ff9b::808:808", "2002:0808:0808::1", "::ffff:8.8.8.8"})
    void allowsPublicAddresses(String ip) throws Exception {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isTrue();
    }
}
