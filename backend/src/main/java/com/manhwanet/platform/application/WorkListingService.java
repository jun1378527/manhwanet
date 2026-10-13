package com.manhwanet.platform.application;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ListingAlreadyLinkedException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.AgeRatingClassifier;
import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.ListingKind;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.platform.domain.ListingRow;
import com.manhwanet.platform.domain.ListingStatus;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.platform.domain.PlatformRow;
import com.manhwanet.platform.domain.PlatformStatus;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.platform.domain.UrlRuleBasis;
import com.manhwanet.platform.dto.CreateListingRequest;
import com.manhwanet.platform.dto.ListingInput;
import com.manhwanet.platform.dto.ListingLookupResponse;
import com.manhwanet.platform.dto.ListingMutationResponse;
import com.manhwanet.platform.dto.ListingResponse;
import com.manhwanet.platform.dto.MoveListingRequest;
import com.manhwanet.platform.dto.UpdateListingRequest;
import com.manhwanet.platform.dto.WorkListingsResponse;
import com.manhwanet.work.domain.LanguageTags;
import com.manhwanet.work.domain.WorkRepository;
import com.manhwanet.work.domain.WorkRepository.LockedWork;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품 플랫폼 연결 관리 (#39, docs/work-platform-admin-spec.md §4·§5.3).
 * <ul>
 *   <li>하나의 작품에 여러 플랫폼 연결을 둔다. 같은 (플랫폼, 외부 작품 ID) 는 한 연결만 가진다(409 PLATFORM_LISTING_ALREADY_LINKED).</li>
 *   <li>모든 변경은 작품 version(명세 S-3: 작품당 버전 하나)이 같을 때만 반영하고 작품 version 을 1 올린다.</li>
 *   <li>삭제하지 않는다. 제공 종료는 ENDED, 이전은 MOVED(+이전 대상)로 바꾼다.</li>
 *   <li>이전 대상은 같은 작품의 다른 연결이어야 하고 그 자체가 MOVED 이면 안 된다(자기 참조·순환 방지).
 *       연결이 MOVED 로 바뀌면 그 연결을 가리키던 연결도 새 대상으로 옮겨, MOVED 연결의 대상은 항상 MOVED 가 아니다.</li>
 *   <li>URL 은 허용 스킴·호스트·플랫폼 패턴으로만 검사하고 서버가 가져오지 않는다.</li>
 *   <li>제목이 같다고 다른 작품의 연결을 합치지 않는다(이 서비스는 작품 ID 로만 연결한다).</li>
 * </ul>
 * 잠금 순서: 플랫폼 행(FOR SHARE, 상태 변경과 직렬화) → 작품 행(FOR NO KEY UPDATE). 같은 작품의 연결 변경은 작품 잠금으로 직렬화된다.
 */
@Service
@Transactional(readOnly = true)
public class WorkListingService {

    private final ListingRepository listingRepository;
    private final PlatformRepository platformRepository;
    private final WorkRepository workRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public WorkListingService(ListingRepository listingRepository, PlatformRepository platformRepository,
            WorkRepository workRepository, NamedParameterJdbcTemplate jdbc) {
        this.listingRepository = listingRepository;
        this.platformRepository = platformRepository;
        this.workRepository = workRepository;
        this.jdbc = jdbc;
    }

    /** 작품 version 과 연결 목록을 같은 스냅샷에서 읽는다 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkListingsResponse getListings(long workId) {
        long version = workVersion(workId);
        return new WorkListingsResponse(workId, version,
                listingRepository.findByWorkId(workId).stream().map(ListingResponse::from).toList());
    }

    public ListingLookupResponse lookup(String platformCode, String platformWorkId) {
        AdminInputs in = new AdminInputs(new ArrayList<>());
        String code = in.text(platformCode, "platformCode", 30, true);
        String id = in.text(platformWorkId, "platformWorkId", 100, true);
        in.throwIfAny();
        Optional<ListingRow> found = listingRepository.findByPlatformWorkId(code.toUpperCase(Locale.ROOT), id);
        return new ListingLookupResponse(found.isPresent(), found.map(ListingResponse::from).orElse(null));
    }

    @Transactional
    public ListingMutationResponse create(long workId, CreateListingRequest request, long adminId) {
        AdminInputs in = new AdminInputs(new ArrayList<>());
        Long version = in.version(request.version());
        NewListing input = newListing(request.toInput(), "", in);
        in.throwIfAny();

        PlatformRow platform = checkedPlatform(input.platformCode, input.url, input.platformWorkId, "", in);
        String url = checkUrl(input.url, platform, input.platformWorkId, "", in);
        if (platform.status() == PlatformStatus.CLOSED) {
            in.add("platformCode", "종료(CLOSED)된 플랫폼에는 새 연결을 만들 수 없습니다.");
        }
        in.throwIfAny();
        lockWork(workId, version);

        long listingId = insert(workId, input, url, adminId);
        bump(workId, version);
        return new ListingMutationResponse(workId, version + 1, response(listingId), null,
                warnings(platform, input.idScope, input.ageClass));
    }

    @Transactional
    public ListingMutationResponse update(long workId, long listingId, UpdateListingRequest request, long adminId) {
        AdminInputs in = new AdminInputs(new ArrayList<>());
        Long version = in.version(request.version());
        String rawUrl = in.text(request.url(), "url", PlatformUrls.URL_MAX_LENGTH, true);
        IdScope idScope = in.enumValue(request.idScope(), IdScope.class, "idScope", true);
        ListingKind kind = in.enumValue(request.listingKind(), ListingKind.class, "listingKind", true);
        String languageCode = language(request.languageCode(), "languageCode", in);
        String ageRaw = in.text(request.ageRatingRaw(), "ageRatingRaw", 50, false);
        String note = in.text(request.note(), "note", 500, false);
        AgeClass ageClass = age(ageRaw, request.ageClass(), note, "", in);
        ListingStatus status = in.enumValue(request.status(), ListingStatus.class, "status", true);
        Long movedTo = in.integer(request.movedToListingId(), "movedToListingId", 1, false);
        if (status == ListingStatus.MOVED && movedTo == null && request.movedToListingId() == null) {
            in.add("movedToListingId", "MOVED 상태에는 이전 대상 연결(movedToListingId)이 필요합니다.");
        }
        if (status != null && status != ListingStatus.MOVED && request.movedToListingId() != null) {
            in.add("movedToListingId", "이전 대상은 MOVED 상태에서만 지정할 수 있습니다.");
        }
        Instant lastVerifiedAt = in.verifiedAt(request.lastVerifiedAt(), "lastVerifiedAt", false, clock);
        in.throwIfAny();

        ListingRow before = ownedListing(workId, listingId);
        // 저장된 URL 을 그대로 보내면 규칙을 다시 검사하지 않는다(규칙이 바뀐 뒤에도 종료·이전 처리를 할 수 있게)
        boolean urlUnchanged = rawUrl.equals(before.url());
        PlatformRow platform = urlUnchanged
                ? lockPlatform(before.platformCode())
                : checkedPlatform(before.platformCode(), rawUrl, before.platformWorkId(), "", in);
        String url = urlUnchanged ? before.url() : checkUrl(rawUrl, platform, before.platformWorkId(), "", in);
        in.throwIfAny();
        lockWork(workId, version);
        ListingRow current = ownedListing(workId, listingId);
        if (platform.status() == PlatformStatus.CLOSED && status == ListingStatus.ACTIVE
                && current.status() != ListingStatus.ACTIVE) {
            in.add("status", "종료(CLOSED)된 플랫폼의 연결을 다시 ACTIVE 로 바꿀 수 없습니다.");
        }
        if (status == ListingStatus.MOVED && !Objects.equals(current.movedToListingId(), movedTo)) {
            checkMoveTarget(current, movedTo, in);
        }
        Instant verified = lastVerifiedAt == null ? current.lastVerifiedAt() : lastVerifiedAt;
        if (verified.isBefore(current.firstVerifiedAt())) {
            in.add("lastVerifiedAt", "마지막 확인 시각은 처음 확인 시각(" + current.firstVerifiedAt() + ")보다 빠를 수 없습니다.");
        }
        in.throwIfAny();
        long verifiedBy = verified.equals(current.lastVerifiedAt()) ? current.verifiedBy() : adminId;

        boolean same = current.idScope() == idScope && current.url().equals(url) && current.listingKind() == kind
                && current.languageCode().equals(languageCode) && current.status() == status
                && Objects.equals(current.movedToListingId(), movedTo)
                && Objects.equals(current.ageRatingRaw(), ageRaw) && current.ageClass() == ageClass
                && current.lastVerifiedAt().equals(verified) && Objects.equals(current.note(), note);
        if (same) {
            return new ListingMutationResponse(workId, version, ListingResponse.from(current), null,
                    warnings(platform, idScope, ageClass));
        }
        listingRepository.update(listingId, idScope, url, kind, languageCode, status, movedTo, ageRaw, ageClass,
                verified, verifiedBy, note);
        if (status == ListingStatus.MOVED) {
            listingRepository.repointMovedTo(workId, listingId, movedTo);
        }
        bump(workId, version);
        return new ListingMutationResponse(workId, version + 1, response(listingId), null,
                warnings(platform, idScope, ageClass));
    }

    /** 새 연결을 만들고 기존 연결을 MOVED(→새 연결)로 바꾼다. 한 트랜잭션, 작품 version 은 1만 오른다 */
    @Transactional
    public ListingMutationResponse move(long workId, long listingId, MoveListingRequest request, long adminId) {
        AdminInputs in = new AdminInputs(new ArrayList<>());
        Long version = in.version(request.version());
        if (request.newListing() == null) {
            in.add("newListing", "새 연결(newListing)이 필요합니다.");
            in.throwIfAny();
        }
        NewListing input = newListing(request.newListing(), "newListing.", in);
        in.throwIfAny();

        ownedListing(workId, listingId);
        PlatformRow platform = checkedPlatform(input.platformCode, input.url, input.platformWorkId, "newListing.", in);
        String url = checkUrl(input.url, platform, input.platformWorkId, "newListing.", in);
        if (platform.status() == PlatformStatus.CLOSED) {
            in.add("newListing.platformCode", "종료(CLOSED)된 플랫폼에는 새 연결을 만들 수 없습니다.");
        }
        in.throwIfAny();
        lockWork(workId, version);
        ListingRow current = ownedListing(workId, listingId);
        if (current.status() == ListingStatus.MOVED) {
            in.add("status", "이미 다른 연결로 이전(MOVED)된 연결입니다.");
            in.throwIfAny();
        }

        long newId = insert(workId, input, url, adminId);
        listingRepository.update(listingId, current.idScope(), current.url(), current.listingKind(),
                current.languageCode(), ListingStatus.MOVED, newId, current.ageRatingRaw(), current.ageClass(),
                current.lastVerifiedAt(), current.verifiedBy(), current.note());
        listingRepository.repointMovedTo(workId, listingId, newId);
        bump(workId, version);
        return new ListingMutationResponse(workId, version + 1, response(newId), response(listingId),
                warnings(platform, input.idScope, input.ageClass));
    }

    // ---------- 내부 ----------

    private record NewListing(String platformCode, String platformWorkId, String url, IdScope idScope,
            ListingKind kind, String languageCode, String ageRaw, AgeClass ageClass, Instant verifiedAt, String note) {
    }

    private NewListing newListing(ListingInput input, String prefix, AdminInputs in) {
        String code = in.text(input.platformCode(), prefix + "platformCode", 30, true);
        String id = in.text(input.platformWorkId(), prefix + "platformWorkId", 100, true);
        if (id != null && !PlatformUrls.isValidWorkId(id)) {
            in.add(prefix + "platformWorkId", "외부 작품 ID는 공백 없는 영문·숫자·기호(ASCII)만 쓸 수 있습니다.");
        }
        String url = in.text(input.url(), prefix + "url", PlatformUrls.URL_MAX_LENGTH, true);
        IdScope scope = in.enumValue(input.idScope(), IdScope.class, prefix + "idScope", true);
        ListingKind kind = in.enumValue(input.listingKind(), ListingKind.class, prefix + "listingKind", true);
        String language = language(input.languageCode(), prefix + "languageCode", in);
        String ageRaw = in.text(input.ageRatingRaw(), prefix + "ageRatingRaw", 50, false);
        String note = in.text(input.note(), prefix + "note", 500, false);
        AgeClass ageClass = age(ageRaw, input.ageClass(), note, prefix, in);
        Instant verifiedAt = in.verifiedAt(input.verifiedAt(), prefix + "verifiedAt", true, clock);
        return new NewListing(code == null ? null : code.toUpperCase(Locale.ROOT), id, url, scope, kind, language,
                ageRaw, ageClass, verifiedAt, note);
    }

    /**
     * 이용등급: 명확한 18세 미만 표기 → GENERAL, 명확한 성인 표기 → ADULT, 모호하거나 없으면 UNKNOWN.
     * ageClass 를 생략하면 원래 표기로 분류하고, 표기가 없거나 분류할 수 없으면 UNKNOWN.
     * 지정했는데 표기와 어긋나거나, 표기 없이 GENERAL·ADULT 를 지정하면 400.
     * GENERAL 은 표기가 18세 미만 등급으로 확인될 때만 쓸 수 있다.
     * 알아보지 못한 표기를 ADULT 로 지정하려면 사유(note)가 있어야 하고, 그 메모를 연결에 남긴다.
     */
    private static AgeClass age(String raw, String requested, String note, String prefix, AdminInputs in) {
        AgeClass asked = in.enumValue(requested, AgeClass.class, prefix + "ageClass", false);
        if (in.hasError(prefix + "ageRatingRaw")) {
            return AgeClass.UNKNOWN;
        }
        Optional<AgeClass> derived = AgeRatingClassifier.classify(raw);
        if (asked == null) {
            return requested == null ? derived.orElse(AgeClass.UNKNOWN) : AgeClass.UNKNOWN;
        }
        if (raw == null && asked != AgeClass.UNKNOWN) {
            in.add(prefix + "ageClass", "원래 이용등급 표기(ageRatingRaw) 없이 GENERAL·ADULT 로 분류할 수 없습니다.");
        } else if (derived.isPresent() && derived.get() != asked) {
            in.add(prefix + "ageClass", "원래 표기 '" + raw + "' 는 " + derived.get() + " 로 분류됩니다.");
        } else if (derived.isEmpty() && asked == AgeClass.GENERAL) {
            in.add(prefix + "ageClass", "표기 '" + raw + "' 로는 18세 미만 등급을 확인할 수 없어 GENERAL 로 둘 수 없습니다.");
        } else if (derived.isEmpty() && asked == AgeClass.ADULT && note == null) {
            in.add(prefix + "note", "알아보지 못한 표기를 ADULT로 지정하려면 사유(note)가 필요합니다.");
        }
        return asked;
    }

    private static String language(String raw, String field, AdminInputs in) {
        if (raw == null || raw.isBlank()) {
            in.add(field, field + " 은 필수입니다.");
            return null;
        }
        try {
            return LanguageTags.normalize(raw);
        } catch (IllegalArgumentException e) {
            in.add(field, e.getMessage());
            return null;
        }
    }

    /**
     * URL 규칙 검사를 플랫폼 잠금 밖에서 먼저 하고(관리자 패턴이 느려도 잠금을 오래 쥐지 않게), 그 뒤 공유 잠금을 잡는다.
     * 그 사이 규칙이 바뀌었을 수 있으므로 호출 측은 잠근 행으로 한 번 더 검사한다(규칙이 같으면 캐시된 패턴으로 빠르다).
     */
    private PlatformRow checkedPlatform(String code, String url, String platformWorkId, String prefix, AdminInputs in) {
        PlatformRow unlocked = platformRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
        checkUrl(url, unlocked, platformWorkId, prefix, in);
        in.throwIfAny();
        return lockPlatform(code);
    }

    private PlatformRow lockPlatform(String code) {
        return platformRepository.lockForShare(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLATFORM_NOT_FOUND));
    }

    private static String checkUrl(String url, PlatformRow platform, String platformWorkId, String prefix,
            AdminInputs in) {
        PlatformUrls.Result result = PlatformUrls.validate(url, platform.allowedHosts(), platform.workUrlPattern(),
                platformWorkId);
        if (!result.ok()) {
            in.add(prefix + result.field(), result.reason());
            return null;
        }
        return result.normalizedUrl();
    }

    private LockedWork lockWork(long workId, Long version) {
        LockedWork work = workRepository.lockById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        if (!work.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.WORK_VERSION_CONFLICT, work.getVersion());
        }
        return work;
    }

    private void bump(long workId, long version) {
        if (workRepository.bumpVersion(workId, version) != 1) {
            throw new IllegalStateException("locked work version was not bumped: " + workId);
        }
    }

    private long insert(long workId, NewListing input, String url, long adminId) {
        Optional<Long> id = listingRepository.insertIfAbsent(workId, input.platformCode, input.platformWorkId,
                input.idScope, url, input.kind, input.languageCode, input.ageRaw, input.ageClass, input.verifiedAt,
                adminId, input.note);
        if (id.isEmpty()) {
            ListingRow existing = listingRepository.findByPlatformWorkId(input.platformCode, input.platformWorkId)
                    .orElseThrow(() -> new IllegalStateException("conflicting listing not visible"));
            throw new ListingAlreadyLinkedException(existing.workId(), existing.id());
        }
        return id.get();
    }

    private ListingRow ownedListing(long workId, long listingId) {
        return listingRepository.findById(listingId)
                .filter(row -> row.workId() == workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LISTING_NOT_FOUND));
    }

    private void checkMoveTarget(ListingRow current, Long movedTo, AdminInputs in) {
        if (movedTo.equals(current.id())) {
            in.add("movedToListingId", "자기 자신으로 이전할 수 없습니다.");
            return;
        }
        Optional<ListingRow> target = listingRepository.findById(movedTo);
        if (target.isEmpty() || !target.get().workId().equals(current.workId())) {
            in.add("movedToListingId", "같은 작품의 다른 연결만 이전 대상으로 지정할 수 있습니다.");
        } else if (target.get().status() == ListingStatus.MOVED) {
            in.add("movedToListingId", "이전 대상이 이미 다른 연결로 이전(MOVED)되었습니다. 최종 연결을 지정해 주세요.");
        }
    }

    private ListingResponse response(long listingId) {
        return ListingResponse.from(listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalStateException("listing not found right after write")));
    }

    private long workVersion(long workId) {
        List<Long> versions = jdbc.queryForList("SELECT version FROM works WHERE id = :id", Map.of("id", workId),
                Long.class);
        if (versions.isEmpty()) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND);
        }
        return versions.get(0);
    }

    private static List<String> warnings(PlatformRow platform, IdScope idScope, AgeClass ageClass) {
        List<String> warnings = new ArrayList<>();
        if (platform.status() == PlatformStatus.MERGING) {
            warnings.add("PLATFORM_MERGING");
        } else if (platform.status() == PlatformStatus.UNKNOWN) {
            warnings.add("PLATFORM_STATUS_UNKNOWN");
        }
        if (platform.workUrlPattern() == null || platform.urlRuleBasis() == UrlRuleBasis.UNVERIFIED) {
            warnings.add("URL_RULE_UNVERIFIED");
        }
        if (idScope == IdScope.UNKNOWN) {
            warnings.add("ID_SCOPE_UNKNOWN");
        }
        if (ageClass == AgeClass.UNKNOWN) {
            warnings.add("AGE_CLASS_UNKNOWN");
        }
        return warnings;
    }
}
