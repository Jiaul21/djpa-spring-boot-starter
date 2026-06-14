package com.djpa.core.helper;

import java.util.Collection;
import java.util.List;

public interface GenericHelper<E, ID> extends GenericUpdate<E, ID> {

    <T> T findById(ID id, Class<T> type);

    <T> T findByIdOrThrow(ID id, Class<T> type);

    boolean existsById(ID id);

    boolean existsByIdOrThrow(ID id);

    <T> List<T> findByIdIn(Collection<ID> ids, Class<T> type);

    E save(E entity);

    void delete(E entity);
}

