package com.manhwanet.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.manhwanet.support.AdminApiIntegrationTest;
import com.manhwanet.support.ApiFixtures;
import com.manhwanet.support.TestDatabaseCleaner;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AdminApiIntegrationTest
class CreatorApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private ApiFixtures fixtures;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
        fixtures = new ApiFixtures(mockMvc, objectMapper);
    }

    @Test
    void registersCreatorWithTrimmedNames() throws Exception {
        fixtures.postJson("/api/v1/admin/creators", Map.of("name", "  한서윤 ", "originalName", " 韓瑞潤　"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/creators/1")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("한서윤"))
                .andExpect(jsonPath("$.data.originalName").value("韓瑞潤"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/creators/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("한서윤"));
    }

    @Test
    void createdAtInResponseMatchesStoredValue() throws Exception {
        String created = fixtures.postJson("/api/v1/admin/creators", Map.of("name", "한서윤"))
                .andReturn().getResponse().getContentAsString();
        String fetched = mockMvc.perform(get("/api/v1/creators/1")).andReturn().getResponse().getContentAsString();

        String createdAt = JsonPath.read(created, "$.data.createdAt");
        String fetchedAt = JsonPath.read(fetched, "$.data.createdAt");
        assertThat(fetchedAt).isEqualTo(createdAt);
    }

    @Test
    void allowsHomonymCreators() throws Exception {
        long first = fixtures.createCreator("한서윤");
        long second = fixtures.createCreator("한서윤");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void rejectsInvalidCreator() throws Exception {
        fixtures.postJson("/api/v1/admin/creators", Map.of("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));

        fixtures.postJson("/api/v1/admin/creators", Map.of("name", "　"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));

        fixtures.postJson("/api/v1/admin/creators", Map.of("name", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));

        Map<String, Object> blankOriginal = new HashMap<>();
        blankOriginal.put("name", "한서윤");
        blankOriginal.put("originalName", "  ");
        fixtures.postJson("/api/v1/admin/creators", blankOriginal)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("originalName"));
    }

    @Test
    void missingCreatorReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/creators/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CREATOR_NOT_FOUND"));
    }

    @Test
    void listsCreatorsWithPaging() throws Exception {
        fixtures.createCreator("A");
        fixtures.createCreator("B");
        fixtures.createCreator("C");

        mockMvc.perform(get("/api/v1/creators").param("size", "2").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].name").value("A"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/creators"))
                .andExpect(jsonPath("$.data.content[0].name").value("C"));
    }
}
