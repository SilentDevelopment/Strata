package io.github.silentdevelopment.strata;


import io.github.silentdevelopment.strata.core.repository.DefaultRepository;
import io.github.silentdevelopment.strata.core.stack.DefaultStackBuilder;
import io.github.silentdevelopment.strata.repository.Repository;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Static factory facade for default Strata stacks and repositories.
 */
public final class Strata {

    private Strata() {
        throw new UnsupportedOperationException("Utility class.");
    }

    public static @NotNull DefaultStackBuilder stack() {
        return new DefaultStackBuilder();
    }

    public static <T, I> @NotNull Repository<T, I> repository(@NotNull Stack stack, @NotNull Function<I, Key<T>> keyFactory) {
        return new DefaultRepository<>(stack, keyFactory);
    }

}
