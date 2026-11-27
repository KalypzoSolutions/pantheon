package de.kalypzo.pantheon.data;

import static de.kalypzo.pantheon.data.Condition.contains;
import static de.kalypzo.pantheon.data.Condition.not;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionTest {

    @Test
    void notContainsInfersCollectionType() {
        Condition<Set<String>> c = not(contains("a"));
        assertThat(c).isInstanceOf(Condition.Not.class);
        Condition.Not<Set<String>> not = (Condition.Not<Set<String>>) c;
        assertThat(not.condition()).isEqualTo(new Condition.Contains<>("a"));
    }
}
