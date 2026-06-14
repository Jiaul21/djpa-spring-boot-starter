package com.djpa.core.dynamicquery.querydsl;


import com.djpa.core.dynamicquery.dto.Operator;

import java.util.List;

public interface QueryFilter {

    String getField();

    Operator getOperator();

    List<String> getValues();

    String getSort();
}
