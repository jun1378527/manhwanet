package com.manhwanet.collection.collector.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** 검사를 마친 IP 로 연결한다. host 는 TLS 서버 이름(SNI)·인증서 확인에 쓴다 */
public interface SocketConnector {

    Socket connect(String host, int port, InetAddress address, int connectTimeoutMillis) throws IOException;
}
