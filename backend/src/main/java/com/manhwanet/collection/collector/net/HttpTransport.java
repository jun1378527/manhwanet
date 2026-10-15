package com.manhwanet.collection.collector.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 수집기 전용 최소 HTTP/1.1 GET 클라이언트.
 * <p>JDK {@code HttpClient} 는 이름 해석을 바꿀 수 없어 "검사한 IP 로만 연결"을 보장하지 못한다(검사 뒤 다시 해석).
 * 그래서 직접 해석·검사한 IP 에 소켓을 열고 TLS 에서 원래 호스트 이름을 확인하는 이 클래스를 쓴다(docs/collector-core-spec.md §12).
 * <ul>
 *   <li>요청 헤더는 Host·User-Agent·Accept·Accept-Encoding(gzip)·Connection(close) 뿐이다. 쿠키·인증 헤더를 보내지 않는다.</li>
 *   <li>리다이렉트를 따라가지 않는다(호출자가 한 단계씩 검사). 3xx 본문은 읽지 않는다.</li>
 *   <li>연결 시간·읽기 간격·전체 시간 제한, 헤더·본문(압축 해제 뒤 포함) 크기 제한.</li>
 *   <li>응답 헤더 중 Set-Cookie 는 버린다. DB 트랜잭션 안에서 부르면 거절한다(네트워크 대기 중 연결 점유 방지).</li>
 * </ul>
 */
public final class HttpTransport {

    public record Limits(int connectTimeoutMillis, int readTimeoutMillis, int totalTimeoutMillis, long maxBodyBytes) {
        public Limits {
            if (connectTimeoutMillis < 1 || readTimeoutMillis < 1 || totalTimeoutMillis < 1 || maxBodyBytes < 1
                    || maxBodyBytes > Integer.MAX_VALUE - 16) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    /** 응답. headers 이름은 소문자이며 Set-Cookie 는 들어 있지 않다 */
    public record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        public boolean isRedirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }

    static final int MAX_LINE = 8192;
    static final int MAX_HEADER_BYTES = 65_536;
    static final int MAX_HEADERS = 200;
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,text/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5";

    private final CollectorNetwork network;

    public HttpTransport(CollectorNetwork network) {
        this.network = Objects.requireNonNull(network);
    }

    public RawResponse get(URI uri, String userAgent, Limits limits) throws FetchException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DB 트랜잭션 안에서 네트워크 요청을 보내지 않습니다.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new FetchException(FetchException.Kind.BLOCKED_DESTINATION, "https 호스트 URL 만 요청합니다: " + LogSafe.url(uri));
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        List<InetAddress> addresses = resolve(host);
        for (InetAddress address : addresses) {
            if (!network.addressPolicy().permits(address)) {
                throw new FetchException(FetchException.Kind.BLOCKED_DESTINATION,
                        "공개 주소가 아닌 목적지라 연결하지 않습니다: " + host);
            }
        }
        long deadline = System.nanoTime() + limits.totalTimeoutMillis() * 1_000_000L;
        Socket socket = connect(host, port, addresses, limits);
        try {
            OutputStream out = socket.getOutputStream();
            out.write(request(uri, host, port, userAgent));
            out.flush();
            InputStream in = new DeadlineInputStream(socket, socket.getInputStream(), limits.readTimeoutMillis(), deadline);
            return read(in, limits.maxBodyBytes());
        } catch (SocketTimeoutException e) {
            throw new FetchException(FetchException.Kind.TIMEOUT, "응답 시간 초과: " + host);
        } catch (IOException e) {
            throw new FetchException(FetchException.Kind.CONNECTION, "응답을 읽지 못했습니다: " + host + " (" + e.getClass().getSimpleName() + ")");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 닫기 실패는 무시
            }
        }
    }

    private List<InetAddress> resolve(String host) throws FetchException {
        try {
            List<InetAddress> addresses = network.resolver().resolve(host);
            if (addresses == null || addresses.isEmpty()) {
                throw new FetchException(FetchException.Kind.CONNECTION, "주소를 찾지 못했습니다: " + host);
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw new FetchException(FetchException.Kind.CONNECTION, "주소를 찾지 못했습니다: " + host);
        }
    }

    private Socket connect(String host, int port, List<InetAddress> addresses, Limits limits) throws FetchException {
        IOException last = null;
        for (InetAddress address : addresses) {
            try {
                return network.connector().connect(host, port, address, limits.connectTimeoutMillis());
            } catch (IOException e) {
                last = e;
            }
        }
        if (last instanceof SocketTimeoutException) {
            throw new FetchException(FetchException.Kind.TIMEOUT, "연결 시간 초과: " + host);
        }
        throw new FetchException(FetchException.Kind.CONNECTION, "연결하지 못했습니다: " + host
                + (last == null ? "" : " (" + last.getClass().getSimpleName() + ")"));
    }

    static byte[] request(URI uri, String host, int port, String userAgent) throws FetchException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        if (!visibleAscii(target) || !printableAscii(userAgent)) {
            throw new FetchException(FetchException.Kind.BLOCKED_DESTINATION, "요청 줄에 쓸 수 없는 문자가 있습니다.");
        }
        String hostHeader = port == 443 ? host : host + ":" + port;
        String head = "GET " + target + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: " + userAgent + "\r\n"
                + "Accept: " + ACCEPT + "\r\n"
                + "Accept-Encoding: gzip\r\n"
                + "Connection: close\r\n\r\n";
        return head.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean visibleAscii(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(c -> c > 0x20 && c < 0x7f);
    }

    private static boolean printableAscii(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    static RawResponse read(InputStream in, long maxBody) throws IOException, FetchException {
        while (true) {
            String statusLine = line(in, new int[] {0});
            if (statusLine == null) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "응답이 비어 있습니다.");
            }
            int status = parseStatus(statusLine);
            Map<String, List<String>> headers = headers(in);
            if (status >= 100 && status < 200) {
                if (status == 101) {
                    throw new FetchException(FetchException.Kind.PROTOCOL, "프로토콜 전환 응답은 처리하지 않습니다.");
                }
                continue; // 100 Continue 등 정보 응답은 건너뛴다
            }
            RawResponse head = new RawResponse(status, headers, new byte[0]);
            if (head.isRedirect() || status == 204 || status == 304) {
                return head;
            }
            byte[] body = body(in, headers, maxBody);
            String encoding = first(headers, "content-encoding");
            if (encoding != null && !encoding.isBlank() && !encoding.strip().equalsIgnoreCase("identity")) {
                if (!encoding.strip().equalsIgnoreCase("gzip")) {
                    throw new FetchException(FetchException.Kind.PROTOCOL, "지원하지 않는 Content-Encoding 입니다.");
                }
                body = gunzip(body, maxBody);
            }
            return new RawResponse(status, Collections.unmodifiableMap(headers), body);
        }
    }

    private static int parseStatus(String line) throws FetchException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3) {
            throw new FetchException(FetchException.Kind.PROTOCOL, "상태 줄 형식이 틀립니다.");
        }
        try {
            int status = Integer.parseInt(parts[1]);
            if (status < 100 || status > 599) {
                throw new NumberFormatException();
            }
            return status;
        } catch (NumberFormatException e) {
            throw new FetchException(FetchException.Kind.PROTOCOL, "상태 코드 형식이 틀립니다.");
        }
    }

    private static Map<String, List<String>> headers(InputStream in) throws IOException, FetchException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int[] total = {0};
        int count = 0;
        while (true) {
            String line = line(in, total);
            if (line == null) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "헤더가 끝나기 전에 연결이 닫혔습니다.");
            }
            if (line.isEmpty()) {
                return headers;
            }
            if (++count > MAX_HEADERS || total[0] > MAX_HEADER_BYTES) {
                throw new FetchException(FetchException.Kind.TOO_LARGE, "응답 헤더가 너무 큽니다.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "헤더 형식이 틀립니다.");
            }
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (name.equals("set-cookie") || name.equals("set-cookie2")) {
                continue; // 쿠키는 저장·기록하지 않는다
            }
            headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(line.substring(colon + 1).strip());
        }
    }

    private static byte[] body(InputStream in, Map<String, List<String>> headers, long maxBody) throws IOException, FetchException {
        String transfer = first(headers, "transfer-encoding");
        if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
            return chunked(in, maxBody);
        }
        String length = first(headers, "content-length");
        if (length != null) {
            long n;
            try {
                n = Long.parseLong(length.strip());
            } catch (NumberFormatException e) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "Content-Length 형식이 틀립니다.");
            }
            if (n < 0) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "Content-Length 형식이 틀립니다.");
            }
            if (n > maxBody) {
                throw new FetchException(FetchException.Kind.TOO_LARGE, "응답이 크기 상한(" + maxBody + "바이트)을 넘습니다.");
            }
            byte[] data = in.readNBytes((int) n);
            if (data.length != n) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "본문이 Content-Length 보다 짧습니다.");
            }
            return data;
        }
        return readLimited(in, maxBody);
    }

    private static byte[] chunked(InputStream in, long maxBody) throws IOException, FetchException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = line(in, new int[] {0});
            if (sizeLine == null) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "청크가 끝나기 전에 연결이 닫혔습니다.");
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi < 0 ? sizeLine : sizeLine.substring(0, semi)).strip();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "청크 크기 형식이 틀립니다.");
            }
            if (size < 0 || hex.length() > 15) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "청크 크기 형식이 틀립니다.");
            }
            if (size == 0) {
                headers(in); // 트레일러는 읽고 버린다
                return out.toByteArray();
            }
            if (out.size() + size > maxBody) {
                throw new FetchException(FetchException.Kind.TOO_LARGE, "응답이 크기 상한(" + maxBody + "바이트)을 넘습니다.");
            }
            byte[] data = in.readNBytes((int) size);
            if (data.length != size) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "청크가 짧습니다.");
            }
            out.write(data);
            String end = line(in, new int[] {0});
            if (end == null || !end.isEmpty()) {
                throw new FetchException(FetchException.Kind.PROTOCOL, "청크 끝 형식이 틀립니다.");
            }
        }
    }

    private static byte[] gunzip(byte[] body, long maxBody) throws FetchException {
        try (InputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
            return readLimited(gz, maxBody);
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            throw new FetchException(FetchException.Kind.PROTOCOL, "gzip 본문을 풀지 못했습니다.");
        }
    }

    private static byte[] readLimited(InputStream in, long maxBody) throws IOException, FetchException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + (long) n > maxBody) {
                throw new FetchException(FetchException.Kind.TOO_LARGE, "응답이 크기 상한(" + maxBody + "바이트)을 넘습니다.");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** CRLF(또는 LF)로 끝나는 한 줄. 연결이 바로 닫히면 null */
    private static String line(InputStream in, int[] total) throws IOException, FetchException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                total[0] += bytes.length + 1;
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            if (buf.size() >= MAX_LINE) {
                throw new FetchException(FetchException.Kind.TOO_LARGE, "응답 줄이 너무 깁니다.");
            }
            buf.write(c);
        }
        if (any) {
            throw new FetchException(FetchException.Kind.PROTOCOL, "줄이 끝나기 전에 연결이 닫혔습니다.");
        }
        return null;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** 읽기마다 읽기 시간 제한과 남은 전체 시간 중 짧은 쪽을 소켓 시간 제한으로 건다 */
    static final class DeadlineInputStream extends InputStream {
        private final Socket socket;
        private final InputStream in;
        private final int readTimeout;
        private final long deadline;
        private final byte[] one = new byte[1];
        private final byte[] buffer = new byte[8192];
        private int pos;
        private int limit;

        DeadlineInputStream(Socket socket, InputStream in, int readTimeout, long deadline) {
            this.socket = socket;
            this.in = in;
            this.readTimeout = readTimeout;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (pos >= limit) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) {
                    throw new SocketTimeoutException("total timeout");
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(readTimeout, remaining)));
                limit = in.read(buffer, 0, buffer.length);
                pos = 0;
                if (limit == -1) {
                    limit = 0;
                    return -1;
                }
            }
            int n = Math.min(len, limit - pos);
            System.arraycopy(buffer, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
