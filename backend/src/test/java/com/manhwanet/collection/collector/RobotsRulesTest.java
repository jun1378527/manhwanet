package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.manhwanet.collection.collector.core.RobotsRules;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** robots.txt 해석(RFC 9309) */
class RobotsRulesTest {

    private static RobotsRules parse(String text) {
        return RobotsRules.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void specificGroupWinsOverStarAndGroupsAreMerged() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /

                User-agent: ManhwanetCollector
                Disallow: /api/
                Crawl-delay: 7

                User-agent: manhwanetcollector/2.0
                Disallow: /private
                """);
        assertThat(rules.allows("/books/1")).isTrue();
        assertThat(rules.allows("/api/x")).isFalse();
        assertThat(rules.allows("/private/a")).isFalse();
        assertThat(rules.crawlDelayMillis()).isEqualTo(7_000);
    }

    @Test
    void starGroupAppliesWhenNoSpecificGroup() {
        RobotsRules rules = parse("User-agent: Googlebot\nDisallow: /\n\nUser-agent: *\nDisallow: /search\nCrawl-delay: 2.5\n");
        assertThat(rules.allows("/search?q=1")).isFalse();
        assertThat(rules.allows("/books")).isTrue();
        assertThat(rules.crawlDelayMillis()).isEqualTo(2_500);
    }

    @Test
    void noMatchingGroupAllowsAll() {
        assertThat(parse("User-agent: Googlebot\nDisallow: /\n").allows("/anything")).isTrue();
        assertThat(parse("").allows("/anything")).isTrue();
    }

    @Test
    void longestMatchWinsAndAllowWinsTies() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /books/
                Allow: /books/public/
                Disallow: /a
                Allow: /a
                """);
        assertThat(rules.allows("/books/1")).isFalse();
        assertThat(rules.allows("/books/public/1")).isTrue();
        assertThat(rules.allows("/a/b")).isTrue();
    }

    @Test
    void wildcardsAndEndAnchor() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /*.json$
                Disallow: /*?sort=
                Disallow: /exact$
                """);
        assertThat(rules.allows("/data.json")).isFalse();
        assertThat(rules.allows("/data.json?x=1")).isTrue();
        assertThat(rules.allows("/list?sort=new")).isFalse();
        assertThat(rules.allows("/list?page=2")).isTrue();
        assertThat(rules.allows("/exact")).isFalse();
        assertThat(rules.allows("/exactly")).isTrue();
    }

    @Test
    void emptyDisallowCommentsBomAndRobotsTxtItself() {
        RobotsRules rules = parse("﻿User-agent: * # 모두\nDisallow:\nDisallow: / # 전부 금지\nSitemap: https://x.test/s.xml\n");
        assertThat(rules.allows("/robots.txt")).isTrue();
        assertThat(rules.allows("/x")).isFalse();
        assertThat(rules.sitemaps()).containsExactly("https://x.test/s.xml");
    }

    @Test
    void percentEncodingIsComparedCaseInsensitivelyAndUtf8() {
        RobotsRules rules = parse("User-agent: *\nDisallow: /%ea%b2%80\nDisallow: /웹툰\n");
        assertThat(rules.allows("/%EA%B2%80색")).isFalse();
        assertThat(rules.allows("/%EC%9B%B9%ED%88%B0/1")).isFalse();
        assertThat(rules.allows("/other")).isTrue();
    }

    @Test
    void manyWildcardsDoNotBacktrackExponentially() {
        RobotsRules rules = parse("User-agent: *\nDisallow: /" + "*a".repeat(30) + "b\n");
        long start = System.nanoTime();
        assertThat(rules.allows("/" + "a".repeat(5_000))).isTrue();
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    void crawlDelayIsCappedAndInvalidValuesIgnored() {
        assertThat(parse("User-agent: *\nCrawl-delay: 99999\n").crawlDelayMillis()).isEqualTo(3_600_000);
        assertThat(parse("User-agent: *\nCrawl-delay: abc\n").crawlDelayMillis()).isZero();
    }

    @Test
    void onlyFirst500KibAreParsed() {
        String filler = "#" + "x".repeat(RobotsRules.MAX_BYTES) + "\n";
        assertThat(parse("User-agent: *\n" + filler + "Disallow: /\n").allows("/a")).isTrue();
    }
}
