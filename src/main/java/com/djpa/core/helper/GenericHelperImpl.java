package com.djpa.core.helper;

import com.djpa.core.exception.NotFoundException;
import com.djpa.core.repository.BaseRepository;

import java.util.Collection;
import java.util.List;

public class GenericHelperImpl<E, ID> extends GenericUpdateImpl<E, ID> implements GenericHelper<E, ID> {

    private final BaseRepository<E, ID> baseRepository;

    public GenericHelperImpl(Class<E> entityClass, BaseRepository<E, ID> baseRepository) {
        super(entityClass);
        this.baseRepository = baseRepository;
    }

    @Override
    public <T> T findById(ID id, Class<T> type) {
        return baseRepository.findById(id, type).orElse(null);
    }

    @Override
    public <T> T findByIdOrThrow(ID id, Class<T> type) {
        baseRepository.deleteAllByIdInBatch(List.of(id));
        return baseRepository.findById(id, type)
                .orElseThrow(() -> new NotFoundException(entityClass.getSimpleName() + " not found for id=" + id));
    }

    @Override
    public <T> List<T> findByIdIn(Collection<ID> ids, Class<T> type) {
        return baseRepository.findByIdIn(ids, type);
    }

    @Override
    public boolean existsById(ID id) {
        return baseRepository.existsById(id);
    }

    @Override
    public boolean existsByIdOrThrow(ID id) {
        if (!baseRepository.existsById(id))
            throw new NotFoundException(entityClass.getSimpleName() + " not exists for id=" + id);
        return true;
    }

    @Override
    public E save(E entity) {
        return baseRepository.save(entity);
    }

    @Override
    public void delete(E entity) {
        baseRepository.delete(entity);
    }
}
