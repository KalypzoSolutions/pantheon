package de.kalypzo.pantheon.core.audit;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commands that never reach the audit log, e.g. password commands (audit_log.md §5, E40). Thread-safe.
 *
 * <p>A command is hidden if its resolved command has a hidden identity, if a label hidden via {@link #hide(String)}
 * resolves to the same command (or equals the typed label), or if the typed label without namespace, the name or an
 * alias of the resolved command is in {@link #DEFAULTS}. Case-insensitive; an unknown label in the defaults is hidden.
 */
public final class HiddenCommands {

    public static final Set<String> DEFAULTS =
            Set.of("login", "l", "register", "reg", "changepassword", "changepass");

    private final CommandResolver resolver;
    private final Set<Object> identities = ConcurrentHashMap.newKeySet();
    private final Set<String> labels = ConcurrentHashMap.newKeySet();

    public HiddenCommands(CommandResolver resolver) {
        this.resolver = resolver;
    }

    public void hide(Object commandIdentity) {
        identities.add(commandIdentity);
    }

    /**
     * Hides {@code label}, normalised like a typed label (R53): leading slashes and whitespace stripped (trailing
     * whitespace too), lower-cased with {@link Locale#ROOT}. So {@code "/2fa"} hides {@code /2fa <code>}.
     *
     * @throws IllegalArgumentException if nothing is left after normalising
     */
    public void hide(String label) {
        String normalised = CommandAudit.stripLeading(label).strip().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("command label is blank: '" + label + "'");
        }
        labels.add(normalised);
    }

    public boolean isHidden(String typedLabel) {
        String label = CommandAudit.stripLeading(typedLabel).toLowerCase(Locale.ROOT);
        if (DEFAULTS.contains(withoutNamespace(label)) || labels.contains(label)) {
            return true;
        }
        Optional<ResolvedCommand> resolved = resolver.resolve(label);
        if (resolved.isPresent()) {
            ResolvedCommand c = resolved.get();
            if (identities.contains(c.identity()) || DEFAULTS.contains(withoutNamespace(lower(c.name())))) {
                return true;
            }
            for (String alias : c.aliases()) {
                if (DEFAULTS.contains(withoutNamespace(lower(alias)))) {
                    return true;
                }
            }
        }
        for (String hiddenLabel : labels) {
            Optional<ResolvedCommand> h = resolver.resolve(hiddenLabel);
            if (h.isPresent() && resolved.isPresent() && h.get().identity().equals(resolved.get().identity())) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String withoutNamespace(String label) {
        int i = label.lastIndexOf(':');
        return i < 0 ? label : label.substring(i + 1);
    }
}
