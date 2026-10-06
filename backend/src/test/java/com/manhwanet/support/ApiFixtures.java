package com.manhwanet.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등록 API 호출 헬퍼 (AdminApiIntegrationTest 에서 사용).
 */
public final class ApiFixtures {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiFixtures(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
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
