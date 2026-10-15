package com.manhwanet.collection.collector.core;

import com.manhwanet.collection.collector.net.HttpTransport.RawResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 접근 차단 응답 판단(설계 §7.3): 403·451, CAPTCHA·봇 확인 화면, 접근 거부 안내. 차단이면 그 플랫폼 실행을 멈춘다(재시도·우회 없음).
 * 본문은 앞 64KB 만 본다. 잘못 걸리면 실행이 멈출 뿐 우회는 일어나지 않는다(보수적 판단).
 */
public final class BlockDetector {

    static final int SCAN_BYTES = 65_536;
    private static final List<String> MARKERS = List.of(
            "g-recaptcha", "recaptcha/api.js", "h-captcha", "hcaptcha.com", "cf-chl-", "cf_chl_opt", "challenge-platform",
            "/cdn-cgi/challenge", "captcha-delivery.com", "px-captcha", "_incapsula_resource", "awswaf",
            "<title>access denied</title>", "attention required! | cloudflare", "are you a robot", "verify you are human",
            "자동입력 방지", "자동 입력 방지", "비정상적인 접근", "비정상적인 요청", "접근이 차단", "접근이 거부");

    private BlockDetector() {
    }

    public static boolean isBlocked(RawResponse response) {
        if (response.status() == 403 || response.status() == 451) {
            return true;
        }
        String mitigated = response.header("cf-mitigated");
        if (mitigated != null && mitigated.toLowerCase(Locale.ROOT).contains("challenge")) {
            return true;
        }
        return looksLikeBlockPage(response.body());
    }

    public static boolean looksLikeBlockPage(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        int n = Math.min(body.length, SCAN_BYTES);
        String text = new String(body, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
