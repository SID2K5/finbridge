package com.finbridge.common;

import com.finbridge.logging.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Success envelope used by every endpoint:
 * { "data": ..., "meta": {...}, "correlationId": "...", "timestamp": "..." }.
 * "meta" is present only on paged list responses.
 */
public record ApiResponse<T>(T data, PageMeta meta, String correlationId, Instant timestamp) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null, CorrelationIdFilter.currentId(), Instant.now());
    }

    /** For a single object that carries one paged list inside it. */
    public static <T> ApiResponse<T> of(T data, PageMeta meta) {
        return new ApiResponse<>(data, meta, CorrelationIdFilter.currentId(), Instant.now());
    }

    public static <E, T> ApiResponse<List<T>> page(Page<E> page, Function<E, T> mapper) {
        List<T> items = page.getContent().stream().map(mapper).toList();
        PageMeta meta = new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        return new ApiResponse<>(items, meta, CorrelationIdFilter.currentId(), Instant.now());
    }

    public record PageMeta(int page, int size, long totalElements, int totalPages) {
    }
}
