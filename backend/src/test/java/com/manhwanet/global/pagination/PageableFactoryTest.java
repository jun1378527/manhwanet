package com.manhwanet.global.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.global.error.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageableFactoryTest {

    @Test
    void defaultSortIsCreatedAtDescThenIdDesc() {
        Pageable pageable = PageableFactory.create(0, 20, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void blankSortUsesDefault() {
        assertThat(PageableFactory.create(0, 20, " ").getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void createdAtSortAddsIdTieBreakerInSameDirection() {
        assertThat(PageableFactory.create(1, 10, "createdAt,asc").getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        assertThat(PageableFactory.create(1, 10, "createdAt,DESC").getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void idSortHasNoTieBreaker() {
        assertThat(PageableFactory.create(0, 10, "id,desc").getSort()).isEqualTo(Sort.by(Sort.Order.desc("id")));
    }

    @Test
    void directionDefaultsToAsc() {
        assertThat(PageableFactory.create(0, 10, "id").getSort()).isEqualTo(Sort.by(Sort.Order.asc("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "title,asc", "createdAt,up", "createdAt,asc,id,desc", "id,", ",asc", "CREATEDAT"})
    void rejectsUnsupportedSort(String sort) {
        assertThatThrownBy(() -> PageableFactory.create(0, 10, sort))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors())
                        .singleElement()
                        .satisfies(error -> assertThat(error.field()).isEqualTo("sort")));
    }

    @Test
    void rejectsOutOfRangePageAndSize() {
        assertThatThrownBy(() -> PageableFactory.create(-1, 10, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> PageableFactory.create(0, 0, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> PageableFactory.create(0, 101, null)).isInstanceOf(InvalidInputException.class);
        assertThat(PageableFactory.create(0, 100, null).getPageSize()).isEqualTo(100);
    }

    @Test
    void fixedSortValidatesPageSizeAndOffset() {
        Sort order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Pageable pageable = PageableFactory.createWithFixedSort(2, 10, 50, order);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(order);
        assertThat(PageableFactory.createWithFixedSort(0, 50, 50, order).getPageSize()).isEqualTo(50);

        assertThatThrownBy(() -> PageableFactory.createWithFixedSort(-1, 10, 50, order))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> PageableFactory.createWithFixedSort(0, 0, 50, order))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> PageableFactory.createWithFixedSort(0, 51, 50, order))
                .isInstanceOf(InvalidInputException.class);
        // offset 이 int 범위를 넘으면 JPA 가 처리할 수 없으므로 거절
        assertThat(PageableFactory.createWithFixedSort(Integer.MAX_VALUE / 50, 50, 50, order).getOffset())
                .isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> PageableFactory.createWithFixedSort(Integer.MAX_VALUE / 50 + 1, 50, 50, order))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---------- #20: offset(page × size) 지원 범위 ----------

    @Test
    void offsetUpToIntMaxIsAcceptedForEverySize() {
        for (int size : new int[] {1, 7, 10, 20, 50, 100}) {
            int maxPage = PageableFactory.maxPage(size);
            assertThat((long) maxPage * size).isLessThanOrEqualTo(Integer.MAX_VALUE);
            assertThat(((long) maxPage + 1) * size).isGreaterThan(Integer.MAX_VALUE);
            Pageable pageable = PageableFactory.create(maxPage, size, null);
            assertThat(pageable.getPageNumber()).isEqualTo(maxPage);
            assertThat(pageable.getPageSize()).isEqualTo(size);
            assertThat(pageable.getOffset()).isLessThanOrEqualTo(Integer.MAX_VALUE);
        }
        // size 1 이면 int 최대 page 까지 지원한다 (offset = Integer.MAX_VALUE)
        assertThat(PageableFactory.create(Integer.MAX_VALUE, 1, null).getOffset()).isEqualTo(Integer.MAX_VALUE);
        assertThat(PageableFactory.maxPage(100)).isEqualTo(21_474_836);
        assertThat(PageableFactory.maxPage(20)).isEqualTo(107_374_182);
        assertThat(PageableFactory.maxPage(50)).isEqualTo(42_949_672);
    }

    @Test
    void offsetPastIntMaxIsRejectedOnPageWithoutOverflowOrClamping() {
        for (int size : new int[] {2, 20, 100}) {
            int tooLarge = PageableFactory.maxPage(size) + 1;
            assertThatThrownBy(() -> PageableFactory.create(tooLarge, size, null))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.getFieldErrors()).hasSize(1);
                        assertThat(e.getFieldErrors().get(0).field()).isEqualTo("page");
                        assertThat(e.getFieldErrors().get(0).reason())
                                .contains("0 이상 " + PageableFactory.maxPage(size) + " 이하");
                    });
        }
        // int 곱셈이면 음수로 넘쳐 통과했을 값: 2147483647 × 100 = 214748364700 (int 로는 -100)
        assertThat(Integer.MAX_VALUE * 100).isNegative();
        assertThatThrownBy(() -> PageableFactory.create(Integer.MAX_VALUE, 100, null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> PageableFactory.create(Integer.MAX_VALUE, 2, null))
                .isInstanceOf(InvalidInputException.class);
        // 고정 정렬 목록도 같은 규칙
        Sort order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(PageableFactory.createWithFixedSort(PageableFactory.maxPage(50), 50, 50, order).getOffset())
                .isLessThanOrEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> PageableFactory.createWithFixedSort(PageableFactory.maxPage(50) + 1, 50, 50, order))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void pageAndSizeChecksStillComeFirst() {
        assertThatThrownBy(() -> PageableFactory.create(-1, 20, null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.getFieldErrors().get(0).field()).isEqualTo("page"));
        assertThatThrownBy(() -> PageableFactory.create(Integer.MAX_VALUE, 101, null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.getFieldErrors().get(0).field()).isEqualTo("size"));
        assertThatThrownBy(() -> PageableFactory.create(0, 0, null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.getFieldErrors().get(0).field()).isEqualTo("size"));
    }
}
