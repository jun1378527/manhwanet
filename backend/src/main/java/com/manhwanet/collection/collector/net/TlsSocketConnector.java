package com.manhwanet.collection.collector.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 운영 연결: 검사한 IP 로 TCP 연결 → 원래 호스트 이름으로 SNI·인증서 호스트 이름 확인(HTTPS 규칙)을 하는 TLS.
 * IP 로 직접 연결하므로 DNS 를 두 번 해석하는 사이에 목적지가 바뀌는 문제(DNS rebinding)가 없다.
 */
public final class TlsSocketConnector implements SocketConnector {

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private final SSLSocketFactory factory;

    public TlsSocketConnector(SSLContext context) {
        this.factory = context.getSocketFactory();
    }

    public static TlsSocketConnector system() {
        try {
            return new TlsSocketConnector(SSLContext.getDefault());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Socket connect(String host, int port, InetAddress address, int connectTimeoutMillis) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
            raw.setSoTimeout(connectTimeoutMillis);
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setProtocols(PROTOCOLS);
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }
}
