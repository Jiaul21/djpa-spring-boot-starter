package com.djpa.core.dynamicquery.dto;

public enum Operator {

    EQUAL,
    NOT_EQUAL,

    CONTAINS,
    NOT_CONTAINS,

    STARTS_WITH,
    ENDS_WITH,

    GREATER_THAN,
    GREATER_THAN_EQUAL,
    LESS_THAN,
    LESS_THAN_EQUAL,

    BETWEEN,
    NOT_BETWEEN,

    IN,
    NOT_IN,
}