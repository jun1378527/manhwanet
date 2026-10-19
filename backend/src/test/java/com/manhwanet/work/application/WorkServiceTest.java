package com.manhwanet.work.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.platform.application.PublicPlatformLinkService;
import com.manhwanet.platform.application.WorkListingService;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.provenance.application.ProvenanceQuery;
import com.manhwanet.provenance.application.ProvenanceWriter;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.work.domain.Work;
import com.manhwanet.work.domain.WorkCreatorRepository;
import com.manhwanet.work.domain.WorkRepository;
import com.manhwanet.work.domain.WorkTitleRepository;
import com.manhwanet.work.dto.CreateWorkRequest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB 무결성 오류 변환 정책: 예상한 중복 제약만 409, 나머지는 원래 예외를 그대로 던진다.
 */
class WorkServiceTest {

    private final WorkRepository workRepository = mock(WorkRepository.class);
    private final CreatorService creatorService = mock(CreatorService.class);
    private final WorkService workService = new WorkService(
            workRepository, mock(WorkTitleRepository.class), mock(WorkCreatorRepository.class), creatorService,
            mock(WorkRatingSummaryQuery.class), mock(WorkReviewCountQuery.class), mock(PublicPlatformLinkService.class),
            mock(WorkListingService.class), mock(ListingRepository.class),
            mock(ProvenanceWriter.class), mock(ProvenanceQuery.class), mock(AdminChangeLog.class));

    private final CreateWorkRequest request = new CreateWorkRequest(
            "WEBTOON", null, null,
            List.of(new CreateWorkRequest.TitleRequest("달빛 기사단", "ko", true)),
            List.of(new CreateWorkRequest.CreditRequest(1L, "STORY")),
            null, null, null,
            List.of(evidence("TITLE"), evidence("CREDIT")), null);

    private static EvidenceRequest evidence(String field) {
        return new EvidenceRequest(field, null, null, "STAFF", null, null, Instant.parse("2026-10-12T00:00:00Z"), null);
    }

    @BeforeEach
    void setUp() {
        given(creatorService.findMissingIds(anyCollection())).willReturn(Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uk_work_titles_work_language_title",
            "uk_work_titles_primary_per_work",
            "uk_work_creators_work_creator_role"})
    void expectedDuplicateConstraintBecomesConflict(String constraintName) {
        given(workRepository.saveAndFlush(any(Work.class))).willThrow(violation(constraintName));

        assertThatThrownBy(() -> workService.register(request, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void otherIntegrityViolationIsRethrownUnchanged() {
        DataIntegrityViolationException original = violation("fk_work_creators_creator");
        given(workRepository.saveAndFlush(any(Work.class))).willThrow(original);

        assertThatThrownBy(() -> workService.register(request, 1L)).isSameAs(original);
    }

    @Test
    void integrityViolationWithoutConstraintNameIsRethrownUnchanged() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("unknown");
        given(workRepository.saveAndFlush(any(Work.class))).willThrow(original);

        assertThatThrownBy(() -> workService.register(request, 1L)).isSameAs(original);
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException("violation", "23505"), constraintName));
    }
}
