package com.djpa.core.dynamicquery.dto;

//import lombok.Builder;

import java.util.List;

//@Builder
public record TableResponse<T>(
        List<T> content,
        long contentSize,
        long totalElements,
        long totalPages,
        long page,
        long size,
        boolean first,
        boolean last
) {
}
