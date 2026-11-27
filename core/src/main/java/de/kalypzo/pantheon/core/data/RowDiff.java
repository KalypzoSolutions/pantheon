package de.kalypzo.pantheon.core.data;

import java.util.Map;

/** The visible data of one namespace before and after a change. */
public record RowDiff(String namespace, Map<String, Object> before, Map<String, Object> after) {}
