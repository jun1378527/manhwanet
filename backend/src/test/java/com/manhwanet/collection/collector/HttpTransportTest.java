package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.collection.collector.net.AddressPolicy;
import com.manhwanet.collection.collector.net.CollectorNetwork;
import com.manhwanet.collection.collector.net.FetchException;
import com.manhwanet.collection.collector.net.HttpTransport;
import com.manhwanet.collection.collector.net.HttpTransport.RawResponse;
import com.manhwanet.collection.collector.net.PublicAddressPolicy;
import com.manhwanet.collection.collector.net.TlsSocketConnector;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 전송 계층: 고정 헤더, 쿠키 미저장, 크기·시간 제한, 목적지 IP 검사, TLS 호스트 이름 확인 (127.0.0.1 모의 서버) */
class HttpTransportTest {

    private static final String UA = "ManhwanetCollector/0.1.0 (+https://manhwanet.test/collector)";
    private static final HttpTransport.Limits LIMITS = new HttpTransport.Limits(1_000, 1_000, 1_500, 65_536);

    private MockSite site;
    private LoopbackNetwork net;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        site = new MockSite(() -> 0L);
        net = new LoopbackNetwork();
        net.reset(site.port());
        net.map("ridi.mock.test", "127.0.0.1");
        transport = new HttpTransport(net.network());
    }

    @AfterEach
    void tearDown() {
        site.close();
    }

    @Test
    void sendsOnlyFixedHeadersAndDropsSetCookie() throws Exception {
        site.on("/a?x=1", MockSite.status(200, "ok", "Set-Cookie", "sid=SECRET; HttpOnly", "X-Test", "1"));
        RawResponse response = transport.get(URI.create("https://ridi.mock.test/a?x=1"), UA, LIMITS);
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
        assertThat(response.headers()).doesNotContainKey("set-cookie").containsKey("x-test");
        MockSite.Request request = site.requests().get(0);
        assertThat(request.headers().keySet()).containsExactlyInAnyOrder("Host", "User-agent", "Accept", "Accept-encoding",
                "Connection");
        assertThat(request.headers().get("Host")).containsExactly("ridi.mock.test");
        assertThat(request.headers().get("User-agent")).containsExactly(UA);
    }

    @Test
    void redirectsAreReturnedNotFollowed() throws Exception {
        site.on("/r", MockSite.redirect(302, "/elsewhere"));
        RawResponse response = transport.get(URI.create("https://ridi.mock.test/r"), UA, LIMITS);
        assertThat(response.isRedirect()).isTrue();
        assertThat(response.header("location")).isEqualTo("/elsewhere");
        assertThat(site.targets()).containsExactly("/r");
    }

    @Test
    void bodyLimitsApplyToContentLengthChunkedAndGzip() throws Exception {
        site.ok("/big", "x".repeat(70_000));
        site.on("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(new byte[8_192]);
                }
            }
        });
        byte[] bomb = gzip(new byte[5_000_000]);
        site.on("/bomb", ex -> {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, bomb.length);
            ex.getResponseBody().write(bomb);
        });
        byte[] small = gzip("압축 본문".getBytes(StandardCharsets.UTF_8));
        site.on("/gz", ex -> {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, small.length);
            ex.getResponseBody().write(small);
        });
        for (String path : List.of("/big", "/chunked", "/bomb")) {
            assertThatThrownBy(() -> transport.get(URI.create("https://ridi.mock.test" + path), UA, LIMITS))
                    .as(path).isInstanceOfSatisfying(FetchException.class,
                            e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TOO_LARGE));
        }
        assertThat(new String(transport.get(URI.create("https://ridi.mock.test/gz"), UA, LIMITS).body(), StandardCharsets.UTF_8))
                .isEqualTo("압축 본문");
    }

    @Test
    void slowDripHitsTotalTimeout() {
        site.on("/slow", ex -> {
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            for (int i = 0; i < 50; i++) {
                out.write('x');
                out.flush();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            out.close();
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> transport.get(URI.create("https://ridi.mock.test/slow"), UA, LIMITS))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TIMEOUT));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(4_000);
    }

    @Test
    void productionNetworkRefusesLocalAndPrivateDestinationsBeforeConnecting() {
        HttpTransport production = new HttpTransport(CollectorNetwork.production());
        assertBlocked(production, "https://localhost/");
        assertBlocked(production, "https://127.0.0.1/");
        assertBlocked(production, "https://169.254.169.254/latest/meta-data");
        assertBlocked(production, "https://[::1]/");
        assertBlocked(production, "http://example.com/");
        assertBlocked(production, "https://user:pw@example.com/");
        // 공개 주소와 사설 주소가 섞여 해석되면 연결하지 않는다
        AtomicInteger connects = new AtomicInteger();
        CollectorNetwork mixed = new CollectorNetwork(host -> List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")),
                new PublicAddressPolicy(), (host, port, address, timeout) -> {
                    connects.incrementAndGet();
                    throw new java.io.IOException("must not connect");
                });
        assertBlocked(new HttpTransport(mixed), "https://rebind.example/");
        assertThat(connects).hasValue(0);
    }

    @Test
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transport.get(URI.create("https://ridi.mock.test/a"), UA, LIMITS))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(site.requests()).isEmpty();
    }

    @Test
    void tlsVerifiesCertificateHostName(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("ks.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=ridi.mock.test", "-ext", "SAN=dns:ridi.mock.test", "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("t", ks.getCertificate("t"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", ex -> MockSite.send(ex, 200, "tls ok"));
        server.start();
        try {
            int port = server.getAddress().getPort();
            AddressPolicy loopback = a -> a.isLoopbackAddress();
            TlsSocketConnector tls = new TlsSocketConnector(clientContext);
            CollectorNetwork network = new CollectorNetwork(host -> List.of(InetAddress.getLoopbackAddress()), loopback,
                    (host, ignored, address, timeout) -> tls.connect(host, port, address, timeout));
            HttpTransport secure = new HttpTransport(network);
            assertThat(new String(secure.get(URI.create("https://ridi.mock.test/"), UA, LIMITS).body(), StandardCharsets.UTF_8))
                    .isEqualTo("tls ok");
            // 인증서 이름과 다른 호스트
            assertThatThrownBy(() -> secure.get(URI.create("https://other.mock.test/"), UA, LIMITS))
                    .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.CONNECTION));
            // 신뢰하지 않는 인증서(운영 기본 신뢰 저장소)
            TlsSocketConnector system = TlsSocketConnector.system();
            HttpTransport untrusted = new HttpTransport(new CollectorNetwork(host -> List.of(InetAddress.getLoopbackAddress()),
                    loopback, (host, ignored, address, timeout) -> system.connect(host, port, address, timeout)));
            assertThatThrownBy(() -> untrusted.get(URI.create("https://ridi.mock.test/"), UA, LIMITS))
                    .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.CONNECTION));
        } finally {
            server.stop(0);
        }
    }

    private static void assertBlocked(HttpTransport t, String url) {
        assertThatThrownBy(() -> t.get(URI.create(url), UA, LIMITS)).as(url)
                .isInstanceOfSatisfying(FetchException.class,
                        e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.BLOCKED_DESTINATION));
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
