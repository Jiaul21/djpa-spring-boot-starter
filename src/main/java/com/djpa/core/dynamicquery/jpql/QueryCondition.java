package com.djpa.core.dynamicquery.jpql;

import java.util.List;
import java.util.Map;

public record QueryCondition(
        List<String> conditions,
        Map<String, Object> parameters
) {
}
