package com.manhwanet.platform.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.platform.domain.PlatformRow;
import com.manhwanet.platform.domain.PlatformStatus;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.platform.domain.StatusBasis;
import com.manhwanet.platform.domain.UrlRuleBasis;
import com.manhwanet.platform.dto.ChangePlatformStatusRequest;
import com.manhwanet.platform.dto.PlatformResponse;
import com.manhwanet.platform.dto.PlatformStatusChangeResponse;
import com.manhwanet.platform.dto.StatusHistoryResponse;
import com.manhwanet.platform.dto.UpdatePlatformRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 코드 표·URL 규칙·운영 상태 관리 (#39, docs/work-platform-admin-spec.md §5.5).
 * <ul>
 *   <li>모든 변경은 요청 version 이 현재 platforms.version 과 같을 때만 반영하고 1 올린다(다르면 409 PLATFORM_VERSION_CONFLICT).</li>
 *   <li>상태 변경과 이력(platform_status_history) 저장은 한 트랜잭션이다. 상태·근거·통합 대상·효력일·근거 URL·메모가 모두 같을 때만 이력을 남기지 않는다.</li>
 *   <li>종료(CLOSED)·통합(MERGING)은 공식 확인(OFFICIAL)만 허용한다. 보도·미확인은 UNKNOWN 상태에 근거를 남긴다.</li>
 *   <li>플랫폼 상태가 바뀌어도 연결 상태는 자동으로 바꾸지 않는다(관리자가 연결마다 ENDED·MOVED 처리).</li>
 *   <li>증거 URL 은 형식만 검사하고 서버가 가져오지 않는다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PlatformAdminService {

    static final int MAX_HOSTS = 10;
    static final int ACTIVE_LISTING_ID_LIMIT = 100;
    static final Set<String> HOST_CONSTRAINTS = Set.of("uk_platform_hosts_host");

    private final PlatformRepository platformRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public PlatformAdminService(PlatformRepository platformRepository, NamedParameterJdbcTemplate jdbc) {
        this.platformRepository = platformRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PlatformResponse> getPlatforms() {
        List<PlatformRow> rows = platformRepository.findAll();
        return rows.stream().map(row -> respond(row, rows)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlatformResponse getPlatform(String code) {
        code = normalizeCode(code);
        return respond(find(code), platformRepository.findAll());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<StatusHistoryResponse> getStatusHistory(String code) {
        code = normalizeCode(code);
        find(code);
        return platformRepository.findHistory(code).stream().map(StatusHistoryResponse::from).toList();
    }

    /** URL 규칙·표시 이름 등 수정(전체 교체). 운영 상태는 changeStatus 로만 바꾼다 */
    @Transactional
    public PlatformResponse updateRules(String code, UpdatePlatformRequest request) {
        code = normalizeCode(code);
        AdminInputs in = new AdminInputs(new ArrayList<>());
        Long version = in.version(request.version());
        String name = in.text(request.name(), "name", 50, true);
        String operatorName = in.text(request.operatorName(), "operatorName", 100, false);
        IdScope scope = in.enumValue(request.defaultIdScope(), IdScope.class, "defaultIdScope", true);
        String pattern = in.text(request.workUrlPattern(), "workUrlPattern", PlatformUrls.PATTERN_MAX_LENGTH, false);
        if (pattern != null) {
            String problem = PlatformUrls.checkPattern(pattern);
            if (problem != null) {
                in.add("workUrlPattern", problem);
            }
        }
        UrlRuleBasis ruleBasis = in.enumValue(request.urlRuleBasis(), UrlRuleBasis.class, "urlRuleBasis", true);
        LocalDate ruleCheckedOn = pastDate(request.urlRuleCheckedOn(), "urlRuleCheckedOn", in);
        if (ruleBasis == UrlRuleBasis.OBSERVED && request.urlRuleCheckedOn() == null) {
            in.add("urlRuleCheckedOn", "urlRuleBasis 가 OBSERVED 이면 확인일이 필요합니다.");
        }
        if (ruleBasis == UrlRuleBasis.OBSERVED && pattern == null) {
            in.add("workUrlPattern", "urlRuleBasis 가 OBSERVED 이면 관찰한 작품 URL 패턴이 필요합니다.");
        }
        LocalDate termsCheckedOn = pastDate(request.termsCheckedOn(), "termsCheckedOn", in);
        List<String> hosts = hosts(request.allowedHosts(), in);
        in.throwIfAny();

        PlatformRow current = platformRepository.lockForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        if (current.version() != version) {
            throw new VersionConflictException(ErrorCode.PLATFORM_VERSION_CONFLICT, current.version());
        }
        List<String> takenByOthers = jdbc.queryForList(
                "SELECT host FROM platform_hosts WHERE host IN (:hosts) AND platform_code <> :code ORDER BY host",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("hosts", hosts).addValue("code", code), String.class);
        if (!takenByOthers.isEmpty()) {
            in.add("allowedHosts", "다른 플랫폼이 이미 쓰는 호스트입니다: " + String.join(", ", takenByOthers));
            in.throwIfAny();
        }

        boolean same = current.name().equals(name)
                && Objects.equals(current.operatorName(), operatorName)
                && current.defaultIdScope() == scope
                && Objects.equals(current.workUrlPattern(), pattern)
                && current.urlRuleBasis() == ruleBasis
                && Objects.equals(current.urlRuleCheckedOn(), ruleCheckedOn)
                && Objects.equals(current.termsCheckedOn(), termsCheckedOn)
                && Set.copyOf(current.allowedHosts()).equals(Set.copyOf(hosts));
        if (same) {
            return respond(current, platformRepository.findAll());
        }
        try {
            if (!Set.copyOf(current.allowedHosts()).equals(Set.copyOf(hosts))) {
                platformRepository.replaceHosts(code, hosts);
            }
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, HOST_CONSTRAINTS)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "다른 플랫폼이 같은 호스트를 동시에 등록했습니다.");
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // 호스트는 정렬해 넣으므로 교착은 드물지만, 발생하면 재시도 가능한 충돌로 돌려준다
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "다른 플랫폼의 호스트 변경과 충돌했습니다. 다시 시도해 주세요.");
        }
        int updated = platformRepository.updateRules(code, version, name, operatorName, scope, pattern, ruleBasis,
                ruleCheckedOn, termsCheckedOn);
        if (updated != 1) {
            throw new IllegalStateException("locked platform was not updated: " + code);
        }
        return respond(find(code), platformRepository.findAll());
    }

    /**
     * 운영 상태 변경 + 이력 저장(한 트랜잭션).
     * 순서: 입력 검증(400) → (통합 대상이 있으면 플랫폼 전체를 코드 순으로 잠금) → 대상 잠금(없으면 404)
     * → version 비교(409) → 통합 대상 존재·순환 검사(400) → 상태·근거·메모까지 같으면 그대로 → 이력 INSERT → 상태 UPDATE.
     */
    @Transactional
    public PlatformStatusChangeResponse changeStatus(String code, ChangePlatformStatusRequest request, long adminId) {
        code = normalizeCode(code);
        AdminInputs in = new AdminInputs(new ArrayList<>());
        Long version = in.version(request.version());
        PlatformStatus toStatus = in.enumValue(request.toStatus(), PlatformStatus.class, "toStatus", true);
        StatusBasis basis = in.enumValue(request.statusBasis(), StatusBasis.class, "statusBasis", true);
        String mergedInto = in.text(request.mergedInto(), "mergedInto", 30, false);
        if (mergedInto != null) {
            mergedInto = mergedInto.toUpperCase(Locale.ROOT);
        }
        String evidenceUrl = evidenceUrl(request.evidenceUrl(), in);
        String note = in.text(request.note(), "note", 500, false);
        LocalDate effectiveOn = request.effectiveOn();
        if (toStatus != null && basis != null) {
            if ((toStatus == PlatformStatus.CLOSED || toStatus == PlatformStatus.MERGING)
                    && basis != StatusBasis.OFFICIAL) {
                in.add("statusBasis", "종료(CLOSED)·통합(MERGING)은 공식 확인(OFFICIAL)일 때만 기록합니다. "
                        + "보도·미확인은 UNKNOWN 상태에 근거(PRESS/UNVERIFIED)로 남겨 주세요.");
            }
            if (basis != StatusBasis.UNVERIFIED && evidenceUrl == null && !in.hasError("evidenceUrl")) {
                in.add("evidenceUrl", "공식·보도 근거에는 근거 URL 이 필요합니다.");
            }
            if (toStatus == PlatformStatus.MERGING && mergedInto == null) {
                in.add("mergedInto", "통합(MERGING)에는 통합 대상 플랫폼이 필요합니다.");
            }
            if (mergedInto != null && toStatus != PlatformStatus.MERGING && toStatus != PlatformStatus.CLOSED) {
                in.add("mergedInto", "통합 대상은 MERGING·CLOSED 상태에서만 지정할 수 있습니다.");
            }
        }
        if (mergedInto != null && mergedInto.equals(code)) {
            in.add("mergedInto", "자기 자신으로 통합할 수 없습니다.");
        }
        in.throwIfAny();

        if (mergedInto != null) {
            // 순환(A→B, B→A)을 동시에 만들지 못하도록 통합 대상이 있는 변경은 플랫폼 행 전체를 코드 순서로 잠근다(10행).
            jdbc.queryForList("SELECT code FROM platforms ORDER BY code FOR NO KEY UPDATE", java.util.Map.of(),
                    String.class);
        }
        PlatformRow current = platformRepository.lockForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        if (current.version() != version) {
            throw new VersionConflictException(ErrorCode.PLATFORM_VERSION_CONFLICT, current.version());
        }
        if (mergedInto != null) {
            PlatformRow target = platformRepository.findByCode(mergedInto).orElse(null);
            if (target == null) {
                in.add("mergedInto", "없는 플랫폼입니다: " + mergedInto);
            } else if (target.status() == PlatformStatus.CLOSED) {
                in.add("mergedInto", "종료(CLOSED)된 플랫폼으로는 통합할 수 없습니다: " + mergedInto);
            } else if (platformRepository.mergedChain(mergedInto).contains(code)) {
                in.add("mergedInto", "통합 관계가 순환합니다: " + mergedInto + " 는 이미 " + code + " 로 이어집니다.");
            }
            in.throwIfAny();
        }

        Optional<PlatformRepository.LatestNote> previousNote = platformRepository.latestStatusNote(code);
        boolean noteSame = previousNote.isEmpty() ? note == null : Objects.equals(previousNote.get().note(), note);
        boolean same = current.status() == toStatus
                && current.statusBasis() == basis
                && Objects.equals(current.mergedInto(), mergedInto)
                && Objects.equals(current.statusEffectiveOn(), effectiveOn)
                && Objects.equals(current.statusEvidenceUrl(), evidenceUrl)
                && noteSame;
        if (same) {
            return statusResponse(current, null);
        }
        long historyId = platformRepository.insertHistory(code, current, toStatus, basis, mergedInto, effectiveOn,
                evidenceUrl, note, adminId);
        int updated = platformRepository.updateStatus(code, version, toStatus, basis, mergedInto, effectiveOn,
                evidenceUrl);
        if (updated != 1) {
            throw new IllegalStateException("locked platform was not updated: " + code);
        }
        return statusResponse(find(code), historyId);
    }

    private PlatformStatusChangeResponse statusResponse(PlatformRow platform, Long historyId) {
        return new PlatformStatusChangeResponse(respond(platform, platformRepository.findAll()), historyId,
                platformRepository.countActiveListings(platform.code()),
                platformRepository.findActiveListingIds(platform.code(), ACTIVE_LISTING_ID_LIMIT));
    }

    private PlatformResponse respond(PlatformRow row, List<PlatformRow> all) {
        return PlatformResponse.from(row, mergeWarnings(row, all));
    }

    /**
     * 통합 관계는 지우지 않는다. 대상이 이미 CLOSED 이거나, CLOSED 인 이 플랫폼을 다른 플랫폼이 아직 가리키면 경고만 준다.
     */
    private static List<String> mergeWarnings(PlatformRow row, List<PlatformRow> all) {
        List<String> warnings = new ArrayList<>();
        if (row.mergedInto() != null) {
            for (PlatformRow other : all) {
                if (other.code().equals(row.mergedInto()) && other.status() == PlatformStatus.CLOSED) {
                    warnings.add("MERGED_INTO_CLOSED");
                    break;
                }
            }
        }
        if (row.status() == PlatformStatus.CLOSED) {
            for (PlatformRow other : all) {
                if (row.code().equals(other.mergedInto())) {
                    warnings.add("MERGE_SOURCES_REMAIN");
                    break;
                }
            }
        }
        return warnings;
    }

    /** 경로의 플랫폼 코드는 대소문자를 구분하지 않는다(저장 값은 대문자). */
    static String normalizeCode(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    private PlatformRow find(String code) {
        return platformRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
    }

    private LocalDate pastDate(LocalDate value, String field, AdminInputs in) {
        if (value != null && value.isAfter(LocalDate.now(clock.withZone(ZoneOffset.ofHours(14))))) {
            in.add(field, field + " 은 미래 날짜일 수 없습니다.");
            return null;
        }
        return value;
    }

    private static List<String> hosts(List<String> raw, AdminInputs in) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_HOSTS) {
            in.add("allowedHosts", "허용 호스트는 1개 이상 " + MAX_HOSTS + "개 이하여야 합니다.");
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String host = raw.get(i) == null ? "" : raw.get(i).strip().toLowerCase(Locale.ROOT);
            if (!PlatformUrls.isValidHost(host)) {
                in.add("allowedHosts[" + i + "]", "호스트 이름 형식이 올바르지 않습니다(예: www.example.com).");
            } else if (!result.add(host)) {
                in.add("allowedHosts[" + i + "]", "같은 호스트가 중복되었습니다.");
            }
        }
        // 호스트를 정렬해 넣으면 두 플랫폼이 서로의 호스트를 맞바꾸는 동시 요청도 같은 순서로 잠근다
        return result.stream().sorted().toList();
    }

    /** 근거 URL: https 형식만 검사한다(가져오지 않음). */
    private static String evidenceUrl(String raw, AdminInputs in) {
        String value = in.text(raw, "evidenceUrl", PlatformUrls.URL_MAX_LENGTH, false);
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                in.add("evidenceUrl", "근거 URL 은 https 주소여야 합니다.");
                return null;
            }
        } catch (URISyntaxException e) {
            in.add("evidenceUrl", "근거 URL 형식이 올바르지 않습니다.");
            return null;
        }
        return value;
    }
}
