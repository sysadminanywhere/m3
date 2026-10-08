package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class MessageSearchTest {
    @Test void roundTripsShareableFiltersAndTrimsText() {
        var filters = new MessageSearch(42L, MessageStatus.FAILED, " source ", "target", LocalDate.of(2026,10,1), LocalDate.of(2026,10,6));
        var params = filters.parameters().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        assertThat(MessageSearch.fromParameters(params)).isEqualTo(filters);
        assertThat(filters.source()).isEqualTo("source");
        assertThat(MessageSearch.empty().parameters()).isEmpty();
    }
    @Test void rejectsMalformedOrImpossibleFilters() {
        for (String id : List.of("0", "-1", "abc", "9223372036854775808"))
            assertThatThrownBy(() -> MessageSearch.fromParameters(Map.of("id", List.of(id)))).isInstanceOf(IllegalArgumentException.class);
        for (String date : List.of("2026-02-30", "yesterday", "0000-01-01"))
            assertThatThrownBy(() -> MessageSearch.fromParameters(Map.of("from", List.of(date)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSearch.fromParameters(Map.of("status", List.of("UNKNOWN")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSearch.fromParameters(Map.of("source", List.of("x".repeat(201))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageSearch.fromParameters(Map.of("from", List.of("2026-10-06"), "to", List.of("2026-10-05")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void searchTreatsSqlWildcardsAsLiteralCharacters() {
        assertThat(MessageSearch.pattern("A%_\\B")).isEqualTo("%a\\%\\_\\\\b%");
    }
}
