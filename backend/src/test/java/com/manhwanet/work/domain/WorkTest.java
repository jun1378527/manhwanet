package com.manhwanet.work.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkTest {

    private static final List<WorkCreditDraft> ONE_CREDIT = List.of(new WorkCreditDraft(1L, CreatorRole.STORY));

    @Test
    void createsWorkWithTitlesAndCredits() {
        Work work = Work.create(WorkFormat.PAGE_COMIC, "JP", "줄거리",
                List.of(new WorkTitleDraft("月光騎士団", "ja", true), new WorkTitleDraft("Moonlight Knights", "en", false)),
                List.of(new WorkCreditDraft(1L, CreatorRole.STORY), new WorkCreditDraft(1L, CreatorRole.ART)));

        assertThat(work.getTitles()).hasSize(2);
        assertThat(work.getTitles()).filteredOn(WorkTitle::isPrimary).singleElement()
                .extracting(WorkTitle::getTitle).isEqualTo("月光騎士団");
        assertThat(work.getCreators()).extracting(WorkCreator::getRole)
                .containsExactly(CreatorRole.STORY, CreatorRole.ART);
    }

    @Test
    void requiresExactlyOnePrimaryTitle() {
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", false)), ONE_CREDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", true), new WorkTitleDraft("B", "en", true)), ONE_CREDIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOneTitleAndCredit() {
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null, List.of(), ONE_CREDIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", true)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateTitleInSameLanguageButAllowsOtherLanguage() {
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", true), new WorkTitleDraft("A", "ko", false)), ONE_CREDIT))
                .isInstanceOf(IllegalArgumentException.class);

        Work work = Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", true), new WorkTitleDraft("A", "en", false)), ONE_CREDIT);
        assertThat(work.getTitles()).hasSize(2);
    }

    @Test
    void rejectsDuplicateCreatorRole() {
        assertThatThrownBy(() -> Work.create(WorkFormat.WEBTOON, null, null,
                List.of(new WorkTitleDraft("A", "ko", true)),
                List.of(new WorkCreditDraft(1L, CreatorRole.STORY), new WorkCreditDraft(1L, CreatorRole.STORY))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
