package com.manhwanet.collection.collector.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 운영 기본 해석기: JDK DNS 해석 */
public final class DnsHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
