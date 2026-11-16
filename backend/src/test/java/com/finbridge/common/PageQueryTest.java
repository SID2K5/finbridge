package com.finbridge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finbridge.exception.InvalidRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageQueryTest {

    private static final Map<String, String> ALLOWED = Map.of("status", "invoiceStatus", "invoiceDate", "invoiceDate");
    private static final Sort DEFAULT = Sort.by("id");

    @Test
    void usesTheDefaultSortWhenNoneIsRequested() {
        Pageable pageable = PageQuery.of(2, 25, null, ALLOWED, DEFAULT);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort()).isEqualTo(DEFAULT);
    }

    @Test
    void mapsAnAllowedApiFieldToItsEntityProperty() {
        Pageable pageable = PageQuery.of(0, 20, "status,desc", ALLOWED, DEFAULT);

        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "invoiceStatus"));
    }

    @Test
    void sortDirectionDefaultsToAscending() {
        Pageable pageable = PageQuery.of(0, 20, "invoiceDate", ALLOWED, DEFAULT);

        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "invoiceDate"));
    }

    @Test
    void refusesToSortByAFieldThatIsNotOnTheAllowList() {
        assertThatThrownBy(() -> PageQuery.of(0, 20, "passwordHash,asc", ALLOWED, DEFAULT))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("passwordHash");
    }

    @Test
    void refusesAnUnknownDirection() {
        assertThatThrownBy(() -> PageQuery.of(0, 20, "status,sideways", ALLOWED, DEFAULT))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesPageSizesOutsideOneToOneHundred() {
        assertThatThrownBy(() -> PageQuery.of(0, 0, null, ALLOWED, DEFAULT))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 101, null, ALLOWED, DEFAULT))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(PageQuery.of(0, 100, null, ALLOWED, DEFAULT).getPageSize()).isEqualTo(100);
    }

    @Test
    void refusesANegativePage() {
        assertThatThrownBy(() -> PageQuery.of(-1, 20, null, ALLOWED, DEFAULT))
                .isInstanceOf(InvalidRequestException.class);
    }
}
