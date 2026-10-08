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
}
