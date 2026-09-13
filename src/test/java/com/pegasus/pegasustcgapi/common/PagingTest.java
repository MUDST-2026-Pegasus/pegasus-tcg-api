package com.pegasus.pegasustcgapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    @DisplayName("a negative page and size become the first page of one")
    void negativesAreClamped() {
        Paging paging = Paging.of(-1, -1);

        assertThat(paging.page()).isZero();
        assertThat(paging.size()).isEqualTo(1);
        assertThat(paging.offset()).isZero();
    }

    @Test
    @DisplayName("size is capped")
    void sizeIsCapped() {
        assertThat(Paging.of(0, 5000).size()).isEqualTo(Paging.MAX_SIZE);
    }

    @Test
    @DisplayName("an offset past int range does not wrap negative")
    void offsetDoesNotOverflow() {
        // 21,474,837 x 100 is past Integer.MAX_VALUE; in int arithmetic it wraps negative
        // and Postgres answers OFFSET must not be negative.
        Paging paging = Paging.of(21_474_837, 100);

        assertThat(paging.offset()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("an ordinary page is untouched")
    void ordinaryPage() {
        Paging paging = Paging.of(3, 20);

        assertThat(paging.page()).isEqualTo(3);
        assertThat(paging.size()).isEqualTo(20);
        assertThat(paging.offset()).isEqualTo(60);
    }
}
