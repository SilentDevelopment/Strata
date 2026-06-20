package io.github.silentdevelopment.strata.core;


import io.github.silentdevelopment.strata.Strata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DefaultStackBuilderTest {

    @Test
    void allowsMemoryOnlyStack() {
        assertThatNoException().isThrownBy(() -> Strata.stack().build());
    }

    @Test
    void canRequireDurableLayer() {
        assertThatThrownBy(() -> Strata.stack().requireDurable().build()).isInstanceOf(IllegalStateException.class);
    }

}
