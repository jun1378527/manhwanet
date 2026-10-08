package com.manhwanet.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 실제 브라우저 흐름(쿠키 CSRF + 세션)을 MockMvc 로 흉내 낸다.
 * CSRF 토큰은 GET /api/v1/auth/csrf 가 내려준 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다.
 */
public final class AuthTestClient {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    public static final String STRONG_PASSWORD = "plum-river-lantern-42";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    private Cookie csrfCookie;
    private MockHttpSession session;
    /** 모든 요청에 붙일 추가 헤더 (예: 리뷰 API 의 X-Expected-User-Id) */
    private final java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();

    public AuthTestClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public Cookie fetchCsrf() throws Exception {
        MvcResult result = mockMvc.perform(withSession(MockMvcRequestBuilders.get("/api/v1/auth/csrf"))).andReturn();
        updateCsrf(result.getResponse());
        return csrfCookie;
    }

    /** 세션·CSRF 쿠키·헤더를 붙인 JSON POST */
    public MvcResult postJson(String path, Object body) throws Exception {
        if (csrfCookie == null) {
            fetchCsrf();
        }
        MockHttpServletRequestBuilder request = withSession(post(path))
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        MvcResult result = mockMvc.perform(request).andReturn();
        afterResponse(result);
        return result;
    }

    public MvcResult postWithoutBody(String path) throws Exception {
        if (csrfCookie == null) {
            fetchCsrf();
        }
        MvcResult result = mockMvc.perform(withSession(post(path))
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())).andReturn();
        afterResponse(result);
        return result;
    }

    /** 세션·CSRF 쿠키·헤더를 붙인 POST. 본문 JSON 원문을 그대로 보낸다(형식 검증용). */
    public MvcResult postRaw(String path, String rawJson) throws Exception {
        if (csrfCookie == null) {
            fetchCsrf();
        }
        MvcResult result = mockMvc.perform(withSession(post(path))
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawJson)).andReturn();
        afterResponse(result);
        return result;
    }

    /** 세션·CSRF 쿠키·헤더를 붙인 PUT. 본문 JSON 원문을 그대로 보낸다(문자열·null 점수 검증용). */
    public MvcResult putRaw(String path, String rawJson) throws Exception {
        if (csrfCookie == null) {
            fetchCsrf();
        }
        MvcResult result = mockMvc.perform(withSession(put(path))
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawJson)).andReturn();
        afterResponse(result);
        return result;
    }

    /** 세션·CSRF 쿠키·헤더를 붙인 DELETE */
    public MvcResult delete(String path) throws Exception {
        if (csrfCookie == null) {
            fetchCsrf();
        }
        MvcResult result = mockMvc.perform(withSession(MockMvcRequestBuilders.delete(path))
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())).andReturn();
        afterResponse(result);
        return result;
    }

    /** 세션만 붙여 그대로 보낸다. (CSRF 누락 검증용) */
    public MvcResult performWithSessionOnly(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(withSession(request)).andReturn();
        afterResponse(result);
        return result;
    }

    public MvcResult get(String path) throws Exception {
        MvcResult result = mockMvc.perform(withSession(MockMvcRequestBuilders.get(path)))
                .andReturn();
        afterResponse(result);
        return result;
    }

    public MvcResult signup(String email, String password, String nickname) throws Exception {
        return postJson("/api/v1/auth/signup", Map.of("email", email, "password", password, "nickname", nickname));
    }

    public MvcResult login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    public MvcResult logout() throws Exception {
        return postWithoutBody("/api/v1/auth/logout");
    }

    public MockHttpSession session() {
        return session;
    }

    public void useSession(MockHttpSession session) {
        this.session = session;
    }

    public Cookie csrfCookie() {
        return csrfCookie;
    }

    /** 이후 모든 요청에 헤더를 붙인다. value 가 null 이면 그 헤더를 뺀다. */
    public AuthTestClient withHeader(String name, String value) {
        if (value == null) {
            extraHeaders.remove(name);
        } else {
            extraHeaders.put(name, value);
        }
        return this;
    }

    private MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder builder) {
        extraHeaders.forEach(builder::header);
        return session == null ? builder : builder.session(session);
    }

    private void afterResponse(MvcResult result) {
        if (result.getRequest().getSession(false) instanceof MockHttpSession current) {
            session = current.isInvalid() ? null : current;
        } else if (session != null && session.isInvalid()) {
            session = null;
        }
        updateCsrf(result.getResponse());
    }

    /** 응답의 마지막 XSRF-TOKEN Set-Cookie 를 반영한다. (Max-Age=0 이면 토큰 삭제) */
    private void updateCsrf(MockHttpServletResponse response) {
        Cookie latest = null;
        for (Cookie cookie : response.getCookies()) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                latest = cookie;
            }
        }
        if (latest != null) {
            csrfCookie = latest.getMaxAge() == 0 || latest.getValue() == null || latest.getValue().isEmpty() ? null : latest;
        }
    }
}
