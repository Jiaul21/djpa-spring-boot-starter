package com.djpa.core.helper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GenericUpdateImpl<E, ID> implements GenericUpdate<E, ID> {

    protected final Class<E> entityClass;
    @PersistenceContext
    protected EntityManager entityManager;

    public GenericUpdateImpl(Class<E> entityClass) {
        this.entityClass = entityClass;
    }

    @Override
    @Transactional
    public E updateEntity(ID id, Consumer<E> updater) {
        Objects.requireNonNull(updater, "Updater object must not be null.");

        E entity = entityManager.find(entityClass, id);
        if (entity == null)
            throw new EntityNotFoundException(entityClass.getSimpleName() + " entity reference not found for id=" + id);
        updater.accept(entity);
        return entity;
    }

    @Override
    @Transactional
    public int updateFieldByID(ID id, String fieldName, Object value) {
        String ql = "UPDATE " + entityClass.getSimpleName() + " e SET e." + fieldName + " = :value WHERE e.id = :id";
        int updated = entityManager.createQuery(ql)
                .setParameter("value", value)
                .setParameter("id", id)
                .executeUpdate();

        entityManager.clear();
        return updated;
    }

    @Override
    @Transactional
    public int updateFieldsByIDIn(Collection<ID> ids, Map<String, Object> fields) {
        if (ids==null || ids.isEmpty() || fields == null || fields.isEmpty())
            throw new IllegalArgumentException("Update query failed because of ids or fields is null or empty. ids="+ids+", fields="+fields);

        StringBuilder jpql = new StringBuilder("UPDATE " + entityClass.getSimpleName() + " e SET ");

        int i = 0;
        for (String field : fields.keySet()) {
            if (i++ > 0) jpql.append(", ");
            jpql.append("e.").append(field).append(" = :").append(field);
        }
        jpql.append(" WHERE e.id IN :ids");

        Query query = entityManager.createQuery(jpql.toString());
        query.setParameter("ids", ids);
        fields.forEach(query::setParameter);

        int updated = query.executeUpdate();
        entityManager.clear();
        return updated;
    }

    @Override
    @Transactional
    public int updateField(String cName, Object cValue, String fieldName, Object fieldValue) {
        String ql = "UPDATE " + entityClass.getSimpleName() + " e SET e." + fieldName + " = :value WHERE e." + cName + " = :c";
        int updated = entityManager.createQuery(ql)
                .setParameter("value", fieldValue)
                .setParameter("c", cValue)
                .executeUpdate();

        entityManager.clear();
        return updated;
    }


//    @Override
//    @Transactional
//    public int updateFieldForBulk(String fieldName, Object value, Collection<ID> ids) {
//        if (ids == null) return 0;
//        ids = ids.stream().filter(Objects::nonNull).toList();
//        if (ids.isEmpty()) return 0;
//
//        String ql = "UPDATE " + entityClass.getSimpleName() + " e SET e." + fieldName + " = :value WHERE e.id IN :ids";
//        int updated = entityManager.createQuery(ql)
//                .setParameter("value", value)
//                .setParameter("ids", ids)
//                .executeUpdate();
//
//        entityManager.clear();
//        return updated;
//    }

//    @Override
//    @Transactional
//    public int updateFieldForBulk(String fieldName, List<? extends IdFieldValue> fields) {
//        if (fields == null || fields.isEmpty()) return 0;
//        int updated = 0;
//        String jpql = "UPDATE " + entityClass.getSimpleName() + " e SET e." + fieldName + " = :value WHERE e.id = :id";
//        Query query = entityManager.createQuery(jpql);
//
//        for (IdFieldValue idFieldValue : fields) {
//            updated += query
//                    .setParameter("value", idFieldValue.getValue())
//                    .setParameter("id", idFieldValue.getId())
//                    .executeUpdate();
//        }
//        entityManager.clear();
//        return updated;
//    }


    @Override
    @Transactional
    public int bulkUpdate(String fieldName, List<? extends IdFieldValue> fields) {
        if(fields == null || fields.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(entityClass.getSimpleName()).append(" SET ").append(fieldName).append(" = CASE id ");

        for(IdFieldValue f : fields) sql.append(" WHEN ").append(f.getId()).append(" THEN :v").append(f.getId());

        sql.append(" END WHERE id IN (");
        String ids = fields.stream().map(f -> f.getId().toString()).collect(Collectors.joining(","));
        sql.append(ids).append(")");

        Query query = entityManager.createNativeQuery(sql.toString());
        for(IdFieldValue f : fields) query.setParameter("v"+f.getId(), f.getValue());

        return query.executeUpdate();
    }


//    @Override
//    @Transactional
//    public int updateBulkEntity(List<ID> ids, List<? extends IdFieldValue> idFieldValues) {
//        if (idFieldValues == null || idFieldValues.isEmpty()) return 0;
//
//        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
//        CriteriaUpdate<E> update = cb.createCriteriaUpdate(entityClass);
//        Root<E> root = update.from(entityClass);
//
//        for (IdFieldValue field : idFieldValues) update.set(root.get(field.getField()), field.getValue());
//        update.where(root.get("id").in(ids));
//
//        int updated = entityManager.createQuery(update).executeUpdate();
//        entityManager.clear();
//        return updated;
//    }

//    @Override
//    @Transactional
//    public int updateFields(ID id, Map<String, Object> fields) {
//        if (fields == null || fields.isEmpty()) return 0;
//        StringBuilder jpql = new StringBuilder("UPDATE " + entityClass.getSimpleName() + " e SET ");
//
//        int i = 0;
//        for (String field : fields.keySet()) {
//            if (i++ > 0) jpql.append(", ");
//            jpql.append("e.").append(field).append(" = :").append(field);
//        }
//        jpql.append(" WHERE e.id = :id");
//
//        Query query = entityManager.createQuery(jpql.toString());
//        query.setParameter("id", id);
//        fields.forEach(query::setParameter);
//
//        int updated = query.executeUpdate();
//        entityManager.clear();
//        return updated;
//    }

    @Override
    public Object convertValue(String fieldName, Object value) {
        if (value == null) return null;

        Class<?> type = entityManager.getMetamodel().entity(entityClass).getAttribute(fieldName).getJavaType();

        if (type.equals(LocalDateTime.class)) return LocalDateTime.parse(value.toString());
        if (type.equals(Boolean.class) || type.equals(boolean.class)) return Boolean.parseBoolean(value.toString());
        if (Enum.class.isAssignableFrom(type)) return Enum.valueOf((Class<? extends Enum>) type, value.toString());

        return value;
    }

    @Override
    public Map<Object, List<Long>> groupFields(List<? extends IdFieldValue> fields) {
        return fields.stream()
                .filter(field -> field.getId() != null)
                .collect(Collectors.groupingBy(
                        IdFieldValue::getValue,
                        Collectors.mapping(IdFieldValue::getId, Collectors.toList())
                ));
    }

}
