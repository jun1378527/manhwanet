package com.manhwanet.platform;

import static com.manhwanet.platform.PlatformTestSupport.at;
import static com.manhwanet.platform.PlatformTestSupport.code;
import static com.manhwanet.platform.PlatformTestSupport.fields;
import static com.manhwanet.platform.PlatformTestSupport.insertWork;
import static com.manhwanet.platform.PlatformTestSupport.kakaoPage;
import static com.manhwanet.platform.PlatformTestSupport.listing;
import static com.manhwanet.platform.PlatformTestSupport.longAt;
import static com.manhwanet.platform.PlatformTestSupport.ridi;
import static com.manhwanet.platform.PlatformTestSupport.workVersion;
import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.support.AuthTestClient;
import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.application.AdminAccountService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 작품 플랫폼 연결 관리자 API (#39). 실제 로그인 세션·쿠키 CSRF·PostgreSQL 테스트 DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkListingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private AdminAccountService adminAccountService;

    private AuthTestClient admin;
    private long workId;
    private long otherWorkId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseCleaner.clean(jdbc, environment);
        admin = PlatformTestSupport.admin(mockMvc, objectMapper, adminAccountService);
        workId = insertWork(jdbc, "유미의 세포들");
        otherWorkId = insertWork(jdbc, "다른 작품");
    }

    // ---------- 권한·CSRF ----------

    @Test
    void anonymousUserAndMissingCsrfAreRejected() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/admin/works/" + workId + "/listings")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/admin/listings/lookup?platformCode=RIDI&platformWorkId=1")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        AuthTestClient anonymous = new AuthTestClient(mockMvc, objectMapper);
        MvcResult anonymousPost = anonymous.postJson(listingsPath(workId), ridi(0, "4921002551"));
        assertThat(anonymousPost.getResponse().getStatus()).isEqualTo(401);

        AuthTestClient user = new AuthTestClient(mockMvc, objectMapper);
        user.signup("reader@manhwanet.test", STRONG_PASSWORD, "독자");
        assertThat(user.get(listingsPath(workId)).getResponse().getStatus()).isEqualTo(403);
        MvcResult userPost = user.postJson(listingsPath(workId), ridi(0, "4921002551"));
        assertThat(userPost.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(userPost)).isEqualTo("FORBIDDEN");

        MvcResult noCsrf = admin.performWithSessionOnly(post(listingsPath(workId))
                .contentType(MediaType.APPLICATION_JSON).content(json(ridi(0, "4921002551"))));
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(noCsrf)).isEqualTo("INVALID_CSRF_TOKEN");

        assertThat(count("work_platform_listings")).isZero();
        assertThat(workVersion(jdbc, workId)).isZero();
    }

    // ---------- 등록 ----------

    @Test
    void oneWorkCanHaveListingsOnSeveralPlatforms() throws Exception {
        MvcResult first = admin.postJson(listingsPath(workId), kakaoPage(0, "56309081"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(longAt(first, "$.data.workVersion")).isEqualTo(1);
        assertThat((String) at(first, "$.data.listing.status")).isEqualTo("ACTIVE");
        assertThat((String) at(first, "$.data.listing.url")).isEqualTo("https://page.kakao.com/content/56309081");
        assertThat(longAt(first, "$.data.listing.verifiedBy")).isEqualTo(PlatformTestSupport.adminId(jdbc));
        assertThat((String) at(first, "$.data.listing.firstVerifiedAt")).isEqualTo(PlatformTestSupport.VERIFIED_AT);
        List<String> warnings = at(first, "$.data.warnings");
        assertThat(warnings).contains("PLATFORM_STATUS_UNKNOWN", "AGE_CLASS_UNKNOWN");

        Map<String, Object> second = ridi(1, "4921002551");
        second.put("idScope", "VOLUME_OR_EPISODE");
        assertThat(admin.postJson(listingsPath(workId), second).getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> third = listing(2, "NAVER_WEBTOON", "651673",
                "https://comic.naver.com/webtoon/list?titleId=651673");
        third.put("ageRatingRaw", "12세 이용가");
        MvcResult naver = admin.postJson(listingsPath(workId), third);
        assertThat(naver.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) at(naver, "$.data.listing.ageClass")).isEqualTo("GENERAL");

        MvcResult list = admin.get(listingsPath(workId));
        assertThat(longAt(list, "$.data.workVersion")).isEqualTo(3);
        List<String> platforms = at(list, "$.data.listings[*].platformCode");
        assertThat(platforms).containsExactly("KAKAO_PAGE", "RIDI", "NAVER_WEBTOON");
        assertThat(workVersion(jdbc, workId)).isEqualTo(3);
    }

    @Test
    void samePlatformWorkIdCannotBeLinkedTwice() throws Exception {
        assertThat(admin.postJson(listingsPath(workId), kakaoPage(0, "56309081")).getResponse().getStatus())
                .isEqualTo(201);
        long listingId = jdbc.queryForObject("SELECT id FROM work_platform_listings", Long.class);

        MvcResult other = admin.postJson(listingsPath(otherWorkId), kakaoPage(0, "56309081"));
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(other)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(longAt(other, "$.error.existingWorkId")).isEqualTo(workId);
        assertThat(longAt(other, "$.error.existingListingId")).isEqualTo(listingId);
        assertThat(workVersion(jdbc, otherWorkId)).isZero();

        // 같은 작품에 다시 보내도 409(재시도해도 두 번 생기지 않음)
        MvcResult again = admin.postJson(listingsPath(workId), kakaoPage(1, "56309081"));
        assertThat(code(again)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(count("work_platform_listings")).isEqualTo(1);

        MvcResult lookup = admin.get("/api/v1/admin/listings/lookup?platformCode=KAKAO_PAGE&platformWorkId=56309081");
        assertThat((Boolean) at(lookup, "$.data.linked")).isTrue();
        assertThat(longAt(lookup, "$.data.listing.workId")).isEqualTo(workId);
        MvcResult none = admin.get("/api/v1/admin/listings/lookup?platformCode=RIDI&platformWorkId=56309081");
        assertThat((Boolean) at(none, "$.data.linked")).isFalse();
    }

    @Test
    void sameNumberOnDifferentPlatformsIsADifferentIdentifier() throws Exception {
        assertThat(admin.postJson(listingsPath(workId), kakaoPage(0, "123456")).getResponse().getStatus()).isEqualTo(201);
        assertThat(admin.postJson(listingsPath(otherWorkId), ridi(0, "123456")).getResponse().getStatus()).isEqualTo(201);
        assertThat(count("work_platform_listings")).isEqualTo(2);
    }

    @Test
    void urlIsCheckedBySchemeHostAndPlatformPatternOnly() throws Exception {
        String[][] bad = {
                {"http://page.kakao.com/content/1", "url"},
                {"javascript:alert(1)", "url"},
                {"https://evil.example.com/content/1", "url"},
                {"https://page.kakao.com.evil.com/content/1", "url"},
                {"https://127.0.0.1/content/1", "url"},
                {"https://user@page.kakao.com/content/1", "url"},
                {"https://page.kakao.com:8443/content/1", "url"},
                {"https://page.kakao.com/content/1#top", "url"},
                {"https://page.kakao.com/viewer/1", "url"},
                {"https://page.kakao.com/content/1 2", "url"},
                {"https://page.kakao.com/content/" + "9".repeat(480), "url"},
                {"https://page.kakao.com/content/2", "platformWorkId"},
        };
        for (String[] c : bad) {
            MvcResult result = admin.postJson(listingsPath(workId), listing(0, "KAKAO_PAGE", "1", c[0]));
            assertThat(result.getResponse().getStatus()).as(c[0]).isEqualTo(400);
            assertThat(fields(result)).as(c[0]).contains(c[1]);
        }
        // 호스트 대소문자·기본 포트는 정규화해 저장한다
        MvcResult ok = admin.postJson(listingsPath(workId),
                listing(0, "KAKAO_PAGE", "1", "HTTPS://Page.Kakao.com:443/content/1"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) at(ok, "$.data.listing.url")).isEqualTo("https://page.kakao.com/content/1");

        // URL 형식이 확인되지 않은 플랫폼은 호스트만 검사하고 경고한다
        MvcResult toomics = admin.postJson(listingsPath(workId),
                listing(1, "TOOMICS", "4321", "https://toomics.com/webtoon/episode/toon/4321"));
        assertThat(toomics.getResponse().getStatus()).isEqualTo(201);
        List<String> warnings = at(toomics, "$.data.warnings");
        assertThat(warnings).contains("URL_RULE_UNVERIFIED");
        assertThat(count("work_platform_listings")).isEqualTo(2);
    }

    @Test
    void invalidInputAndLargeIdsAreRejected() throws Exception {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("version", 0);
        MvcResult empty = admin.postJson(listingsPath(workId), missing);
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(empty)).contains("platformCode", "platformWorkId", "url", "idScope", "listingKind",
                "languageCode", "verifiedAt");

        Map<String, Object> badEnums = kakaoPage(0, "1");
        badEnums.put("idScope", "WHOLE");
        badEnums.put("listingKind", "x");
        badEnums.put("languageCode", "not a tag");
        badEnums.put("verifiedAt", "2999-01-01T00:00:00Z");
        badEnums.put("version", "0");
        assertThat(fields(admin.postJson(listingsPath(workId), badEnums)))
                .contains("idScope", "listingKind", "languageCode", "verifiedAt", "version");

        Map<String, Object> spaceId = listing(0, "BOMTOON", "a b", "https://www.bomtoon.com/detail/a b");
        assertThat(fields(admin.postJson(listingsPath(workId), spaceId))).contains("platformWorkId");

        MvcResult unknownPlatform = admin.postJson(listingsPath(workId), listing(0, "NOPE", "1", "https://x.com/1"));
        assertThat(unknownPlatform.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(unknownPlatform)).isEqualTo("PLATFORM_NOT_FOUND");

        MvcResult missingWork = admin.postJson(listingsPath(999_999_999L), kakaoPage(0, "1"));
        assertThat(code(missingWork)).isEqualTo("WORK_NOT_FOUND");
        MvcResult hugeWork = admin.postJson("/api/v1/admin/works/99999999999999999999/listings", kakaoPage(0, "1"));
        assertThat(hugeWork.getResponse().getStatus()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/works/" + Long.MAX_VALUE + "/listings").getResponse().getStatus())
                .isEqualTo(404);
        MvcResult hugeListing = admin.putRaw(listingsPath(workId) + "/" + Long.MAX_VALUE, json(update(0, "ACTIVE")));
        assertThat(code(hugeListing)).isEqualTo("LISTING_NOT_FOUND");

        assertThat(count("work_platform_listings")).isZero();
        assertThat(workVersion(jdbc, workId)).isZero();
    }

    @Test
    void ageRatingIsClassifiedAndUnknownIsNeverGeneral() throws Exception {
        String[][] cases = {
                {"전체 이용가", null, "GENERAL"},
                {"15세 이용가", null, "GENERAL"},
                {"19세 이용가", null, "ADULT"},
                {"청소년 이용불가", null, "ADULT"},
                {"19금", null, "ADULT"},
                {"청불", null, "ADULT"},
                {"18세 미만", null, "UNKNOWN"},
                {"미성인", null, "UNKNOWN"},
                {"성인 아님", null, "UNKNOWN"},
                {"전체 이용불가", null, "UNKNOWN"},
                {"청소년 이용불가 아님", null, "UNKNOWN"},
                {"18세 이용가 아님", null, "UNKNOWN"},
                {null, null, "UNKNOWN"},
                {"독자 판단", null, "UNKNOWN"},
                {null, "UNKNOWN", "UNKNOWN"},
        };
        long version = 0;
        for (int i = 0; i < cases.length; i++) {
            Map<String, Object> body = kakaoPage(version, String.valueOf(1000 + i));
            body.put("ageRatingRaw", cases[i][0]);
            body.put("ageClass", cases[i][1]);
            MvcResult result = admin.postJson(listingsPath(workId), body);
            assertThat(result.getResponse().getStatus()).as(String.join("/", String.valueOf(cases[i][0]))).isEqualTo(201);
            assertThat((String) at(result, "$.data.listing.ageClass")).as(String.valueOf(cases[i][0])).isEqualTo(cases[i][2]);
            version++;
        }
        Map<String, Object> noRawGeneral = kakaoPage(version, "2000");
        noRawGeneral.put("ageClass", "GENERAL");
        assertThat(fields(admin.postJson(listingsPath(workId), noRawGeneral))).contains("ageClass");
        Map<String, Object> unrecognizedGeneral = kakaoPage(version, "2002");
        unrecognizedGeneral.put("ageRatingRaw", "독자 판단");
        unrecognizedGeneral.put("ageClass", "GENERAL");
        assertThat(fields(admin.postJson(listingsPath(workId), unrecognizedGeneral))).contains("ageClass");
        Map<String, Object> mismatch = kakaoPage(version, "2001");
        mismatch.put("ageRatingRaw", "19세");
        mismatch.put("ageClass", "GENERAL");
        assertThat(fields(admin.postJson(listingsPath(workId), mismatch))).contains("ageClass");
        Map<String, Object> adultNoNote = kakaoPage(version, "2003");
        adultNoNote.put("ageRatingRaw", "독자 판단");
        adultNoNote.put("ageClass", "ADULT");
        assertThat(fields(admin.postJson(listingsPath(workId), adultNoNote))).contains("note");
        Map<String, Object> adultNote = kakaoPage(version, "2004");
        adultNote.put("ageRatingRaw", "독자 판단");
        adultNote.put("ageClass", "ADULT");
        adultNote.put("note", "목록에 성인 배지가 있음");
        MvcResult adult = admin.postJson(listingsPath(workId), adultNote);
        assertThat(adult.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) at(adult, "$.data.listing.ageClass")).isEqualTo("ADULT");
        assertThat((String) at(adult, "$.data.listing.note")).isEqualTo("목록에 성인 배지가 있음");
        assertThat(jdbc.queryForObject("SELECT age_rating_raw FROM work_platform_listings WHERE platform_work_id = '1002'",
                String.class)).isEqualTo("19세 이용가");
    }

    @Test
    void closedPlatformRejectsNewListingsButMergingWarns() throws Exception {
        setPlatformStatus("COMICO_KR", "CLOSED", "OFFICIAL", null);
        MvcResult closed = admin.postJson(listingsPath(workId), listing(0, "COMICO_KR", "1", "https://www.comico.kr/titles/1"));
        assertThat(closed.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(closed)).contains("platformCode");

        setPlatformStatus("KAKAO_WEBTOON", "MERGING", "OFFICIAL", "KAKAO_PAGE");
        MvcResult merging = admin.postJson(listingsPath(workId),
                listing(0, "KAKAO_WEBTOON", "198", "https://webtoon.kakao.com/content/%EC%98%A4%EB%AC%B4%EB%9D%BC%EC%9D%B4%EC%8A%A4/198"));
        assertThat(merging.getResponse().getStatus()).isEqualTo(201);
        List<String> warnings = at(merging, "$.data.warnings");
        assertThat(warnings).contains("PLATFORM_MERGING");
    }

    // ---------- 수정·종료·이전 ----------

    @Test
    void listingCanBeEndedReverifiedAndIdempotent() throws Exception {
        long id = create(workId, kakaoPage(0, "56309081"));

        Map<String, Object> end = update(1, "ENDED");
        end.put("lastVerifiedAt", "2026-10-12T01:00:00Z");
        end.put("note", "작품 페이지가 내려감");
        MvcResult ended = admin.putRaw(listingPath(workId, id), json(end));
        assertThat(ended.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(ended, "$.data.listing.status")).isEqualTo("ENDED");
        assertThat((String) at(ended, "$.data.listing.lastVerifiedAt")).isEqualTo("2026-10-12T01:00:00Z");
        assertThat(longAt(ended, "$.data.workVersion")).isEqualTo(2);

        // 같은 내용은 version 을 올리지 않는다
        end.put("version", 2);
        MvcResult same = admin.putRaw(listingPath(workId, id), json(end));
        assertThat(longAt(same, "$.data.workVersion")).isEqualTo(2);
        assertThat(workVersion(jdbc, workId)).isEqualTo(2);

        Map<String, Object> early = update(2, "ENDED");
        early.put("lastVerifiedAt", "2020-01-01T00:00:00Z");
        assertThat(fields(admin.putRaw(listingPath(workId, id), json(early)))).contains("lastVerifiedAt");

        // 다른 작품 경로로는 찾을 수 없다
        MvcResult wrongWork = admin.putRaw(listingPath(otherWorkId, id), json(update(0, "ACTIVE")));
        assertThat(code(wrongWork)).isEqualTo("LISTING_NOT_FOUND");
        assertThat(count("work_platform_listings")).isEqualTo(1);
    }

    @Test
    void movedListingPointsToAnotherListingOfTheSameWorkWithoutCycles() throws Exception {
        long a = create(workId, kakaoPage(0, "100"));
        long b = create(workId, kakaoPage(1, "200"));
        long foreign = create(otherWorkId, kakaoPage(0, "300"));

        Map<String, Object> self = update(2, "MOVED", "100");
        self.put("movedToListingId", a);
        assertThat(fields(admin.putRaw(listingPath(workId, a), json(self)))).contains("movedToListingId");

        Map<String, Object> cross = update(2, "MOVED", "100");
        cross.put("movedToListingId", foreign);
        assertThat(fields(admin.putRaw(listingPath(workId, a), json(cross)))).contains("movedToListingId");

        Map<String, Object> noTarget = update(2, "MOVED", "100");
        assertThat(fields(admin.putRaw(listingPath(workId, a), json(noTarget)))).contains("movedToListingId");

        Map<String, Object> targetOnActive = update(2, "ACTIVE", "100");
        targetOnActive.put("movedToListingId", b);
        assertThat(fields(admin.putRaw(listingPath(workId, a), json(targetOnActive)))).contains("movedToListingId");

        Map<String, Object> aToB = update(2, "MOVED", "100");
        aToB.put("movedToListingId", b);
        MvcResult moved = admin.putRaw(listingPath(workId, a), json(aToB));
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(longAt(moved, "$.data.listing.movedToListingId")).isEqualTo(b);

        // B→A 는 순환: A 가 이미 MOVED 이므로 거절
        Map<String, Object> bToA = update(3, "MOVED", "200");
        bToA.put("movedToListingId", a);
        MvcResult cycle = admin.putRaw(listingPath(workId, b), json(bToA));
        assertThat(cycle.getResponse().getStatus()).isEqualTo(400);
        assertThat(fields(cycle)).contains("movedToListingId");
        assertThat(jdbc.queryForObject("SELECT status FROM work_platform_listings WHERE id = ?", String.class, b))
                .isEqualTo("ACTIVE");
        assertThat(workVersion(jdbc, workId)).isEqualTo(3);
    }

    @Test
    void moveCreatesNewListingAndMarksOldOneAtomically() throws Exception {
        long old = create(workId, listing(0, "LEZHIN", "highschoolboy", "https://www.lezhin.com/ko/comic/highschoolboy"));
        Map<String, Object> newListing = listing(0, "LEZHIN", "highschoolboy_15",
                "https://www.lezhin.com/ko/comic/highschoolboy_15");
        newListing.remove("version");
        newListing.put("listingKind", "EDITION");
        newListing.put("ageRatingRaw", "15세 이용가");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("newListing", newListing);
        body.put("reason", "슬러그 변경으로 이전");

        MvcResult moved = admin.postJson(listingPath(workId, old) + "/move", body);
        assertThat(moved.getResponse().getStatus()).isEqualTo(201);
        long newId = longAt(moved, "$.data.listing.id");
        assertThat(longAt(moved, "$.data.workVersion")).isEqualTo(2);
        assertThat((String) at(moved, "$.data.movedFrom.status")).isEqualTo("MOVED");
        assertThat(longAt(moved, "$.data.movedFrom.movedToListingId")).isEqualTo(newId);
        assertThat((String) at(moved, "$.data.listing.ageClass")).isEqualTo("GENERAL");

        // 이미 MOVED 인 연결은 다시 이전할 수 없다
        body.put("version", 2);
        newListing.put("platformWorkId", "x2");
        newListing.put("url", "https://www.lezhin.com/ko/comic/x2");
        assertThat(fields(admin.postJson(listingPath(workId, old) + "/move", body))).contains("status");

        // 새 연결이 이미 다른 작품에 있으면 기존 연결도 바뀌지 않는다(롤백)
        long other = create(otherWorkId, listing(0, "LEZHIN", "taken", "https://www.lezhin.com/ko/comic/taken"));
        newListing.put("platformWorkId", "taken");
        newListing.put("url", "https://www.lezhin.com/ko/comic/taken");
        MvcResult taken = admin.postJson(listingPath(workId, newId) + "/move", body);
        assertThat(code(taken)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(longAt(taken, "$.error.existingListingId")).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT status FROM work_platform_listings WHERE id = ?", String.class, newId))
                .isEqualTo("ACTIVE");
        assertThat(workVersion(jdbc, workId)).isEqualTo(2);
    }

    @Test
    void staleWorkVersionIsRejectedAndSharedWithWorkEdits() throws Exception {
        long id = create(workId, kakaoPage(0, "56309081"));

        MvcResult stale = admin.postJson(listingsPath(workId), ridi(0, "1"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(longAt(stale, "$.error.currentVersion")).isEqualTo(1);

        MvcResult staleUpdate = admin.putRaw(listingPath(workId, id), json(update(0, "ENDED")));
        assertThat(code(staleUpdate)).isEqualTo("WORK_VERSION_CONFLICT");

        // 작품 수정(#38) 화면이 version 0 을 들고 있으면 연결 추가 뒤 409 가 난다(작품당 버전 하나)
        Map<String, Object> workEdit = new LinkedHashMap<>();
        workEdit.put("version", 0);
        workEdit.put("reason", "제목 수정");
        workEdit.put("evidence", List.of(Map.of("field", "TITLE", "sourceType", "STAFF", "verifiedAt", "2026-10-12T00:00:00Z")));
        workEdit.put("workFormat", "WEBTOON");
        workEdit.put("titles", List.of(Map.of("title", "새 제목", "languageCode", "ko", "primary", true)));
        long creatorId = jdbc.queryForObject("SELECT creator_id FROM work_creators WHERE work_id = ?", Long.class, workId);
        workEdit.put("creators", List.of(Map.of("creatorId", creatorId, "role", "STORY")));
        assertThat(code(admin.putRaw("/api/v1/admin/works/" + workId, json(workEdit)))).isEqualTo("WORK_VERSION_CONFLICT");
        assertThat(count("work_platform_listings")).isEqualTo(1);
    }

    @Test
    void ratingsReviewsAndWorkIdsArePreserved() throws Exception {
        long reader = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname, role) VALUES ('r@manhwanet.test', '{noop}x', '독자', 'USER')
                RETURNING id""", Long.class);
        jdbc.update("INSERT INTO work_ratings (user_id, work_id, score_half) VALUES (?, ?, 8)", reader, workId);
        jdbc.update("INSERT INTO work_reviews (user_id, work_id, body, contains_spoiler) VALUES (?, ?, '연결 전후로 남아야 하는 리뷰입니다.', FALSE)",
                reader, workId);
        List<Map<String, Object>> ratings = jdbc.queryForList("SELECT * FROM work_ratings ORDER BY id");
        List<Map<String, Object>> reviews = jdbc.queryForList("SELECT * FROM work_reviews ORDER BY id");
        String titleBefore = jdbc.queryForObject("SELECT title FROM work_titles WHERE work_id = ?", String.class, workId);

        long id = create(workId, kakaoPage(0, "56309081"));
        admin.putRaw(listingPath(workId, id), json(update(1, "ENDED")));
        create(workId, ridi(2, "777"));

        assertThat(jdbc.queryForList("SELECT * FROM work_ratings ORDER BY id")).isEqualTo(ratings);
        assertThat(jdbc.queryForList("SELECT * FROM work_reviews ORDER BY id")).isEqualTo(reviews);
        assertThat(jdbc.queryForObject("SELECT title FROM work_titles WHERE work_id = ?", String.class, workId))
                .isEqualTo(titleBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM works", Integer.class)).isEqualTo(2);
        // 공개 상세는 그대로 열리고 연결 정보는 아직 노출하지 않는다(#42)
        String detail = mockMvc.perform(get("/api/v1/works/" + workId)).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("56309081").doesNotContain("listings");
    }

    @Test
    void urlAliasesAreNormalizedAndHiddenCharactersRejected() throws Exception {
        String[] bad = {
                "https://page.kakao.com/content/1\u200b",
                "https://page.kakao.com/x/../content/1",
                "https://page.kakao.com/x/%2e%2e/content/1",
                "https://page.kakao.com/content/1?utm_source=x",
        };
        for (String url : bad) {
            MvcResult result = admin.postJson(listingsPath(workId), listing(0, "KAKAO_PAGE", "1", url));
            assertThat(result.getResponse().getStatus()).as(url).isEqualTo(400);
            assertThat(fields(result)).as(url).contains("url");
        }
        // 예약되지 않은 문자의 퍼센트 인코딩은 풀어서 저장한다
        MvcResult ok = admin.postJson(listingsPath(workId), listing(0, "KAKAO_PAGE", "1", "https://page.kakao.com/%63ontent/%31"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) at(ok, "$.data.listing.url")).isEqualTo("https://page.kakao.com/content/1");
        // 같은 외부 ID 는 URL 표기가 달라도 다른 작품에 다시 연결할 수 없다
        MvcResult alias = admin.postJson(listingsPath(otherWorkId), listing(0, "KAKAO_PAGE", "1", "https://page.kakao.com/content/1"));
        assertThat(code(alias)).isEqualTo("PLATFORM_LISTING_ALREADY_LINKED");
        assertThat(count("work_platform_listings")).isEqualTo(1);
    }

    @Test
    void ruleChangeDoesNotBlockEndingAListingWithItsSavedUrl() throws Exception {
        long id = create(workId, kakaoPage(0, "56309081"));
        jdbc.update("UPDATE platforms SET work_url_pattern = ? WHERE code = 'KAKAO_PAGE'",
                "^https://page\\.kakao\\.com/series/(?<id>[0-9]+)$");
        MvcResult ended = admin.putRaw(listingPath(workId, id), json(update(1, "ENDED")));
        assertThat(ended.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) at(ended, "$.data.listing.status")).isEqualTo("ENDED");
        // URL 을 바꾸면 새 규칙으로 검사한다
        Map<String, Object> changed = update(2, "ENDED");
        changed.put("url", "https://page.kakao.com/content/56309081?x=1");
        assertThat(fields(admin.putRaw(listingPath(workId, id), json(changed)))).contains("url");
    }

    @Test
    void movedChainIsRepointedToTheLatestTarget() throws Exception {
        long a = create(workId, kakaoPage(0, "100"));
        long b = create(workId, kakaoPage(1, "200"));
        long c = create(workId, kakaoPage(2, "300"));
        Map<String, Object> aToB = update(3, "MOVED", "100");
        aToB.put("movedToListingId", b);
        assertThat(admin.putRaw(listingPath(workId, a), json(aToB)).getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> bToC = update(4, "MOVED", "200");
        bToC.put("movedToListingId", c);
        assertThat(admin.putRaw(listingPath(workId, b), json(bToC)).getResponse().getStatus()).isEqualTo(200);
        // A 는 MOVED 인 B 대신 최종 대상 C 를 가리킨다(MOVED 연결을 가리키는 연결이 남지 않음)
        assertThat(jdbc.queryForObject("SELECT moved_to_listing_id FROM work_platform_listings WHERE id = ?", Long.class, a))
                .isEqualTo(c);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM work_platform_listings l JOIN work_platform_listings t ON t.id = l.moved_to_listing_id
                WHERE t.status = 'MOVED'""", Integer.class)).isZero();
        assertThat(workVersion(jdbc, workId)).isEqualTo(5);
    }

    // ---------- 도우미 ----------

    private long create(long work, Map<String, Object> body) throws Exception {
        MvcResult result = admin.postJson(listingsPath(work), body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return longAt(result, "$.data.listing.id");
    }

    private Map<String, Object> update(long version, String status) {
        return update(version, status, "56309081");
    }

    private Map<String, Object> update(long version, String status, String kakaoPageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("url", "https://page.kakao.com/content/" + kakaoPageId);
        body.put("idScope", "SERIES");
        body.put("listingKind", "MAIN");
        body.put("languageCode", "ko");
        body.put("status", status);
        body.put("reason", "연결 상태 확인");
        return body;
    }

    private void setPlatformStatus(String code, String status, String basis, String mergedInto) {
        jdbc.update("""
                UPDATE platforms SET status = ?, status_basis = ?, merged_into = ?,
                    status_evidence_url = CASE WHEN ? = 'UNVERIFIED' THEN NULL ELSE 'https://example.com/notice' END
                WHERE code = ?""", status, basis, mergedInto, basis, code);
    }

    private static String listingsPath(long work) {
        return "/api/v1/admin/works/" + work + "/listings";
    }

    private static String listingPath(long work, long listing) {
        return listingsPath(work) + "/" + listing;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
