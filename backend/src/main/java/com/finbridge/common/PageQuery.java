package com.finbridge.common;

import com.finbridge.exception.InvalidRequestException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a Pageable from request parameters. Sorting is only allowed on an explicit
 * allow-list (API field name -> entity property), so a caller can never sort on an
 * arbitrary or sensitive column.
 */
public final class PageQuery {

    public static final int MAX_SIZE = 100;

    private PageQuery() {
    }

    public static Pageable of(int page, int size, String sort, Map<String, String> allowedSorts, Sort defaultSort) {
        if (page < 0) {
            throw new InvalidRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page, size, parseSort(sort, allowedSorts, defaultSort));
    }

    private static Sort parseSort(String sort, Map<String, String> allowedSorts, Sort defaultSort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",");
        if (parts.length > 2) {
            throw new InvalidRequestException("sort must look like field,asc or field,desc");
        }
        String property = allowedSorts.get(parts[0].trim());
        if (property == null) {
            throw new InvalidRequestException("Cannot sort by '" + parts[0].trim() + "'. Allowed: "
                    + String.join(", ", allowedSorts.keySet().stream().sorted().toList()));
        }
        String direction = parts.length == 2 ? parts[1].trim().toLowerCase() : "asc";
        return switch (direction) {
            case "asc" -> Sort.by(Sort.Direction.ASC, property);
            case "desc" -> Sort.by(Sort.Direction.DESC, property);
            default -> throw new InvalidRequestException("sort direction must be asc or desc");
        };
    }
}
