package com.manhwanet.collection.review;

import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.application.CollectedListing.CollectedCredit;
import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.application.StagingIngestService;
import com.manhwanet.collection.domain.RunKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #51 검수 테스트·시연용 가상 데이터. 실제 작품이 아니다.
 * 제목·작가 이름에 "[DEMO]"·"가상"을 붙이고, 플랫폼 주소는 실제로 해석되지 않는 *.mock.test 호스트만 쓴다.
 */
final class ReviewFixtures {

    static final String OWNER = "demo-collector";
    static final String TOOMICS_HOST = "toomics.mock.test";
    static final String MRBLUE_HOST = "mrblue.mock.test";
    static final String DEMO_NOTE = "[DEMO] 시연용 가상 데이터(실제 작품 아님)";

    private ReviewFixtures() {
    }

    /** 가상 호스트를 플랫폼 허용 호스트에 넣고 수집 설정을 켠다(테스트 DB 에서만) */
    static void preparePlatforms(JdbcTemplate jdbc, long adminId) {
        jdbc.update("INSERT INTO platform_hosts (platform_code, host) VALUES ('TOOMICS', ?), ('MRBLUE', ?)", TOOMICS_HOST,
                MRBLUE_HOST);
        jdbc.update("""
                UPDATE platform_collection_policies SET collection_policy = 'ENABLED', decided_by = ?, decided_at = now(),
                    note = '[DEMO] 테스트 DB 전용' WHERE platform_code IN ('TOOMICS', 'MRBLUE')""", adminId);
    }

    static long startRun(CrawlRunService runs, String platform) {
        return runs.start(platform, RunKind.FULL, "demo-1", OWNER, 300, null).id();
    }

    static String url(String platform, String id) {
        return "https://" + (platform.equals("TOOMICS") ? TOOMICS_HOST : MRBLUE_HOST) + "/demo/" + id;
    }

    static CollectedListing listing(String platform, String id, String title, String ageRaw, List<String[]> credits) {
        return listing(platform, id, title, ageRaw, credits, "OK", List.of());
    }

    static CollectedListing listing(String platform, String id, String title, String ageRaw, List<String[]> credits,
            String detailStatus, List<String> aliases) {
        List<CollectedCredit> collected = new ArrayList<>();
        for (String[] c : credits) {
            collected.add(new CollectedCredit(c[0], c[1], c[1].equals("글") ? "STORY" : c[1].equals("그림") ? "ART" : "UNSPECIFIED",
                    null));
        }
        List<AgeSignal> signals = ageRaw == null ? List.of() : List.of(new AgeSignal("detail:age", ageRaw, true));
        return new CollectedListing(platform, id, url(platform, id), detailStatus, "SERIES", "ko", title, title, List.of("판타지"),
                ageRaw, signals, "연재중", "ONGOING", null, null, Map.of("demo", true), Map.of(), collected, "demo:list", aliases);
    }

    static long ingest(StagingIngestService ingest, long runId, CollectedListing listing) {
        return ingest.ingest(runId, OWNER, listing).stagingId();
    }

    static Map<String, Object> staff(String field) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (field != null) {
            e.put("field", field);
        }
        e.put("sourceType", "STAFF");
        e.put("verifiedAt", "2026-10-15T00:00:00Z");
        e.put("note", DEMO_NOTE);
        return e;
    }

    static Map<String, Object> listingOptions() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("listingKind", "MAIN");
        l.put("note", DEMO_NOTE);
        l.put("evidence", List.of(staff(null)));
        return l;
    }

    static Map<String, Object> createWork(long revision, String reason, List<Map<String, Object>> credits,
            List<Map<String, Object>> candidates) {
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("workFormat", "WEBTOON");
        work.put("originCountry", "KR");
        work.put("credits", credits);
        work.put("evidence", List.of(staff("TITLE"), staff("CREDIT")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "CREATE_WORK");
        body.put("stagingRevision", revision);
        body.put("reason", reason);
        body.put("candidates", candidates);
        body.put("work", work);
        body.put("listing", listingOptions());
        return body;
    }

    static Map<String, Object> link(long revision, long workId, long workVersion, String reason,
            List<Map<String, Object>> candidates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "LINK_EXISTING");
        body.put("stagingRevision", revision);
        body.put("reason", reason);
        body.put("candidates", candidates);
        body.put("workId", workId);
        body.put("workVersion", workVersion);
        body.put("listing", listingOptions());
        return body;
    }

    static Map<String, Object> newCreator(String name, String role) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("newCreatorName", name);
        c.put("role", role);
        return c;
    }

    static Map<String, Object> existingCreator(long id, String role) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("creatorId", id);
        c.put("role", role);
        return c;
    }

    static Map<String, Object> judge(long candidateId, String decision) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", candidateId);
        c.put("decision", decision);
        return c;
    }
}
