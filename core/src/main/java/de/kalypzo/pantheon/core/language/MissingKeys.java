package de.kalypzo.pantheon.core.language;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers what was already reported, so each problem is logged once. Thread safe. */
final class MissingKeys {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> types = ConcurrentHashMap.newKeySet();

    /** True the first time this (key, locale) is reported. */
    boolean firstMissing(String key, Locale locale) {
        return keys.add(locale + "\0" + key);
    }

    /** True the first time this class is reported. */
    boolean firstUnknownType(Class<?> type) {
        return types.add(type);
    }
}
