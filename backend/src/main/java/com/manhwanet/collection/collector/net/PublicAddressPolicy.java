package com.manhwanet.collection.collector.net;

import java.net.InetAddress;

/**
 * 공개 유니캐스트 주소만 허용한다(운영 기본값). 막는 범위:
 * <ul>
 *   <li>IPv4: 0/8, 10/8, 100.64/10(CGNAT·일부 클라우드 메타데이터), 127/8, 169.254/16(링크 로컬·메타데이터 169.254.169.254),
 *       172.16/12, 192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4</li>
 *   <li>IPv6: 2000::/3 밖 전부(::, ::1, IPv4 호환·매핑 주소의 비공개 대상, fc00::/7 ULA·fd00:ec2::254, fe80::/10, ff00::/8 …),
 *       2001::/23(Teredo 등 프로토콜 할당), 2001:db8::/32, 2002::/16 중 비공개 IPv4 를 품은 것, 64:ff9b::/96 중 비공개 IPv4 를 품은 것</li>
 * </ul>
 */
public final class PublicAddressPolicy implements AddressPolicy {

    @Override
    public boolean permits(InetAddress address) {
        return isPublic(address);
    }

    public static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        return b.length == 4 ? publicV4(b, 0) : publicV6(b);
    }

    private static boolean publicV6(byte[] b) {
        boolean first10Zero = true;
        for (int i = 0; i < 10; i++) {
            first10Zero &= b[i] == 0;
        }
        if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return publicV4(b, 12); // ::ffff:a.b.c.d
        }
        if (first10Zero && b[10] == 0 && b[11] == 0) {
            return false; // ::, ::1, ::a.b.c.d (IPv4 호환, 폐지)
        }
        int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        if ((b[0] & 0xe0) != 0x20) {
            // 2000::/3 밖. 64:ff9b::/96(NAT64)만 품은 IPv4 로 판단한다
            boolean nat64 = w0 == 0x0064 && w1 == 0xff9b;
            for (int i = 4; i < 12 && nat64; i++) {
                nat64 = b[i] == 0;
            }
            return nat64 && publicV4(b, 12);
        }
        if (w0 == 0x2001 && w1 < 0x0200) {
            return false; // 2001::/23 (Teredo 2001::/32, ORCHID 등)
        }
        if (w0 == 0x2001 && w1 == 0x0db8) {
            return false; // 문서용
        }
        if (w0 == 0x2002) {
            return publicV4(b, 2); // 6to4 는 품은 IPv4 로 판단
        }
        return true;
    }

    private static boolean publicV4(byte[] b, int o) {
        int a = b[o] & 0xff;
        int c = b[o + 1] & 0xff;
        int d = b[o + 2] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return false;
        }
        if (a == 100 && c >= 64 && c <= 127) {
            return false;
        }
        if (a == 169 && c == 254) {
            return false;
        }
        if (a == 172 && c >= 16 && c <= 31) {
            return false;
        }
        if (a == 192 && ((c == 0 && (d == 0 || d == 2)) || (c == 88 && d == 99) || c == 168)) {
            return false;
        }
        if (a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100))) {
            return false;
        }
        return !(a == 203 && c == 0 && d == 113);
    }
}
