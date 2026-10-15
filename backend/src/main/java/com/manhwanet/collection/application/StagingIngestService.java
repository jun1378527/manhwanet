package com.manhwanet.collection.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.application.CollectedListing.CollectedCredit;
import com.manhwanet.collection.domain.CollectionPolicyRepository;
import com.manhwanet.collection.domain.CreditRole;
import com.manhwanet.collection.domain.DetailStatus;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.collection.domain.SerialStatus;
import com.manhwanet.collection.domain.StagingCreditRow;
import com.manhwanet.collection.domain.StagingRepository;
import com.manhwanet.collection.domain.StagingRow;
import com.manhwanet.collection.domain.SynopsisPolicy;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.AgeRatingClassifier;
import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.platform.domain.PlatformRow;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.work.domain.LanguageTags;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 결과를 staging 에 반영한다 (#45, 설계 §6·§7.4·§8.2). 공개 작품·연결로는 등록하지 않는다(#51).
 * <ol>
 *   <li>입력 검증(400): 실행의 플랫폼과 같은지, ID·URL(플랫폼 허용 호스트)·길이·역할·발견 경로 형식.</li>
 *   <li>실행 잠금 확인(공유 잠금): 잠금을 쥔 프로세스만 쓴다. 넘겨받기·중단 확정과 직렬화된다.</li>
 *   <li>식별 키 찾기: 대표 ID 와 별칭은 한 식별 공간이다. 별칭 → (플랫폼, 외부 ID) → 없으면 새 행.
 *       다른 행에 연결되는 키는 409 이고 그 반영 전체가 롤백된다.</li>
 *   <li>행 잠금 뒤 다음 값을 계산: OK 는 내용 통째, GATED·SKIPPED_ADULT 는 목록 값만,
 *       NOT_FOUND·PARSE_FAILED·FAILED 는 내용·등급 신호·필드 출처를 유지하고 실패 정보만 남긴다.</li>
 *   <li>등급: 원래 표기와 명시 신호로만 정한다. 명시 신호가 없거나 충돌하면 UNKNOWN(GENERAL 로 올리지 않음).</li>
 *   <li>바뀐 필드의 이전·이후 값을 같은 트랜잭션에서 (staging, 실행)당 한 줄로 남긴다. 재개로 같은 실행이 다시 써도 줄이 늘지 않고,
 *       처음 이전 값은 유지한다. 소개문은 해시·길이만 남긴다.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class StagingIngestService {

    static final int MAX_TITLE_RAW = 500;
    static final int MAX_TITLE_CLEAN = 255;
    static final int MAX_GENRES = 50;
    static final int MAX_GENRE_LENGTH = 100;
    static final int MAX_SIGNALS = 30;
    static final int MAX_SYNOPSIS = 20_000;
    static final int MAX_JSON_BYTES = 65_536;
    static final int MAX_CREDITS = 50;
    static final int MAX_ALIASES = 50;
    private static final Pattern VISIBLE_ASCII = Pattern.compile("^[!-~]{1,100}$");
    private static final Pattern SOURCE_KEY = Pattern.compile("^[a-z0-9][a-z0-9:_.-]{0,99}$");
    private static final Pattern SIGNAL_SOURCE = Pattern.compile("^[a-z0-9][a-z0-9:_.-]{0,49}$");
    private static final Set<DetailStatus> LIST_ONLY = EnumSet.of(DetailStatus.GATED, DetailStatus.SKIPPED_ADULT);
    private static final Set<DetailStatus> FAILURES = EnumSet.of(DetailStatus.NOT_FOUND, DetailStatus.PARSE_FAILED,
            DetailStatus.FAILED);
    private static final Set<DetailStatus> SEEN_ON_FAILURE = EnumSet.of(DetailStatus.PARSE_FAILED);
    /** 정렬된 키로 직렬화해 같은 내용이면 같은 해시가 나오게 한다 */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final StagingRepository stagingRepository;
    private final PlatformRepository platformRepository;
    private final CollectionPolicyRepository policyRepository;
    private final CrawlRunService runService;

    public StagingIngestService(StagingRepository stagingRepository, PlatformRepository platformRepository,
            CollectionPolicyRepository policyRepository, CrawlRunService runService) {
        this.stagingRepository = stagingRepository;
        this.platformRepository = platformRepository;
        this.policyRepository = policyRepository;
        this.runService = runService;
    }

    @Transactional
    public IngestResult ingest(long runId, String owner, CollectedListing input) {
        if (input == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "수집 결과가 비어 있습니다.");
        }
        RunRow run = runService.requireLeaseForWrite(runId, owner, true);
        PlatformRow platform = platformRepository.findByCode(run.platformCode()).orElseThrow();
        Valid v = validate(input, platform);
        SynopsisPolicy synopsisPolicy = policyRepository.findByCode(platform.code()).orElseThrow().synopsisPolicy();

        List<String> identityIds = new ArrayList<>();
        identityIds.add(v.platformWorkId());
        identityIds.addAll(v.aliasIds);
        stagingRepository.lockIdentity(platform.code(), identityIds);

        boolean byAlias = false;
        Optional<StagingRepository.IdentityKey> identity = stagingRepository.findIdentity(platform.code(), v.platformWorkId());
        boolean created = false;
        long stagingId;
        if (identity.isPresent()) {
            stagingId = identity.get().stagingId();
            byAlias = "ALIAS".equals(identity.get().role());
        } else {
            Optional<Long> foundAlias = stagingRepository.findAliasTarget(platform.code(), v.platformWorkId());
            if (foundAlias.isPresent()) {
                stagingId = foundAlias.get();
                byAlias = true;
            } else {
                Optional<Long> inserted = stagingRepository.insertIfAbsent(platform.code(), v.platformWorkId(), v.url, runId);
                created = inserted.isPresent();
                stagingId = inserted.orElseGet(() -> stagingRepository.findIdByKey(platform.code(), v.platformWorkId())
                        .orElseThrow());
            }
        }
        StagingRow current = stagingRepository.lockById(stagingId).orElseThrow();
        if (byAlias && v.aliasIds.contains(current.platformWorkId())) {
            throw aliasConflict(v.platformWorkId(), current.platformWorkId());
        }
        claimIdentity(platform.code(), current.platformWorkId(), stagingId, true);
        List<StagingCreditRow> currentCredits = stagingRepository.findCredits(stagingId);

        Next next = next(current, currentCredits, v, synopsisPolicy, runId, created);
        Map<String, Object> before = content(current, currentCredits);
        Map<String, Object> after = content(next.row, next.credits);
        String hash = sha256(canonical(after));
        boolean contentChanged = !Objects.equals(current.contentHash(), hash);
        StagingRow toSave = withHash(next.row, hash);

        Map<String, Map<String, Object>> diff = new TreeMap<>();
        for (String field : after.keySet()) {
            if (!canonical(before.get(field)).equals(canonical(after.get(field)))) {
                diff.put(field, pair(before.get(field), after.get(field)));
            }
        }
        if (current.detailStatus() != toSave.detailStatus()) {
            diff.put("detailStatus", pair(current.detailStatus().name(), toSave.detailStatus().name()));
        }

        stagingRepository.update(stagingId, toSave, next.seen, contentChanged, runId);
        if (!canonical(before.get("credits")).equals(canonical(after.get("credits")))) {
            stagingRepository.replaceCredits(stagingId, next.credits);
        }
        if (v.discoverySource != null) {
            stagingRepository.upsertDiscovery(stagingId, v.discoverySource, runId);
        }
        List<String> aliases = v.aliasIds.stream().filter(alias -> !alias.equals(current.platformWorkId())).distinct()
                .sorted().toList();
        for (String alias : aliases) {
            claimIdentity(platform.code(), alias, stagingId, false);
            long target = stagingRepository.insertAlias(platform.code(), alias, stagingId);
            if (target != stagingId) {
                throw aliasConflict(alias, current.platformWorkId());
            }
        }
        if (!diff.isEmpty()) {
            stagingRepository.upsertChange(stagingId, runId, json(mergeChange(stagingId, runId, diff)));
        }
        return new IngestResult(stagingId, created, contentChanged, List.copyOf(diff.keySet()), byAlias);
    }

    /** 대표 ID 또는 별칭을 이 행의 식별 공간에 넣는다. 다른 행이 이미 갖고 있으면 409 */
    private void claimIdentity(String platform, String externalId, long stagingId, boolean canonical) {
        stagingRepository.insertIdentity(platform, externalId, stagingId, canonical ? "CANONICAL" : "ALIAS");
        StagingRepository.IdentityKey key = stagingRepository.findIdentity(platform, externalId).orElseThrow();
        if (key.stagingId() != stagingId || canonical != "CANONICAL".equals(key.role())) {
            throw aliasConflict(externalId, String.valueOf(key.stagingId()));
        }
    }

    private static BusinessException aliasConflict(String externalId, String other) {
        return new BusinessException(ErrorCode.STAGING_ALIAS_CONFLICT,
                "식별 키 " + externalId + " 은 다른 수집 대기 항목(" + other + ")에 연결되어 있습니다.");
    }

    // ---------- 다음 값 ----------

    private record Next(StagingRow row, List<StagingCreditRow> credits, boolean seen) {
    }

    private Next next(StagingRow cur, List<StagingCreditRow> curCredits, Valid v, SynopsisPolicy synopsisPolicy,
            long runId, boolean created) {
        DetailStatus status = v.detailStatus;
        boolean full = status == DetailStatus.OK;
        boolean listOnly = LIST_ONLY.contains(status);
        boolean failure = FAILURES.contains(status);
        List<Map<String, Object>> signals = failure ? List.of()
                : mergeSignals(parseList(cur.ageSignalsJson()), v.signals, runId);

        String url = full || listOnly || created ? v.url : cur.url();
        String titleRaw = full ? v.titleRaw : listOnly && v.titleRaw != null ? v.titleRaw : cur.titleRaw();
        String titleClean = full ? v.titleClean : listOnly && v.titleClean != null ? v.titleClean : cur.titleClean();
        String ageRaw = full ? v.ageRatingRaw : listOnly && v.ageRatingRaw != null ? v.ageRatingRaw : cur.ageRatingRaw();
        String idScope = full && v.idScope != null ? v.idScope : cur.idScope();
        String language = full && v.languageCode != null ? v.languageCode : cur.languageCode();
        String genres = full ? json(v.genres) : cur.genresJson();
        String serialRaw = full ? v.serialStatusRaw : cur.serialStatusRaw();
        SerialStatus serial = full ? v.serialStatus : cur.serialStatus();
        String cover = full ? v.coverUrl : cur.coverUrl();
        String synopsis = cur.synopsisText();
        if (full) {
            synopsis = synopsisPolicy == SynopsisPolicy.NOT_COLLECTED ? null : v.synopsis;
        }
        String extra = full ? json(v.extra) : cur.extraJson();
        Map<String, Object> sources = new TreeMap<>(parseMap(cur.fieldSourcesJson()));
        if (full) {
            sources = new TreeMap<>(v.fieldSources);
        } else if (listOnly) {
            sources.putAll(v.fieldSources);
        }
        List<StagingCreditRow> credits = full ? v.credits : curCredits;
        String ageClass = failure ? cur.ageClass() : ageClass(ageRaw, signals).name();
        String signalsJson = failure ? cur.ageSignalsJson() : json(signals);

        DetailStatus failureReason = cur.lastFailureReason();
        Long failureRun = cur.lastFailureRunId();
        int failures = cur.consecutiveFailures();
        if (status == DetailStatus.OK) {
            failures = 0;
        } else {
            boolean repeatInRun = Objects.equals(cur.lastFailureRunId(), runId) && cur.lastFailureReason() == status
                    && cur.detailStatus() == status;
            if (!repeatInRun) {
                failures = failures + 1;
            }
            failureReason = status;
            failureRun = runId;
        }
        boolean seen = created || full || listOnly || SEEN_ON_FAILURE.contains(status);

        StagingRow row = new StagingRow(cur.id(), cur.platformCode(), cur.platformWorkId(), idScope, cur.listingKind(),
                language, url, titleRaw, titleClean, genres, ageRaw, ageClass, signalsJson, serialRaw, serial, cover,
                synopsis, synopsis == null ? null : sha256(synopsis), synopsis == null ? null : synopsis.length(), extra,
                json(sources), status, cur.contentHash(), cur.firstSeenAt(), cur.lastSeenAt(), cur.lastChangedAt(),
                cur.firstRunId(), runId, failureReason, cur.lastFailureAt(), failureRun, failures, cur.reviewStatus(),
                cur.linkedListingId(), cur.createdAt(), cur.updatedAt());
        return new Next(row, credits, seen);
    }

    /**
     * 등급: 원래 표기와 명시 신호를 분류해 한 값으로 모일 때만 그 값. 신호가 없거나, 분류할 수 없거나, 서로 다르면 UNKNOWN.
     * 후보 신호(explicit=false)는 등급을 정하지 않는다.
     */
    static AgeClass ageClass(String ageRatingRaw, List<Map<String, Object>> signals) {
        Set<AgeClass> classes = EnumSet.noneOf(AgeClass.class);
        AgeRatingClassifier.classify(ageRatingRaw).ifPresent(classes::add);
        for (Map<String, Object> signal : signals) {
            if (Boolean.TRUE.equals(signal.get("explicit"))) {
                AgeRatingClassifier.classify((String) signal.get("value")).ifPresent(classes::add);
            }
        }
        return classes.size() == 1 ? classes.iterator().next() : AgeClass.UNKNOWN;
    }

    /** 출처별로 최신 신호만 둔다(다른 출처 신호는 남긴다). 출처 순으로 정렬 */
    private static List<Map<String, Object>> mergeSignals(List<Object> stored, List<AgeSignal> incoming, long runId) {
        Map<String, Map<String, Object>> bySource = new TreeMap<>();
        for (Object o : stored) {
            if (o instanceof Map<?, ?> m && m.get("source") instanceof String source) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, val) -> copy.put(String.valueOf(k), val));
                bySource.put(source, copy);
            }
        }
        String now = Instant.now().toString();
        for (AgeSignal s : incoming) {
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("source", s.source());
            signal.put("value", s.value());
            signal.put("explicit", s.explicit());
            Map<String, Object> previous = bySource.get(s.source());
            boolean same = previous != null && Objects.equals(previous.get("value"), s.value())
                    && Objects.equals(previous.get("explicit"), s.explicit());
            signal.put("seenAt", same && previous.get("seenAt") != null ? previous.get("seenAt") : now);
            signal.put("runId", runId);
            bySource.put(s.source(), signal);
        }
        return new ArrayList<>(bySource.values());
    }

    /** 내용 해시·변경 비교에 쓰는 값. 수집 시각·필드 출처·실패 정보는 넣지 않는다. 소개문은 해시·길이만 */
    private Map<String, Object> content(StagingRow row, List<StagingCreditRow> credits) {
        Map<String, Object> map = new TreeMap<>();
        map.put("url", row.url());
        map.put("idScope", row.idScope());
        map.put("languageCode", row.languageCode());
        map.put("titleRaw", row.titleRaw());
        map.put("titleClean", row.titleClean());
        map.put("genresRaw", parseList(row.genresJson()));
        map.put("ageRatingRaw", row.ageRatingRaw());
        map.put("ageClass", row.ageClass());
        List<Map<String, Object>> signals = new ArrayList<>();
        for (Object o : parseList(row.ageSignalsJson())) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> s = new TreeMap<>();
                s.put("source", m.get("source"));
                s.put("value", m.get("value"));
                s.put("explicit", m.get("explicit"));
                signals.add(s);
            }
        }
        map.put("ageSignals", signals);
        map.put("serialStatusRaw", row.serialStatusRaw());
        map.put("serialStatus", row.serialStatus().name());
        map.put("coverUrl", row.coverUrl());
        map.put("synopsisSha256", row.synopsisSha256());
        map.put("synopsisLength", row.synopsisLength());
        map.put("extra", parseMap(row.extraJson()));
        List<Map<String, Object>> creditMaps = new ArrayList<>();
        for (StagingCreditRow c : credits) {
            Map<String, Object> m = new TreeMap<>();
            m.put("nameRaw", c.nameRaw());
            m.put("roleRaw", c.roleRaw());
            m.put("role", c.role().name());
            m.put("platformCreatorId", c.platformCreatorId());
            creditMaps.add(m);
        }
        map.put("credits", creditMaps);
        return map;
    }

    /** 같은 실행의 이전 기록과 합친다: 처음 이전 값은 유지하고 이후 값만 바꾼다 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> mergeChange(long stagingId, long runId, Map<String, Map<String, Object>> diff) {
        Map<String, Map<String, Object>> merged = new TreeMap<>();
        stagingRepository.findChange(stagingId, runId).ifPresent(existing -> {
            try {
                Map<String, Object> parsed = CANONICAL.readValue(existing, new TypeReference<Map<String, Object>>() { });
                parsed.forEach((k, val) -> merged.put(k, (Map<String, Object>) val));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored change json is invalid", e);
            }
        });
        diff.forEach((field, change) -> {
            Map<String, Object> prior = merged.get(field);
            merged.put(field, pair(prior != null ? prior.get("before") : change.get("before"), change.get("after")));
        });
        return merged;
    }

    private static Map<String, Object> pair(Object before, Object after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    private static StagingRow withHash(StagingRow r, String hash) {
        return new StagingRow(r.id(), r.platformCode(), r.platformWorkId(), r.idScope(), r.listingKind(), r.languageCode(),
                r.url(), r.titleRaw(), r.titleClean(), r.genresJson(), r.ageRatingRaw(), r.ageClass(), r.ageSignalsJson(),
                r.serialStatusRaw(), r.serialStatus(), r.coverUrl(), r.synopsisText(), r.synopsisSha256(), r.synopsisLength(),
                r.extraJson(), r.fieldSourcesJson(), r.detailStatus(), hash, r.firstSeenAt(), r.lastSeenAt(),
                r.lastChangedAt(), r.firstRunId(), r.lastRunId(), r.lastFailureReason(), r.lastFailureAt(),
                r.lastFailureRunId(), r.consecutiveFailures(), r.reviewStatus(), r.linkedListingId(), r.createdAt(),
                r.updatedAt());
    }

    // ---------- 입력 검증 ----------

    private record Valid(String platformWorkId, String url, DetailStatus detailStatus, String idScope, String languageCode,
            String titleRaw, String titleClean, List<String> genres, String ageRatingRaw, List<AgeSignal> signals,
            String serialStatusRaw, SerialStatus serialStatus, String coverUrl, String synopsis, Map<String, Object> extra,
            Map<String, Object> fieldSources, List<StagingCreditRow> credits, String discoverySource, List<String> aliasIds) {
    }

    private Valid validate(CollectedListing c, PlatformRow platform) {
        AdminInputs in = new AdminInputs();
        String code = c.platformCode() == null ? null : c.platformCode().strip().toUpperCase(java.util.Locale.ROOT);
        if (!platform.code().equals(code)) {
            in.add("platformCode", "실행의 플랫폼(" + platform.code() + ")과 다릅니다.");
        }
        String workId = c.platformWorkId();
        if (workId == null || !VISIBLE_ASCII.matcher(workId).matches()) {
            in.add("platformWorkId", "외부 작품 ID 는 공백 없는 영문·숫자·기호(ASCII) 1~100자여야 합니다.");
        }
        String url = null;
        PlatformUrls.Result urlResult = PlatformUrls.validate(c.url(), platform.allowedHosts(), null, null);
        if (!urlResult.ok()) {
            in.add("url", urlResult.reason());
        } else {
            url = urlResult.normalizedUrl();
        }
        DetailStatus status = in.enumValue(c.detailStatus(), DetailStatus.class, "detailStatus", true);
        if (status == DetailStatus.PENDING) {
            in.add("detailStatus", "수집 결과의 상태로 PENDING 은 쓸 수 없습니다.");
        }
        IdScope scope = in.enumValue(c.idScope(), IdScope.class, "idScope", false);
        String language = null;
        if (c.languageCode() != null) {
            try {
                language = LanguageTags.normalize(c.languageCode());
            } catch (IllegalArgumentException e) {
                in.add("languageCode", e.getMessage());
            }
        }
        String titleRaw = in.text(c.titleRaw(), "titleRaw", MAX_TITLE_RAW, false);
        String titleClean = in.text(c.titleClean(), "titleClean", MAX_TITLE_CLEAN, false);
        List<String> genres = new ArrayList<>();
        if (c.genresRaw() != null) {
            if (c.genresRaw().size() > MAX_GENRES) {
                in.add("genresRaw", "장르는 " + MAX_GENRES + "개 이하여야 합니다.");
            } else {
                for (int i = 0; i < c.genresRaw().size(); i++) {
                    String g = in.text(c.genresRaw().get(i), "genresRaw[" + i + "]", MAX_GENRE_LENGTH, true);
                    if (g != null) {
                        genres.add(g);
                    }
                }
            }
        }
        String ageRaw = in.text(c.ageRatingRaw(), "ageRatingRaw", 50, false);
        List<AgeSignal> signals = new ArrayList<>();
        if (c.ageSignals() != null) {
            if (c.ageSignals().size() > MAX_SIGNALS) {
                in.add("ageSignals", "등급 신호는 " + MAX_SIGNALS + "개 이하여야 합니다.");
            } else {
                Set<String> seenSources = new HashSet<>();
                for (int i = 0; i < c.ageSignals().size(); i++) {
                    AgeSignal s = c.ageSignals().get(i);
                    String p = "ageSignals[" + i + "]";
                    if (s == null || s.source() == null || !SIGNAL_SOURCE.matcher(s.source()).matches()) {
                        in.add(p + ".source", "신호 출처는 소문자·숫자·:_.- 1~50자여야 합니다.");
                        continue;
                    }
                    String value = in.text(s.value(), p + ".value", 100, true);
                    if (!seenSources.add(s.source())) {
                        in.add(p + ".source", "같은 출처의 신호가 중복되었습니다.");
                    } else if (value != null) {
                        signals.add(new AgeSignal(s.source(), value, s.explicit()));
                    }
                }
            }
        }
        String serialRaw = in.text(c.serialStatusRaw(), "serialStatusRaw", 100, false);
        SerialStatus serial = in.enumValue(c.serialStatus(), SerialStatus.class, "serialStatus", false);
        String cover = null;
        if (c.coverUrl() != null && !c.coverUrl().isBlank()) {
            PlatformUrls.Result coverResult = PlatformUrls.validateGeneral(c.coverUrl());
            if (!coverResult.ok()) {
                in.add("coverUrl", coverResult.reason());
            } else {
                cover = coverResult.normalizedUrl();
            }
        }
        String synopsis = c.synopsis() == null || c.synopsis().isBlank() ? null : c.synopsis().strip();
        if (synopsis != null && synopsis.length() > MAX_SYNOPSIS) {
            in.add("synopsis", "소개문은 " + MAX_SYNOPSIS + "자 이하여야 합니다.");
        }
        Map<String, Object> extra = c.extra() == null ? Map.of() : c.extra();
        Map<String, Object> sources = c.fieldSources() == null ? Map.of() : c.fieldSources();
        if (json(extra).getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            in.add("extra", "extra 는 " + MAX_JSON_BYTES + "바이트 이하여야 합니다.");
        }
        if (json(sources).getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            in.add("fieldSources", "fieldSources 는 " + MAX_JSON_BYTES + "바이트 이하여야 합니다.");
        }
        List<StagingCreditRow> credits = new ArrayList<>();
        if (c.credits() != null) {
            if (c.credits().size() > MAX_CREDITS) {
                in.add("credits", "크레딧은 " + MAX_CREDITS + "개 이하여야 합니다.");
            } else {
                Set<List<String>> keys = new HashSet<>();
                for (int i = 0; i < c.credits().size(); i++) {
                    CollectedCredit cr = c.credits().get(i);
                    String p = "credits[" + i + "]";
                    if (cr == null) {
                        in.add(p, "크레딧 항목은 비어 있을 수 없습니다.");
                        continue;
                    }
                    String name = in.text(cr.nameRaw(), p + ".nameRaw", 200, true);
                    String roleRaw = in.text(cr.roleRaw(), p + ".roleRaw", 50, false);
                    CreditRole role = in.enumValue(cr.role(), CreditRole.class, p + ".role", true);
                    String pcid = cr.platformCreatorId();
                    if (pcid != null && !VISIBLE_ASCII.matcher(pcid).matches()) {
                        in.add(p + ".platformCreatorId", "플랫폼 작가 ID 는 공백 없는 ASCII 1~100자여야 합니다.");
                    }
                    if (name != null && role != null) {
                        if (!keys.add(List.of(name, role.name()))) {
                            in.add(p, "같은 이름·역할의 크레딧이 중복되었습니다.");
                        } else {
                            credits.add(new StagingCreditRow(0, name, roleRaw, role, pcid, credits.size()));
                        }
                    }
                }
            }
        }
        String discovery = c.discoverySource();
        if (discovery != null && !SOURCE_KEY.matcher(discovery).matches()) {
            in.add("discoverySource", "발견 경로 키는 소문자·숫자·:_.- 1~100자여야 합니다.");
        }
        List<String> aliases = new ArrayList<>();
        if (c.aliasIds() != null) {
            if (c.aliasIds().size() > MAX_ALIASES) {
                in.add("aliasIds", "별칭은 " + MAX_ALIASES + "개 이하여야 합니다.");
            } else {
                for (int i = 0; i < c.aliasIds().size(); i++) {
                    String a = c.aliasIds().get(i);
                    if (a == null || !VISIBLE_ASCII.matcher(a).matches()) {
                        in.add("aliasIds[" + i + "]", "별칭 ID 는 공백 없는 ASCII 1~100자여야 합니다.");
                    } else if (!aliases.contains(a)) {
                        aliases.add(a);
                    }
                }
            }
        }
        in.throwIfAny();
        return new Valid(workId, url, status, scope == null ? null : scope.name(), language, titleRaw, titleClean,
                genres, ageRaw, signals, serialRaw, serial == null ? SerialStatus.UNKNOWN : serial, cover, synopsis,
                extra, sources, credits, discovery, aliases);
    }

    // ---------- JSON ----------

    private static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "JSON 으로 바꿀 수 없는 값이 있습니다.");
        }
    }

    private static String json(Object value) {
        return canonical(value);
    }

    private static List<Object> parseList(String json) {
        try {
            return json == null ? List.of() : CANONICAL.readValue(json, new TypeReference<List<Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored json array is invalid", e);
        }
    }

    private static Map<String, Object> parseMap(String json) {
        try {
            return json == null ? Map.of() : CANONICAL.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored json object is invalid", e);
        }
    }

    static String sha256(String value) {
        return CrawlTaskService.sha256(value);
    }
}
