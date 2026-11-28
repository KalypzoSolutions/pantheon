package de.kalypzo.pantheon.core.language;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.language.CommonMessage;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CommonMessageFilesTest {

    @Test
    void everyCommonMessageInBothLanguages() throws Exception {
        for (String file : new String[] {"pantheon_de_DE.yml", "pantheon_en_US.yml"}) {
            Map<?, ?> root;
            try (InputStream in = getClass().getResourceAsStream("/lang/" + file)) {
                assertThat(in).as(file).isNotNull();
                root = new Yaml().load(in);
            }
            for (CommonMessage m : CommonMessage.values()) {
                Object node = root;
                for (String part : m.key().split("\\.")) {
                    node = node instanceof Map<?, ?> map ? map.get(part) : null;
                }
                assertThat(node).as(file + " " + m.key()).isInstanceOf(String.class);
                assertThat((String) node).as(file + " " + m.key()).isNotBlank();
            }
        }
    }
}
