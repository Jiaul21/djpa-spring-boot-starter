package com.djpa.core.dynamicquery.querydsl;

import com.djpa.core.dynamicquery.dto.Operator;
import com.djpa.core.dynamicquery.dto.TableRequest;
import com.djpa.core.dynamicquery.dto.TableResponse;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.*;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.SimpleExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import static com.querydsl.core.types.Ops.BETWEEN;

@Service
//@RequiredArgsConstructor
public class QueryUtil {

    private JPAQueryFactory queryFactory;
    @PersistenceContext
    private EntityManager entityManager;

    public <T> List<T> fetchResult(JPAQuery<?> baseQuery, Class<T> projectionClass, Map<String, SimpleExpression<?>> simpleExpressionMap, TableRequest request, QueryFilterResult queryFilter) {

        if (projectionClass.isRecord()) {
            return baseQuery.clone(entityManager).select(Projections.constructor(projectionClass, buildExpression(simpleExpressionMap)))
                    .offset(request.page() * request.size()).limit(request.size())
                    .orderBy(queryFilter.orders())
                    .fetch();
        }
        return baseQuery.clone(entityManager).select(Projections.bean(projectionClass, buildExpression(simpleExpressionMap)))
                .offset(request.page() * request.size()).limit(request.size())
                .orderBy(queryFilter.orders())
                .fetch();
    }

    public <U> U fetchCount(JPAQuery<?> baseQuery, Expression<U> expr) {
        return baseQuery.clone(entityManager)
                .select(expr)
                .fetchOne();
    }

    public SimpleExpression<?>[] buildExpression(Map<String, SimpleExpression<?>> fieldExp) {
        List<? extends SimpleExpression<?>> exp =
                fieldExp.entrySet()
                        .stream()
                        .map(ex -> ex.getValue().as(ex.getKey()))
                        .toList();

        return exp.toArray(new SimpleExpression<?>[0]);
    }


    public QueryFilterResult filter(List<? extends QueryFilter> filters, Map<String, SimpleExpression<?>> map) {
        BooleanBuilder condition = new BooleanBuilder();
        List<OrderSpecifier<?>> orders = new ArrayList<>();

        for (QueryFilter f : filters) {
            if (map.containsKey(f.getField())) {
                SimpleExpression<?> simpleExpression = map.get(f.getField());

                if (f.getValues() != null && !f.getValues().isEmpty())
                    condition.and(buildPredicate(f.getOperator(), f.getValues(), simpleExpression));

                if (f.getSort() != null && !f.getSort().isEmpty()) {
                    Order order = "DESC".equalsIgnoreCase(f.getSort()) ? Order.DESC : Order.ASC;
                    OrderSpecifier<?> orderSpecifier = orderSpecifier(order, simpleExpression);
                    orders.add(orderSpecifier);
                }
            }
        }
        return new QueryFilterResult(condition, orders.toArray(new OrderSpecifier[0]));
    }


    private Predicate buildPredicate(Operator operator, List<String> values, SimpleExpression<?> path) {

        return switch (operator) {
            case EQUAL -> eq(values.get(0), path);
            case NOT_EQUAL -> neq(values.get(0), path);
            case CONTAINS -> contains(values.get(0), path);
            case STARTS_WITH -> startsWith(values.get(0), path);
            case ENDS_WITH -> endsWith(values.get(0), path);
            case GREATER_THAN -> gt(values.get(0), path);
            case GREATER_THAN_EQUAL -> gte(values.get(0), path);
            case LESS_THAN -> lt(values.get(0), path);
            case LESS_THAN_EQUAL -> lte(values.get(0), path);
            case BETWEEN -> between(values.get(0), values.get(1), path);
            case IN -> in(values, path);
            default -> null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public OrderSpecifier<?> orderSpecifier(Order order, SimpleExpression<?> path) {
        return new OrderSpecifier(order, path);
    }

    private Predicate eq(String value, SimpleExpression<?> path) {
        return Expressions.predicate(Ops.EQ, path, Expressions.constant(convertValue(path, value)));
    }

    private Predicate neq(String v, SimpleExpression<?> path) {
        return eq(v, path).not();
    }

    private Predicate contains(String value, SimpleExpression<?> path) {
        return asStringExpression(path).containsIgnoreCase(value);
    }

    private Predicate startsWith(String value, SimpleExpression<?> path) {
        return asStringExpression(path).startsWithIgnoreCase(value);
    }

    private Predicate endsWith(String value, SimpleExpression<?> path) {
        return asStringExpression(path).endsWithIgnoreCase(value);
    }

    private Predicate between(String val1, String val2, SimpleExpression<?> path) {
        return Expressions.predicate(
                BETWEEN, path,
                Expressions.constant(convertValue(path, val1)),
                Expressions.constant(convertValue(path, val2)));
    }

    private Predicate in(List<String> values, SimpleExpression<?> path) {
        return inExpression(path, values.stream().map(value -> convertValue(path, value)).toList());
    }

    private Predicate gt(String v, SimpleExpression<?> path) {
        return compare(Ops.GT, v, path);
    }

    private Predicate gte(String v, SimpleExpression<?> path) {
        return compare(Ops.GOE, v, path);
    }

    private Predicate lt(String v, SimpleExpression<?> path) {
        return compare(Ops.LT, v, path);
    }

    private Predicate lte(String v, SimpleExpression<?> path) {
        return compare(Ops.LOE, v, path);
    }

    private Predicate compare(Ops operator, String value, SimpleExpression<?> path) {
        return Expressions.predicate(operator, path, Expressions.constant(convertValue(path, value)));
    }


    @SuppressWarnings({"rawtypes", "unchecked"})
    private Predicate inExpression(SimpleExpression<?> path, List<?> values) {
        return ((SimpleExpression) path).in(values);
    }

    private StringExpression asStringExpression(SimpleExpression<?> path) {
        if (path instanceof StringExpression stringExpression) {
            return stringExpression;
        }
        return Expressions.stringTemplate("str({0})", path);
    }


    private Object convertValue(SimpleExpression<?> path, String value) {

        Class<?> type = path.getType();

        if (type == Long.class || type == long.class) return Long.valueOf(value);
        if (type == Integer.class || type == int.class) return Integer.valueOf(value);
        if (type == Double.class || type == double.class) return Double.valueOf(value);
        if (type == Float.class || type == float.class) return Float.valueOf(value);
        if (type == Boolean.class || type == boolean.class) return Boolean.valueOf(value);
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, value);
        if (type == BigDecimal.class) return new BigDecimal(value);
        if (type == BigInteger.class) return new BigInteger(value);
        if (type == LocalDate.class) return LocalDate.parse(value);
        if (type == LocalDateTime.class) return LocalDateTime.parse(value);
        if (type == LocalTime.class) return LocalTime.parse(value);
        if (type == Date.class) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
            } catch (ParseException e) {
                return value;
            }
        }
        if (type == Instant.class) return Instant.parse(value);
        if (type == UUID.class) return UUID.fromString(value);

        return value;
    }

    public <ID, E> Map<ID, List<E>> fetchCollection(EntityPath<?> from, SimpleExpression<ID> idField, CollectionExpression<?, E> collection, Class<E> collectionElementType, Collection<ID> ids) {

        Path<E> elementPath = Expressions.path(collectionElementType, "element");
        List<Tuple> rows = queryFactory
                .select(idField, elementPath)
                .from(from)
                .leftJoin(collection, elementPath)
                .where(idField.in(ids))
                .fetch();

        Map<ID, List<E>> result = new LinkedHashMap<>();

        for (Tuple row : rows) {
            ID id = row.get(idField);
            E element = row.get(elementPath);

            result.computeIfAbsent(id, k -> new ArrayList<>()).add(element);
        }
        return result;
    }


    public <T> TableResponse<T> tableResponse(List<T> content, Long count, TableRequest request) {

        long totalElements = count == null ? 0 : count;
        int totalPages = (int) Math.ceil((double) totalElements / request.size());

//        return TableResponse.<T>builder()
//                .content(content)
//                .contentSize(content.size())
//                .totalElements(totalElements)
//                .totalPages(totalPages)
//                .page(request.page())
//                .size(request.size())
//                .first(request.page() == 0)
//                .last(request.page() >= totalPages - 1)
//                .build();
        return null;
    }
}
