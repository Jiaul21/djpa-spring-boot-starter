package com.djpa.core.dynamicquery.jpql;

import com.djpa.core.dynamicquery.dto.Filter;
import com.djpa.core.dynamicquery.dto.TableRequest;
import com.djpa.core.dynamicquery.dto.TableResponse;
import com.querydsl.core.types.Path;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class GenericJPQLSelect {

    @PersistenceContext
    private EntityManager em;


    public TableResponse<?> select(String form, TableRequest request, Map<String, Path<?>> alias) {

        String select = buildSelect(alias);

        QueryCondition condition = buildCondition(request.filters(), alias);

        System.out.println("Conditions:\n" + condition);

        StringBuilder where = new StringBuilder();

        if (!condition.conditions().isEmpty()) {
            where.append(" WHERE ").append(String.join(" AND ", condition.conditions()));
        }

        // =========================
        // ORDER BY
        // =========================
        List<String> orders = buildOrders(request.filters(), alias);
        StringBuilder orderBy = new StringBuilder();
        if (!orders.isEmpty()) {
            orderBy.append(" ORDER BY ").append(String.join(", ", orders));
        }

        // =========================
        // EXECUTION
        // =========================

        String jpql = select + form + where + orderBy;

        System.out.println("Generated JPQL: \n" + jpql);

        TypedQuery<Tuple> query = em.createQuery(jpql, Tuple.class);
        condition.parameters().forEach(query::setParameter);
        query.setFirstResult((int)(request.page() * request.size()));
        query.setMaxResults((int)request.size());

        List<Map<String, Object>> list = query.getResultList().stream().map(this::toMap).toList();

        // =========================
        // TOTAL COUNT QUERY
        // =========================
        TypedQuery<Long> countQuery = em.createQuery("SELECT COUNT(*) " + form + where, Long.class);
        condition.parameters().forEach(countQuery::setParameter);
        long total = countQuery.getSingleResult();

//        return TableResponse.<Map<String, Object>>builder()
//                .content(list)
//                .page(request.page())
//                .size(request.size())
//                .totalElements(total)
//                .totalPages((int) Math.ceil((double) total / request.size()))
//                .build();
        return null;
    }

    public <T> String buildSelect(Map<String, Path<?>> alias) {
        StringBuilder select = new StringBuilder("SELECT ");
        int i = 0;
        for (Map.Entry<String, Path<?>> entry : alias.entrySet()) {
            if (i++ > 0) select.append(", ");
            Path<?> value = entry.getValue();
            boolean collection = Collection.class.isAssignableFrom(value.getType());
            if (collection) {
                select.append(value.getMetadata().getName());
            } else select.append(entry.getValue().toString());

            select.append(" AS ").append(entry.getKey());
        }
        select.append(" ");
        return select.toString();
    }

    private QueryCondition buildCondition(List<Filter> filters, Map<String, Path<?>> alias) {
        if (filters == null) return new QueryCondition(List.of(), Map.of());

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        int paramIndex = 0;
        for (Filter f : filters) {
            if (!alias.containsKey(f.field())) continue;

            Path<?> expression = alias.get(f.field());
            String aliasField = expression.toString();
            boolean collection = Collection.class.isAssignableFrom(expression.getType());

            String param = "p" + paramIndex++;
            switch (f.operator()) {

                case EQUAL -> {
                    if (collection) {
                        conditions.add(":" + param + " MEMBER OF " + aliasField);
                        params.put(param, convertValue(expression, f.values().get(0)));
                    } else {
                        conditions.add(aliasField + " = :" + param);
                        params.put(param, convertValue(expression, f.values().get(0)));
                    }
                }

                case NOT_EQUAL -> {
                    if (collection) {
                        conditions.add(":" + param + " NOT MEMBER OF " + aliasField);
                        params.put(param, convertValue(expression, f.values().get(0)));
                    } else {
                        conditions.add(aliasField + " <> :" + param);
                        params.put(param, convertValue(expression, f.values().get(0)));
                    }
                }

                case CONTAINS -> {
                    if (collection) {
                        String elementAlias = "e" + paramIndex;
                        conditions.add("EXISTS (" + "SELECT " + elementAlias + " FROM " + aliasField + " " + elementAlias +
                                " WHERE LOWER(" + elementAlias + ") LIKE :" + param + ")");
                        params.put(param, "%" + f.values().get(0).toLowerCase() + "%");
                    } else {
                        conditions.add("LOWER(" + aliasField + ") LIKE :" + param);
                        params.put(param, "%" + f.values().get(0).toLowerCase() + "%");
                    }
                }

                case STARTS_WITH -> {
                    conditions.add("LOWER(" + aliasField + ") LIKE :" + param);
                    params.put(param, f.values().get(0).toLowerCase() + "%");
                }

                case ENDS_WITH -> {
                    conditions.add("LOWER(" + aliasField + ") LIKE :" + param);
                    params.put(param, "%" + f.values().get(0).toLowerCase());
                }

                case GREATER_THAN -> {
                    conditions.add(aliasField + " > :" + param);
                    params.put(param, convertValue(expression, f.values().get(0)));
                }

                case GREATER_THAN_EQUAL -> {
                    conditions.add(aliasField + " >= :" + param);
                    params.put(param, convertValue(expression, f.values().get(0)));
                }

                case LESS_THAN -> {
                    conditions.add(aliasField + " < :" + param);
                    params.put(param, convertValue(expression, f.values().get(0)));
                }

                case LESS_THAN_EQUAL -> {
                    conditions.add(aliasField + " <= :" + param);
                    params.put(param, convertValue(expression, f.values().get(0)));
                }

                case BETWEEN -> {
                    if (f.values().size() != 2) continue;
                    String param2 = "p" + paramIndex++;
                    conditions.add(aliasField + " BETWEEN :" + param + " AND :" + param2);
                    params.put(param, convertValue(expression, f.values().get(0)));
                    params.put(param2, convertValue(expression, f.values().get(1)));
                }

                case IN -> {
                    if (f.values().isEmpty()) continue;
                    if (collection) {
                        String elementAlias = "e" + paramIndex;
                        conditions.add("EXISTS (" + "SELECT " + elementAlias + " FROM " + aliasField + " " + elementAlias +
                                " WHERE " + elementAlias + " IN :" + param + ")");
                        params.put(param, f.values());
                    } else {
                        conditions.add(aliasField + " IN :" + param);
                        params.put(param, f.values().stream().map(v -> convertValue(expression, v)).toList());
                    }
                }
            }
        }
        return new QueryCondition(conditions, params);
    }

    private List<String> buildOrders(List<Filter> filters, Map<String, Path<?>> alias) {
        if (filters == null) return List.of();

        List<String> orders = new ArrayList<>();
        for (Filter f : filters) {
            if (f.sort() == null || !alias.containsKey(f.field())) continue;

            String sort = f.sort().trim().toUpperCase();
            if (!sort.equals("ASC") && !sort.equals("DESC")) continue;

            String aliasField = alias.get(f.field()).toString();
            orders.add(aliasField + " " + sort);
        }
        return orders;
    }

    private Map<String, Object> toMap(Tuple tuple) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (TupleElement<?> element : tuple.getElements()) {
            String alias = element.getAlias();
            Object value = tuple.get(alias);
            map.put(alias, value);
        }
        return map;
    }

    private Object convertValue(Path<?> path, String value) {

        Class<?> type = path.getType();
//        if (Collection.class.isAssignableFrom(type) || path instanceof CollectionExpressionBase<?, ?>) {
//            ListPath<String, StringPath> listPath = (ListPath<String, StringPath>) path;
//            type=listPath.getElementType();
//        }

        if (type == Long.class || type == long.class)
            return Long.valueOf(value);

        if (type == Integer.class || type == int.class)
            return Integer.valueOf(value);

        if (type == Double.class || type == double.class)
            return Double.valueOf(value);

        if (type == Float.class || type == float.class)
            return Float.valueOf(value);

        if (type == Boolean.class || type == boolean.class)
            return Boolean.valueOf(value);

        if (type.isEnum())
            return Enum.valueOf((Class<Enum>) type, value);

        if (type == BigDecimal.class)
            return new BigDecimal(value);

        if (type == BigInteger.class)
            return new BigInteger(value);

        if (type == LocalDate.class)
            return LocalDate.parse(value);

        if (type == LocalDateTime.class)
            return LocalDateTime.parse(value);

        if (type == LocalTime.class)
            return LocalTime.parse(value);

        if (type == Date.class) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
            } catch (ParseException e) {
                return value;
            }
        }

        if (type == Instant.class)
            return Instant.parse(value);

        if (type == UUID.class)
            return UUID.fromString(value);

        return value;
    }
}