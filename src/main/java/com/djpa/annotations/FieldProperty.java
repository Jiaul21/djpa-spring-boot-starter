package com.djpa.annotations;

public record FieldProperty<T, E>(
        String name,
        Class<T> type,
        Class<E> elementType
) {}
