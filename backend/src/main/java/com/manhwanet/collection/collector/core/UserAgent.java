package com.manhwanet.collection.collector.core;

import java.net.URI;
import java.util.regex.Pattern;

/** 수집기 사용자 에이전트(설계 §7.2, D-16): {@code ManhwanetCollector/{버전} (+{연락처 URL})}. 바꿔 가며 쓰지 않는다 */
public final class UserAgent {

    public static final String PRODUCT = "ManhwanetCollector";
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.\\-]{1,20}$");

    private UserAgent() {
    }

    /** 연락처가 없거나 https URL 이 아니면 IllegalArgumentException(수집을 시작하지 않음) */
    public static String of(String version, String contactUrl) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("수집기 버전 형식이 틀립니다.");
        }
        if (contactUrl == null || contactUrl.isBlank()) {
            throw new IllegalArgumentException("manhwanet.collector.contact-url(연락처 URL)을 지정해야 수집을 시작합니다(D-16).");
        }
        String url = contactUrl.strip();
        if (url.length() > 200 || !url.chars().allMatch(c -> c > 0x20 && c < 0x7f) || url.contains("(") || url.contains(")")) {
            throw new IllegalArgumentException("연락처 URL 은 공백·괄호 없는 ASCII 200자 이하여야 합니다.");
        }
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("연락처 URL 은 https 주소여야 합니다.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("연락처 URL 형식이 틀립니다.");
        }
        return PRODUCT + "/" + version + " (+" + url + ")";
    }
}
