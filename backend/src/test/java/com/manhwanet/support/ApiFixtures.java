package com.manhwanet.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.auth.application.AuthenticatedUser;
import com.manhwanet.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등록 API 호출 헬퍼 (AdminApiIntegrationTest 에서 사용). ADMIN 사용자 + 유효한 CSRF 토큰으로 요청한다.
 *
 * <p>CSRF 토큰은 브라우저와 같이 GET /api/v1/auth/csrf 가 내려준 XSRF-TOKEN 쿠키 값을 헤더로 보낸다.
 * spring-security-test 의 csrf() 후처리기는 쓰지 않는다. 그 후처리기는 공유 필터 체인의 CSRF 저장소를
 * HttpSessionCsrfTokenRepository 기반 테스트 저장소로 바꿔 두므로, 같은 Spring 컨텍스트를 쓰는 다른 테스트의
 * 쿠키 CSRF 흐름(AuthTestClient)이 테스트 실행 순서에 따라 깨진다.
 *
 * <p>#40: 관리 변경 기록이 관리자 사용자 ID(users FK)를 남기므로, 테스트 DB 에 실제 ADMIN 행을 두고
 * 그 ID 의 AuthenticatedUser 로 요청한다(행이 없으면 만든다. 테스트 DB 정리 뒤에도 다시 만든다).
 */
public final class ApiFixtures {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public static final String ADMIN_EMAIL = "fixture-admin@manhwanet.test";

    public ApiFixtures(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 요청을 보낼 ADMIN 사용자 ID(없으면 만든다) */
    public long adminId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role)
                VALUES (?, '{noop}unused', '픽스처관리자', 'ADMIN') RETURNING id""", Long.class, ADMIN_EMAIL);
    }

    public ResultActions postJson(String path, Object body) throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie(AuthTestClient.CSRF_COOKIE);
        if (csrfCookie == null) {
            throw new IllegalStateException("XSRF-TOKEN cookie was not issued");
        }
        return mockMvc.perform(post(path)
                .with(authentication(adminAuthentication()))
                .cookie(csrfCookie)
                .header(AuthTestClient.CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        AuthenticatedUser admin = new AuthenticatedUser(adminId(), ADMIN_EMAIL, UserRole.ADMIN, "{noop}unused");
        return UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities());
    }

    public long createCreator(String name) throws Exception {
        String body = postJson("/api/v1/admin/creators", Map.of("name", name))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    public long createWork(Map<String, Object> request) throws Exception {
        String body = postJson("/api/v1/admin/works", request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    /** 대표 제목 1개 + 지정한 작가들(STORY)로 작품 등록 요청을 만든다. */
    public static Map<String, Object> workRequest(String primaryTitle, long... storyCreatorIds) {
        List<Map<String, Object>> creators = new ArrayList<>();
        for (long creatorId : storyCreatorIds) {
            creators.add(credit(creatorId, "STORY"));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workFormat", "WEBTOON");
        request.put("titles", List.of(title(primaryTitle, "ko", true)));
        request.put("creators", creators);
        request.put("evidence", basicEvidence());
        return request;
    }

    /** 등록에 필요한 최소 근거(#40): 제목·크레딧을 직접 확인(STAFF)했다는 근거 */
    public static List<Map<String, Object>> basicEvidence() {
        return List.of(evidence("TITLE"), evidence("CREDIT"));
    }

    public static Map<String, Object> evidence(String field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("sourceType", "STAFF");
        map.put("verifiedAt", "2026-10-12T00:00:00Z");
        return map;
    }

    public static Map<String, Object> title(String title, String languageCode, boolean primary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("languageCode", languageCode);
        map.put("primary", primary);
        return map;
    }

    public static Map<String, Object> credit(long creatorId, String role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("creatorId", creatorId);
        map.put("role", role);
        return map;
    }
}
