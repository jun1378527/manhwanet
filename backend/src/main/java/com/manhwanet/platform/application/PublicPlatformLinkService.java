package com.manhwanet.platform.application;

import com.manhwanet.platform.domain.AgeClass;
import com.manhwanet.platform.domain.IdScope;
import com.manhwanet.platform.domain.ListingKind;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.platform.domain.ListingRow;
import com.manhwanet.platform.domain.ListingStatus;
import com.manhwanet.platform.domain.PlatformRepository;
import com.manhwanet.platform.domain.PlatformRow;
import com.manhwanet.platform.domain.PlatformStatus;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.platform.dto.PublicPlatformLinkResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 작품 상세에 보여 줄 플랫폼 바로가기(작품 공개 여부는 호출하는 쪽이 확인한다).
 *
 * <p>보여 주는 연결의 조건(하나라도 어기면 뺀다):
 * <ol>
 *   <li>연결 상태가 ACTIVE. 종료(ENDED)·미확인(UNKNOWN)은 빼고, 이전(MOVED)된 옛 연결은 빼며 이전 대상 연결이 ACTIVE 면 그 연결이 나온다</li>
 *   <li>ID 단위(id_scope)가 확정됨(UNKNOWN 이 아님). 단위 미확정 연결은 관리자 확인 대기다(work-platform-admin-spec §5.2·§6.1)</li>
 *   <li>연결 등급이 GENERAL(공개 전환 조건과 같음). 성인·미확인 등급 연결은 공개 표시 정책 결정 전까지 링크하지 않는다(S-12)</li>
 *   <li>플랫폼이 종료(CLOSED)되지 않음</li>
 *   <li>저장된 URL 이 지금의 플랫폼 URL 규칙(https, 허용 호스트, 작품 URL 패턴과 외부 ID 일치)을 다시 통과. 정규화한 URL 을 쓴다</li>
 * </ol>
 * 같은 URL 은 한 번만, 본편(MAIN) → 번역판 → 시즌 → 판본(명세 §6.1), 같은 종류는 플랫폼 이름 → 연결 등록 순으로 둔다.
 */
@Service
@Transactional(readOnly = true)
public class PublicPlatformLinkService {

    /** 명세 §6.1 의 종류 순서 */
    private static final List<ListingKind> KIND_ORDER_LIST =
            List.of(ListingKind.MAIN, ListingKind.TRANSLATION, ListingKind.SEASON, ListingKind.EDITION);
    private static final Comparator<ListingKind> KIND_ORDER = Comparator.comparingInt(KIND_ORDER_LIST::indexOf);

    private final ListingRepository listingRepository;
    private final PlatformRepository platformRepository;

    public PublicPlatformLinkService(ListingRepository listingRepository, PlatformRepository platformRepository) {
        this.listingRepository = listingRepository;
        this.platformRepository = platformRepository;
    }

    public List<PublicPlatformLinkResponse> findPublicLinks(long workId) {
        Map<String, Optional<PlatformRow>> platforms = new HashMap<>();
        record Candidate(ListingRow listing, PlatformRow platform, String url) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ListingRow listing : listingRepository.findByWorkId(workId)) {
            if (listing.status() != ListingStatus.ACTIVE || listing.idScope() == IdScope.UNKNOWN
                    || listing.ageClass() != AgeClass.GENERAL) {
                continue;
            }
            PlatformRow platform = platforms.computeIfAbsent(listing.platformCode(), platformRepository::findByCode).orElse(null);
            if (platform == null || platform.status() == PlatformStatus.CLOSED) {
                continue;
            }
            PlatformUrls.Result checked = PlatformUrls.validate(listing.url(), platform.allowedHosts(),
                    platform.workUrlPattern(), listing.platformWorkId());
            if (!checked.ok()) {
                continue;
            }
            candidates.add(new Candidate(listing, platform, checked.normalizedUrl()));
        }
        candidates.sort(Comparator.comparing((Candidate c) -> c.listing().listingKind(), KIND_ORDER)
                .thenComparing(c -> c.platform().name())
                .thenComparing(c -> c.listing().id()));
        Set<String> seen = new HashSet<>();
        List<PublicPlatformLinkResponse> links = new ArrayList<>();
        for (Candidate c : candidates) {
            if (seen.add(c.url())) {
                links.add(new PublicPlatformLinkResponse(c.platform().code(), c.platform().name(), c.url(),
                        c.listing().listingKind(), c.listing().languageCode()));
            }
        }
        return links;
    }
}
