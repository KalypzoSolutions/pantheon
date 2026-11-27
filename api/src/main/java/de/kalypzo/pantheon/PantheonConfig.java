package de.kalypzo.pantheon;

import de.kalypzo.pantheon.language.LanguageConfig;

/** Configuration of a plugin's Pantheon instance. Gives no access to the Agora address or token. */
public interface PantheonConfig {

    LanguageConfig language();
}
