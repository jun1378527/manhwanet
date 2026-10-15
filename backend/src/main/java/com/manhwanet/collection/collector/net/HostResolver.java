package com.manhwanet.collection.collector.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 호스트 이름 → IP. 수집기는 이 결과를 검사한 뒤 그 IP 로만 연결한다(연결 단계에서 다시 해석하지 않음) */
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
