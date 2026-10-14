package com.manhwanet.provenance;

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

/** #40 테스트 도우미 */
final class ProvenanceTestSupport {

    static final String ADMIN_EMAIL = "admin@manhwanet.test";
    static final String VERIFIED_AT = "2026-10-12T00:00:00Z";

    private ProvenanceTestSupport() {
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

    static long insertCreator(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO creators (name) VALUES (?) RETURNING id", Long.class, name);
    }

    /** 제목·작가를 SQL 로 바로 넣은 작품(version 0) */
    static long insertWork(JdbcTemplate jdbc, String title, long creatorId) {
        long workId = jdbc.queryForObject("INSERT INTO works (work_format) VALUES ('WEBTOON') RETURNING id", Long.class);
        jdbc.update("INSERT INTO work_titles (work_id, title, language_code, is_primary) VALUES (?, ?, 'ko', TRUE)", workId, title);
        jdbc.update("INSERT INTO work_creators (work_id, creator_id, role) VALUES (?, ?, 'STORY')", workId, creatorId);
        return workId;
    }

    static long workVersion(JdbcTemplate jdbc, long workId) {
        return jdbc.queryForObject("SELECT version FROM works WHERE id = ?", Long.class, workId);
    }

    static long creatorVersion(JdbcTemplate jdbc, long creatorId) {
        return jdbc.queryForObject("SELECT version FROM creators WHERE id = ?", Long.class, creatorId);
    }

    static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    static Map<String, Object> externalId(long version, String source, String externalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("source", source);
        body.put("externalId", externalId);
        body.put("verifiedAt", VERIFIED_AT);
        return body;
    }

    static Map<String, Object> link(long version, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("linkType", "PUBLISHER");
        body.put("url", url);
        body.put("languageCode", "ko");
        body.put("verifiedAt", VERIFIED_AT);
        return body;
    }

    static Map<String, Object> evidence(String field, String sourceType, String sourceRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", field);
        body.put("sourceType", sourceType);
        if (sourceRef != null) {
            body.put("sourceRef", sourceRef);
        }
        body.put("verifiedAt", VERIFIED_AT);
        return body;
    }

    static Map<String, Object> status(long version, String status, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
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

    static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
