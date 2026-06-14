package com.djpa.core.dynamicquery.querydsl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;

public record QueryFilterResult(
        BooleanBuilder condition,
        OrderSpecifier[] orders
) {
}
