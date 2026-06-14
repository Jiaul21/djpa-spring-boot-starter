package com.djpa.core.dynamicquery.dto;


import java.util.List;

public record Filter(
        String field,
        Operator operator,
        List<String> values,
        String sort

) implements QueryFilter {

    @Override
    public String getField() {
        return field;
    }

    @Override
    public Operator getOperator() {
        return operator;
    }

    @Override
    public List<String> getValues() {
        return values;
    }

    @Override
    public String getSort() {
        return sort;
    }
}
