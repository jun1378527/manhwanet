package com.manhwanet.collection.collector.net;

import java.net.InetAddress;

/** 연결해도 되는 목적지 IP 인지 판단한다. 운영 기본값은 {@link PublicAddressPolicy} 하나뿐이다 */
public interface AddressPolicy {

    boolean permits(InetAddress address);
}
