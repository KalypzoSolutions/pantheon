package de.kalypzo.pantheon.core.data.codec;

import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.data.Field;

/** Maps API fields to their wire description. */
public final class FieldSpecs {

    private FieldSpecs() {}

    public static FieldSpec of(Field<?> field) {
        ScalarType type = switch (field.type()) {
            case INT, LONG, INSTANT -> ScalarType.LONG;
            case DOUBLE -> ScalarType.DOUBLE;
            case BOOLEAN -> ScalarType.BOOLEAN;
            case STRING, UUID, ENUM -> ScalarType.STRING;
            case SET -> ScalarType.SET;
            case LIST -> ScalarType.LIST;
            case OBJECT -> ScalarType.OBJECT;
        };
        return new FieldSpec(field.namespace().name(), field.name(), type, encodeDefault(field));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object encodeDefault(Field<?> field) {
        return field.defaultValue() == null ? null : ((FieldCodec) FieldCodecs.forField(field)).encode(field.defaultValue());
    }
}
