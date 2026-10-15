package com.manhwanet.collection;

import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.application.CollectedListing.CollectedCredit;
import com.manhwanet.collection.application.CollectedListing;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** #45 테스트 도우미 */
final class CollectionTestSupport {

    static final String OWNER = "collector-a";
    static final String OTHER = "collector-b";

    private CollectionTestSupport() {
    }

    static long insertAdmin(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('ops@manhwanet.test', '{noop}x', '운영자', 'ADMIN')
                RETURNING id""", Long.class);
    }

    /** 수집 설정을 바꾼다(테스트 준비용, 바꾼 사람 기록 포함) */
    static void setPolicy(JdbcTemplate jdbc, String code, String policy, long adminId) {
        jdbc.update("""
                UPDATE platform_collection_policies SET collection_policy = ?, decided_by = ?, decided_at = now()
                WHERE platform_code = ?""", policy, adminId, code);
    }

    static void setSynopsisPolicy(JdbcTemplate jdbc, String code, String policy, long adminId) {
        jdbc.update("""
                UPDATE platform_collection_policies SET synopsis_policy = ?, decided_by = ?, decided_at = now()
                WHERE platform_code = ?""", policy, adminId, code);
    }

    static void expireLease(JdbcTemplate jdbc, long runId) {
        jdbc.update("UPDATE crawl_runs SET lease_until = now() - interval '1 second' WHERE id = ?", runId);
    }

    static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 리디 상세 결과(OK) */
    static CollectedListing ridi(String id, String title, String ageRaw) {
        return listing("RIDI", id, "https://ridibooks.com/books/" + id, "OK", title, ageRaw, List.of(),
                List.of(new CollectedCredit("이동건", "글·그림", "STORY", "a1"), new CollectedCredit("이동건", "글·그림", "ART", "a1")),
                "소개문 " + title, "list:weekly", List.of());
    }

    static CollectedListing listing(String platform, String id, String url, String status, String title, String ageRaw,
            List<AgeSignal> signals, List<CollectedCredit> credits, String synopsis, String discovery, List<String> aliases) {
        return new CollectedListing(platform, id, url, status, "SERIES", "ko", title, title, List.of("드라마"), ageRaw,
                signals, "연재중", "ONGOING", "https://img.ridicdn.net/cover/" + id + "/large", synopsis,
                Map.of("publisher", "출판사"), Map.of("titleRaw", Map.of("path", "h1")), credits, discovery, aliases);
    }

    static CollectedListing withStatus(CollectedListing c, String status) {
        return new CollectedListing(c.platformCode(), c.platformWorkId(), c.url(), status, c.idScope(), c.languageCode(),
                c.titleRaw(), c.titleClean(), c.genresRaw(), c.ageRatingRaw(), c.ageSignals(), c.serialStatusRaw(),
                c.serialStatus(), c.coverUrl(), c.synopsis(), c.extra(), c.fieldSources(), c.credits(), c.discoverySource(),
                c.aliasIds());
    }

    static CollectedListing withTitle(CollectedListing c, String title) {
        return new CollectedListing(c.platformCode(), c.platformWorkId(), c.url(), c.detailStatus(), c.idScope(),
                c.languageCode(), title, title, c.genresRaw(), c.ageRatingRaw(), c.ageSignals(), c.serialStatusRaw(),
                c.serialStatus(), c.coverUrl(), c.synopsis(), c.extra(), c.fieldSources(), c.credits(), c.discoverySource(),
                c.aliasIds());
    }
}
