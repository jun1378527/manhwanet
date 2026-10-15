package com.manhwanet.collection.collector.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 해석(RFC 9309).
 * <ul>
 *   <li>그룹: 우리 제품 토큰({@value UserAgent#PRODUCT}, 대소문자 무시)과 같은 user-agent 그룹들을 합쳐 쓰고, 없으면 {@code *} 그룹들.
 *       둘 다 없으면 모두 허용.</li>
 *   <li>규칙: 경로+쿼리에 가장 길게 맞는 규칙을 따르고, 길이가 같으면 allow. {@code *} 는 아무 문자열, 끝의 {@code $} 는 끝.
 *       빈 Disallow 는 규칙이 아님. {@code /robots.txt} 는 항상 허용.</li>
 *   <li>퍼센트 인코딩은 대문자 16진수로 맞추고, 비 ASCII 는 UTF-8 로 인코딩해 비교한다.</li>
 *   <li>앞 500KiB 만 해석한다. Crawl-delay(비표준)는 선택된 그룹 값 중 가장 큰 값(최대 3600초).</li>
 *   <li>Sitemap 줄은 위치와 관계없이 모은다.</li>
 * </ul>
 */
public final class RobotsRules {

    public static final int MAX_BYTES = 500 * 1024;
    static final long MAX_CRAWL_DELAY_MILLIS = 3_600_000;

    private record Rule(boolean allow, String pattern) {
    }

    private final List<Rule> rules;
    private final long crawlDelayMillis;
    private final List<String> sitemaps;

    private RobotsRules(List<Rule> rules, long crawlDelayMillis, List<String> sitemaps) {
        this.rules = rules;
        this.crawlDelayMillis = crawlDelayMillis;
        this.sitemaps = sitemaps;
    }

    public static RobotsRules parse(byte[] body) {
        int n = Math.min(body.length, MAX_BYTES);
        String text = new String(body, 0, n, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        String token = UserAgent.PRODUCT.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> star = new ArrayList<>();
        boolean anySpecific = false;
        boolean anyStar = false;
        long specificDelay = -1;
        long starDelay = -1;
        List<String> sitemaps = new ArrayList<>();

        List<String> currentAgents = new ArrayList<>();
        boolean inRules = false;
        for (String rawLine : text.split("\r\n|\r|\n")) {
            int hash = rawLine.indexOf('#');
            String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).strip();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "user-agent" -> {
                    if (inRules) {
                        currentAgents = new ArrayList<>();
                        inRules = false;
                    }
                    String agent = value.toLowerCase(Locale.ROOT);
                    int slash = agent.indexOf('/');
                    agent = (slash >= 0 ? agent.substring(0, slash) : agent).strip();
                    currentAgents.add(agent);
                    if (agent.equals(token)) {
                        anySpecific = true;
                    } else if (agent.equals("*")) {
                        anyStar = true;
                    }
                }
                case "allow", "disallow" -> {
                    inRules = true;
                    if (value.isEmpty()) {
                        continue;
                    }
                    Rule rule = new Rule(key.equals("allow"), normalize(value));
                    if (currentAgents.contains(token)) {
                        specific.add(rule);
                    }
                    if (currentAgents.contains("*")) {
                        star.add(rule);
                    }
                }
                case "crawl-delay" -> {
                    inRules = true;
                    long millis = delayMillis(value);
                    if (millis >= 0) {
                        if (currentAgents.contains(token)) {
                            specificDelay = Math.max(specificDelay, millis);
                        }
                        if (currentAgents.contains("*")) {
                            starDelay = Math.max(starDelay, millis);
                        }
                    }
                }
                case "sitemap" -> {
                    if (!value.isEmpty() && sitemaps.size() < 1000) {
                        sitemaps.add(value);
                    }
                }
                default -> {
                    // 알 수 없는 줄은 무시
                }
            }
        }
        if (anySpecific) {
            return new RobotsRules(List.copyOf(specific), Math.max(0, specificDelay), List.copyOf(sitemaps));
        }
        if (anyStar) {
            return new RobotsRules(List.copyOf(star), Math.max(0, starDelay), List.copyOf(sitemaps));
        }
        return new RobotsRules(List.of(), 0, List.copyOf(sitemaps));
    }

    /** rawPathAndQuery: URI 의 raw path(+ "?" + raw query) */
    public boolean allows(String rawPathAndQuery) {
        String path = rawPathAndQuery == null || rawPathAndQuery.isEmpty() ? "/" : normalize(rawPathAndQuery);
        if (path.equals("/robots.txt")) {
            return true;
        }
        Rule best = null;
        for (Rule rule : rules) {
            if (matches(rule.pattern(), path)) {
                if (best == null || rule.pattern().length() > best.pattern().length()
                        || (rule.pattern().length() == best.pattern().length() && rule.allow() && !best.allow())) {
                    best = rule;
                }
            }
        }
        return best == null || best.allow();
    }

    public long crawlDelayMillis() {
        return crawlDelayMillis;
    }

    public List<String> sitemaps() {
        return Collections.unmodifiableList(sitemaps);
    }

    /** 와일드카드 일치(접두 일치, 끝 $ 는 끝 고정). 되돌아가기를 마지막 * 하나로 제한해 O(n·m) */
    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern + "*";
        int pi = 0;
        int si = 0;
        int star = -1;
        int mark = 0;
        while (si < path.length()) {
            if (pi < p.length() && p.charAt(pi) != '*' && p.charAt(pi) == path.charAt(si)) {
                pi++;
                si++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = si;
            } else if (star >= 0) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }

    /** 퍼센트 인코딩 16진수를 대문자로, 비 ASCII·공백을 UTF-8 퍼센트 인코딩으로 */
    static String normalize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                out.append('%').append(Character.toUpperCase((char) bytes[i + 1])).append(Character.toUpperCase((char) bytes[i + 2]));
                i += 2;
            } else if (b <= 0x20 || b >= 0x7f) {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b));
            } else {
                out.append((char) b);
            }
        }
        return out.toString();
    }

    private static boolean isHex(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    private static long delayMillis(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (Double.isNaN(seconds) || seconds < 0) {
                return -1;
            }
            return (long) Math.min(MAX_CRAWL_DELAY_MILLIS, Math.ceil(seconds * 1000));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
