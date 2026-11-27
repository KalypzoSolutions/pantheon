package de.kalypzo.pantheon.core.data.codec;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.data.DataNamespace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldSpecsTest {

    private static final DataNamespace NS = DataNamespace.of("specs_test");

    enum Color { RED }

    record Pos(double x) {}

    @Test
    void mapsAllTypes() {
        assertThat(FieldSpecs.of(NS.ofInt("i", 3))).isEqualTo(new FieldSpec("specs_test", "i", ScalarType.LONG, 3L));
        assertThat(FieldSpecs.of(NS.ofLong("l", 4L))).isEqualTo(new FieldSpec("specs_test", "l", ScalarType.LONG, 4L));
        assertThat(FieldSpecs.of(NS.ofInstant("t"))).isEqualTo(new FieldSpec("specs_test", "t", ScalarType.LONG, null));
        assertThat(FieldSpecs.of(NS.ofDouble("d", 1.5))).isEqualTo(new FieldSpec("specs_test", "d", ScalarType.DOUBLE, 1.5));
        assertThat(FieldSpecs.of(NS.ofBoolean("b", true))).isEqualTo(new FieldSpec("specs_test", "b", ScalarType.BOOLEAN, true));
        assertThat(FieldSpecs.of(NS.ofString("s", "x"))).isEqualTo(new FieldSpec("specs_test", "s", ScalarType.STRING, "x"));
        assertThat(FieldSpecs.of(NS.ofUuid("u")).type()).isEqualTo(ScalarType.STRING);
        assertThat(FieldSpecs.of(NS.ofEnum("e", Color.class, Color.RED)))
                .isEqualTo(new FieldSpec("specs_test", "e", ScalarType.STRING, "RED"));
        assertThat(FieldSpecs.of(NS.ofSet("set", UUID.class)))
                .isEqualTo(new FieldSpec("specs_test", "set", ScalarType.SET, List.of()));
        assertThat(FieldSpecs.of(NS.ofList("list", String.class)))
                .isEqualTo(new FieldSpec("specs_test", "list", ScalarType.LIST, List.of()));
        assertThat(FieldSpecs.of(NS.of("obj", Pos.class)))
                .isEqualTo(new FieldSpec("specs_test", "obj", ScalarType.OBJECT, null));
    }
}
