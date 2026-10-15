package com.manhwanet.collection.collector.core;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 로그인·성인 인증·결제(유료) 화면으로 가는 리다이렉트인지 판단한다(설계 §6.3·§7.3). 이런 리다이렉트는 따라가지 않고 GATED 로 기록한다.
 * 공통 규칙은 넓게 잡았다(잘못 걸리면 그 작품의 상세를 못 볼 뿐 우회는 일어나지 않음). 어댑터가 플랫폼별 규칙을 더할 수 있다.
 */
public final class GateDetector {

    private static final List<String> PATH_MARKERS = List.of("login", "logon", "signin", "sign-in", "sign_in", "/auth",
            "oauth", "adult", "verify", "verification", "certif", "age-gate", "agegate", "/member/join", "/join",
            "/account", "payment", "/pay/", "purchase", "billing", "checkout", "/charge", "reason=adult");
    private static final List<String> HOST_PREFIXES = List.of("accounts.", "account.", "auth.", "login.", "nid.", "kauth.",
            "member.", "pay.", "payment.");

    private GateDetector() {
    }

    public static boolean isGate(URI target) {
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        for (String prefix : HOST_PREFIXES) {
            if (host.startsWith(prefix)) {
                return true;
            }
        }
        String path = target.getRawPath() == null ? "" : target.getRawPath();
        String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
        String value = (path + query).toLowerCase(Locale.ROOT);
        for (String marker : PATH_MARKERS) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
