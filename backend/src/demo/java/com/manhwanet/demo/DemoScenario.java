package com.manhwanet.demo;

import com.manhwanet.collection.application.CollectionAdminService;
import com.manhwanet.collection.application.CrawlRunActiveException;
import com.manhwanet.collection.collector.core.CollectorEngine;
import com.manhwanet.collection.collector.core.RunReport;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.dto.CollectionPolicyResponse;
import com.manhwanet.collection.dto.UpdateCollectionPolicyRequest;
import com.manhwanet.collection.review.StagingReviewRequest;
import com.manhwanet.collection.review.StagingReviewService;
import com.manhwanet.collection.review.StagingReviewView;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.platform.application.PlatformAdminService;
import com.manhwanet.platform.dto.PlatformResponse;
import com.manhwanet.platform.dto.UpdatePlatformRequest;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.user.application.UserService;
import com.manhwanet.user.domain.EmailAddresses;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.dto.SignupRequest;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.ChangeVisibilityRequest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 포트폴리오 데모 단계(#67). 모든 데이터는 기존 서비스를 거쳐 만든다(핵심 흐름을 DB 직접 입력으로 우회하지 않음).
 * <ol>
 *   <li>accounts: 관리자·독자 계정(AdminAccountService·UserService.signup). 비밀번호는 환경변수 또는 실행 시 생성</li>
 *   <li>collect: 데모 호스트 허용(PlatformAdminService) → 수집 설정 ENABLED(CollectionAdminService) → 127.0.0.1 모의 서버로
 *       수집(CollectorEngine) → staging</li>
 *   <li>review: 후보 확인(StagingReviewService) → CREATE_WORK → 작품이 처음부터 HIDDEN 으로 등록</li>
 *   <li>publish: 관리자가 명시적으로 PUBLIC 전환(WorkService.changeVisibility, version·사유·변경 기록)</li>
 * </ol>
 * 읽기(현황 표시)만 JDBC 로 한다. 데이터를 지우는 동작은 없다(초기화는 별도 스크립트, 데모 DB 만).
 */
@Component
@Profile("demo-seed")
public class DemoScenario {

    public static final String ADMIN_EMAIL = "demo-admin@manhwanet.test";
    public static final String READER_EMAIL = "demo-reader@manhwanet.test";
    static final String NOTE = "[DEMO] 포트폴리오 시연용 가상 데이터(실제 작품 아님)";
    private static final String OWNER = "demo-collector";
    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final AdminAccountService adminAccountService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final PlatformAdminService platformAdminService;
    private final CollectionAdminService collectionAdminService;
    private final CollectorEngine collectorEngine;
    private final StagingReviewService reviewService;
    private final WorkService workService;
    private final DemoCollectorConfig.DemoPort demoPort;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public DemoScenario(AdminAccountService adminAccountService, UserService userService, UserRepository userRepository,
            PlatformAdminService platformAdminService, CollectionAdminService collectionAdminService,
            CollectorEngine collectorEngine, StagingReviewService reviewService, WorkService workService,
            DemoCollectorConfig.DemoPort demoPort, JdbcTemplate jdbc, Environment environment) {
        this.adminAccountService = adminAccountService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.platformAdminService = platformAdminService;
        this.collectionAdminService = collectionAdminService;
        this.collectorEngine = collectorEngine;
        this.reviewService = reviewService;
        this.workService = workService;
        this.demoPort = demoPort;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    /** 관리자·독자 계정. 이미 있으면 그대로 두고 비밀번호도 바꾸지 않는다 */
    public void accounts(Consumer<String> out) {
        if (userRepository.findByEmail(EmailAddresses.normalize(ADMIN_EMAIL)).isEmpty()) {
            Secret secret = secret("MANHWANET_DEMO_ADMIN_PASSWORD");
            adminAccountService.createAdmin(ADMIN_EMAIL, secret.value(), "[DEMO] 관리자");
            out.accept("관리자 계정 생성: " + ADMIN_EMAIL + secret.describe());
        } else {
            out.accept("관리자 계정 있음: " + ADMIN_EMAIL + " (비밀번호 바꾸지 않음)");
        }
        if (userRepository.findByEmail(EmailAddresses.normalize(READER_EMAIL)).isEmpty()) {
            Secret secret = secret("MANHWANET_DEMO_READER_PASSWORD");
            userService.signup(new SignupRequest(READER_EMAIL, secret.value(), "데모독자"));
            out.accept("독자 계정 생성(회원 가입 서비스): " + READER_EMAIL + secret.describe());
        } else {
            out.accept("독자 계정 있음: " + READER_EMAIL + " (비밀번호 바꾸지 않음)");
        }
    }

    /** 모의 수집: 데모 호스트 허용 → 수집 설정 ENABLED → 127.0.0.1 모의 서버 수집 */
    public RunReport collect(Consumer<String> out) throws Exception {
        long adminId = adminId();
        PlatformResponse platform = platformAdminService.getPlatform(DemoAdapter.PLATFORM);
        if (!platform.allowedHosts().contains(DemoAdapter.HOST)) {
            List<String> hosts = new ArrayList<>(platform.allowedHosts());
            hosts.add(DemoAdapter.HOST);
            platformAdminService.updateRules(DemoAdapter.PLATFORM, new UpdatePlatformRequest(new JsonInteger(platform.version()),
                    platform.name(), platform.operatorName(), platform.defaultIdScope().name(), platform.workUrlPattern(),
                    platform.urlRuleBasis() == null ? null : platform.urlRuleBasis().name(), platform.urlRuleCheckedOn(),
                    platform.termsCheckedOn(), hosts, NOTE + ": 데모 호스트 허용(데모 DB 전용)"), adminId);
            out.accept("플랫폼 " + DemoAdapter.PLATFORM + " 허용 호스트에 데모 호스트 추가: " + DemoAdapter.HOST + " (데모 DB 에서만)");
        }
        CollectionPolicyResponse policy = collectionAdminService.getPolicy(DemoAdapter.PLATFORM);
        if (!"ENABLED".equals(policy.collectionPolicy())) {
            collectionAdminService.updatePolicy(DemoAdapter.PLATFORM, new UpdateCollectionPolicyRequest(new JsonInteger(policy.version()),
                    "ENABLED", policy.synopsisPolicy(), policy.coverPolicy(), NOTE, null, null,
                    new JsonInteger((long) policy.rateIntervalMs()), new JsonInteger((long) policy.dailyRequestCap()),
                    NOTE + ": 데모 수집 설정"), adminId);
            out.accept("수집 설정 " + DemoAdapter.PLATFORM + " → ENABLED (데모 DB 에서만, 변경 기록 남음)");
        }
        try (DemoMockSite site = new DemoMockSite(DemoAdapter.BASE)) {
            demoPort.set(site.port());
            out.accept("모의 플랫폼 서버 127.0.0.1:" + site.port() + " 시작 (실제 사이트 요청·표지 다운로드 없음). "
                    + "요청 간격 5초 규칙 때문에 30초쯤 걸립니다");
            RunReport report;
            try {
                report = collectorEngine.start(DemoAdapter.PLATFORM, RunKind.FULL, OWNER);
            } catch (CrawlRunActiveException e) {
                out.accept("남아 있던 실행 #" + e.getActiveRunId() + " 을 이어 갑니다");
                report = collectorEngine.resume(e.getActiveRunId(), OWNER);
            }
            out.accept("수집 실행 #" + report.runId() + ": " + report.status() + (report.halt() == null ? "" : " (" + report.halt() + ")")
                    + ", 모의 서버 요청 " + site.requests() + "건");
            return report;
        }
    }

    /** 검수: 후보 확인 뒤 새 작품으로 등록(처음부터 HIDDEN). 열린 후보가 있는 항목은 자동으로 판단하지 않고 건너뛴다 */
    public List<Long> review(Consumer<String> out) {
        long adminId = adminId();
        List<Long> created = new ArrayList<>();
        List<Long> pending = jdbc.queryForList("""
                SELECT id FROM staging_listings WHERE platform_code = ? AND review_status IN ('NEW', 'CANDIDATES')
                ORDER BY id""", Long.class, DemoAdapter.PLATFORM);
        if (pending.isEmpty()) {
            out.accept("검수할 수집 대기 항목이 없습니다(먼저 collect).");
        }
        Instant verifiedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (long stagingId : pending) {
            StagingReviewView view = reviewService.regenerateCandidates(stagingId, adminId);
            long open = view.candidates().stream().filter(c -> c.status().equals("OPEN")).count();
            if (open > 0) {
                out.accept("staging #" + stagingId + " `" + view.titleClean() + "`: 열린 중복 후보 " + open
                        + "건 → 자동으로 판단하지 않고 건너뜀(API 로 직접 검수)");
                continue;
            }
            List<StagingReviewRequest.Credit> credits = view.credits().stream()
                    .filter(c -> !c.role().equals("UNSPECIFIED"))
                    .map(c -> new StagingReviewRequest.Credit(null, c.nameRaw(), null, null, c.role())).toList();
            StagingReviewRequest request = new StagingReviewRequest("CREATE_WORK", new JsonInteger(view.revision()),
                    NOTE + ": 검수자가 모의 수집 결과를 확인하고 새 작품으로 등록", List.of(), null, null,
                    new StagingReviewRequest.NewWork("WEBTOON", "KR", null, credits,
                            List.of(staff("TITLE", verifiedAt), staff("CREDIT", verifiedAt))),
                    new StagingReviewRequest.ListingOptions("MAIN", null, verifiedAt, NOTE, List.of(staff(null, verifiedAt))));
            try {
                StagingReviewView done = reviewService.review(stagingId, request, adminId);
                Long workId = done.decisions().isEmpty() ? null : done.decisions().get(0).workId();
                created.add(workId);
                out.accept("staging #" + stagingId + " `" + view.titleClean() + "` (" + view.ageRatingRaw() + ") → 후보 없음 → CREATE_WORK → 작품 #"
                        + workId + " 를 HIDDEN 으로 등록. 공개 상세: " + publicStatus(workId));
            } catch (BusinessException e) {
                out.accept("staging #" + stagingId + " 등록 실패: " + e.getErrorCode() + " " + e.getMessage());
            }
        }
        return created;
    }

    /** 공개: 데모 검수로 만든 HIDDEN 작품을 관리자가 명시적으로 PUBLIC 전환 */
    public List<Long> publish(Consumer<String> out) {
        long adminId = adminId();
        List<Long> works = jdbc.queryForList("""
                SELECT DISTINCT d.work_id FROM staging_review_decisions d JOIN works w ON w.id = d.work_id
                JOIN staging_listings s ON s.id = d.staging_id
                WHERE d.decision = 'CREATE_WORK' AND w.visibility = 'HIDDEN' AND s.platform_code = ? ORDER BY d.work_id""",
                Long.class, DemoAdapter.PLATFORM);
        if (works.isEmpty()) {
            out.accept("공개할 HIDDEN 데모 작품이 없습니다(먼저 review, 또는 이미 공개됨).");
        }
        List<Long> published = new ArrayList<>();
        for (long workId : works) {
            long version = workService.getAdminWork(workId).version();
            try {
                workService.changeVisibility(workId, new ChangeVisibilityRequest(new JsonInteger(version), "PUBLIC",
                        NOTE + ": 검수 완료, 등급 GENERAL 확인 뒤 공개"), adminId);
                published.add(workId);
                out.accept("작품 #" + workId + " HIDDEN → PUBLIC (version " + version + " → " + (version + 1) + ", 변경 기록·사유 남김). 공개 상세: "
                        + publicStatus(workId));
            } catch (BusinessException e) {
                out.accept("작품 #" + workId + " 공개 거절: " + e.getErrorCode() + " " + e.getMessage());
            }
        }
        return published;
    }

    /** 현황(읽기만) */
    public void status(Consumer<String> out) {
        out.accept("DB: " + jdbc.queryForObject("SELECT current_database()", String.class));
        out.accept("계정: 관리자 " + exists(ADMIN_EMAIL) + ", 독자 " + exists(READER_EMAIL));
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT s.platform_work_id, s.title_clean, s.review_status, s.revision, l.work_id, w.visibility
                FROM staging_listings s
                LEFT JOIN work_platform_listings l ON l.id = s.linked_listing_id
                LEFT JOIN works w ON w.id = l.work_id
                WHERE s.platform_code = ? ORDER BY s.id""", DemoAdapter.PLATFORM)) {
            out.accept("staging " + row.get("platform_work_id") + " `" + row.get("title_clean") + "` " + row.get("review_status")
                    + " rev " + row.get("revision") + (row.get("work_id") == null ? "" : " → 작품 #" + row.get("work_id") + " " + row.get("visibility")));
        }
        long publicWorks = workService.getWorks(PageRequest.of(0, 1)).totalElements();
        out.accept("공개 목록 작품 수: " + publicWorks);
    }

    private String publicStatus(Long workId) {
        if (workId == null) {
            return "-";
        }
        try {
            workService.getWork(workId);
            return "200(보임)";
        } catch (BusinessException e) {
            return e.getErrorCode() == ErrorCode.WORK_NOT_FOUND ? "404(숨김: 없는 작품과 같음)" : e.getErrorCode().name();
        }
    }

    private String exists(String email) {
        return userRepository.findByEmail(EmailAddresses.normalize(email)).isPresent() ? "있음" : "없음";
    }

    private long adminId() {
        return userRepository.findByEmail(EmailAddresses.normalize(ADMIN_EMAIL))
                .orElseThrow(() -> new IllegalStateException("데모 관리자 계정이 없습니다. 먼저 accounts 단계를 실행하세요."))
                .getId();
    }

    private static EvidenceRequest staff(String field, Instant verifiedAt) {
        return new EvidenceRequest(field, null, null, "STAFF", null, null, verifiedAt, NOTE);
    }

    record Secret(String value, boolean generated) {
        String describe() {
            return generated
                    ? "\n    비밀번호(실행 시 생성, 이번에만 표시하고 어디에도 저장하지 않음): " + value
                    : " (비밀번호: 환경변수에서 읽음, 표시하지 않음)";
        }
    }

    private Secret secret(String envName) {
        String fromEnv = environment.getProperty(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new Secret(fromEnv, false);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return new Secret(sb.toString(), true);
    }
}
