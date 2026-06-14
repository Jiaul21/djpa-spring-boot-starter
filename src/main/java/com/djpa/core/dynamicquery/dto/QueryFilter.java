package com.djpa.core.dynamicquery.dto;

import java.util.List;

public interface QueryFilter {

    String getField();

    Operator getOperator();

    List<String> getValues();

    String getSort();
}
