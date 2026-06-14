package com.djpa.core.dynamicquery.dto;

import java.util.List;

public record TableRequest(
        long page,
        long size,
        List<Filter> filters
) {
}
