package com.manhwanet.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등록 API 호출 헬퍼 (AdminApiIntegrationTest 에서 사용). ADMIN 사용자 + 유효한 CSRF 토큰으로 요청한다.
 *
 * <p>CSRF 토큰은 브라우저와 같이 GET /api/v1/auth/csrf 가 내려준 XSRF-TOKEN 쿠키 값을 헤더로 보낸다.
 * spring-security-test 의 csrf() 후처리기는 쓰지 않는다. 그 후처리기는 공유 필터 체인의 CSRF 저장소를
 * HttpSessionCsrfTokenRepository 기반 테스트 저장소로 바꿔 두므로, 같은 Spring 컨텍스트를 쓰는 다른 테스트의
 * 쿠키 CSRF 흐름(AuthTestClient)이 테스트 실행 순서에 따라 깨진다.
 */
public final class ApiFixtures {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiFixtures(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public ResultActions postJson(String path, Object body) throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie(AuthTestClient.CSRF_COOKIE);
        if (csrfCookie == null) {
            throw new IllegalStateException("XSRF-TOKEN cookie was not issued");
        }
        return mockMvc.perform(post(path)
                .with(user("admin@manhwanet.test").roles("ADMIN"))
                .cookie(csrfCookie)
                .header(AuthTestClient.CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
        return request;
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
