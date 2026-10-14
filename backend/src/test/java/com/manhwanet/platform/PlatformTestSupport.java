package com.manhwanet.platform;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.user.application.AdminAccountService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 플랫폼·연결 통합 테스트 공통 도우미(실제 로그인 세션·쿠키 CSRF) */
final class PlatformTestSupport {

    static final String ADMIN_EMAIL = "admin@manhwanet.test";
    static final String VERIFIED_AT = "2026-10-12T00:00:00Z";

    private PlatformTestSupport() {
    }

    static AuthTestClient admin(MockMvc mockMvc, ObjectMapper objectMapper, AdminAccountService accounts) throws Exception {
        accounts.createAdmin(ADMIN_EMAIL, STRONG_PASSWORD, "관리자");
        AuthTestClient admin = new AuthTestClient(mockMvc, objectMapper);
        assertThat(admin.login(ADMIN_EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        return admin;
    }

    static long adminId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
    }

    /** 제목·작가를 SQL 로 바로 넣은 작품(version 0) */
    static long insertWork(JdbcTemplate jdbc, String title) {
        long creatorId = jdbc.queryForObject("INSERT INTO creators (name) VALUES ('작가 ' || ?) RETURNING id", Long.class, title);
        long workId = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        jdbc.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, ?, 'ko', TRUE)", workId, title);
        jdbc.update("INSERT INTO work_creators (work_id, creator_id, role) VALUES (?, ?, 'STORY')", workId, creatorId);
        return workId;
    }

    static long workVersion(JdbcTemplate jdbc, long workId) {
        return jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
    }

    static Map<String, Object> listing(long version, String platformCode, String platformWorkId, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("platformCode", platformCode);
        body.put("platformWorkId", platformWorkId);
        body.put("url", url);
        body.put("idScope", "SERIES");
        body.put("listingKind", "MAIN");
        body.put("languageCode", "ko");
        body.put("verifiedAt", VERIFIED_AT);
        body.put("evidence", List.of(listingEvidence(url)));
        return body;
    }

    /** 연결 근거(#40): 사람이 플랫폼 작품 페이지를 확인함 */
    static Map<String, Object> listingEvidence(String url) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceType", "PLATFORM_PAGE_MANUAL");
        evidence.put("sourceRef", url);
        evidence.put("verifiedAt", VERIFIED_AT);
        return evidence;
    }

    static Map<String, Object> kakaoPage(long version, String id) {
        return listing(version, "KAKAO_PAGE", id, "https://page.kakao.com/content/" + id);
    }

    static Map<String, Object> ridi(long version, String id) {
        return listing(version, "RIDI", id, "https://ridibooks.com/books/" + id);
    }

    static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
    }

    static List<String> fields(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.fieldErrors[*].field");
    }

    static long longAt(MvcResult result, String path) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), path)).longValue();
    }

    static <T> T at(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
