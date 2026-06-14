package com.djpa.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@NoRepositoryBean
public interface BaseRepository<E, ID> extends JpaRepository<E, ID> {

    <T> Optional<T> findById(ID id, Class<T> type);

    <T> List<T> findByIdIn(Collection<ID> ids, Class<T> tClass);
}

