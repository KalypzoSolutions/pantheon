package de.kalypzo.pantheon.core.data.wire;

public record IndexDeclaration(QueryTarget target, FieldSpec field, boolean unique) {}
