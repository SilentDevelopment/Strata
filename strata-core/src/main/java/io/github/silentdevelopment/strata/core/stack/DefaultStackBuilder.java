package io.github.silentdevelopment.strata.core.stack;


import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.generator.KeyGenerator;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerGroup;
import io.github.silentdevelopment.strata.layer.LayerOptions;
import io.github.silentdevelopment.strata.layer.LayerRegistration;
import io.github.silentdevelopment.strata.layer.Role;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Builder for the default Strata stack implementation.
 */
public final class DefaultStackBuilder {

    private final Map<Role, List<LayerRegistration>> layers = new EnumMap<>(Role.class);
    private final Map<Type<?>, Codec<?>> codecs = new LinkedHashMap<>();
    private Executor executor = ForkJoinPool.commonPool();
    private KeyGenerator keyGenerator = KeyGenerator.ulid();
    private boolean requireDurable;

    public DefaultStackBuilder() {
        for (Role role : Role.values()) {
            layers.put(role, new ArrayList<>());
        }
    }

    public DefaultStackBuilder executor(@NotNull Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    public DefaultStackBuilder keyGenerator(@NotNull KeyGenerator keyGenerator) {
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
        return this;
    }

    public DefaultStackBuilder requireDurable() {
        this.requireDurable = true;
        return this;
    }

    public <T> DefaultStackBuilder codec(@NotNull Type<T> type, @NotNull Codec<T> codec) {
        codecs.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(codec, "codec"));
        return this;
    }

    public DefaultStackBuilder soft(@NotNull Layer layer) {
        return layer(layer, LayerOptions.defaults());
    }

    public DefaultStackBuilder buffer(@NotNull Layer layer) {
        return layer(layer, LayerOptions.defaults());
    }

    public DefaultStackBuilder durable(@NotNull Layer layer) {
        return layer(layer, LayerOptions.defaults());
    }

    public DefaultStackBuilder soft(@NotNull Layer layer, @NotNull LayerOptions options) {
        return layer(layer, options);
    }

    public DefaultStackBuilder buffer(@NotNull Layer layer, @NotNull LayerOptions options) {
        return layer(layer, options);
    }

    public DefaultStackBuilder durable(@NotNull Layer layer, @NotNull LayerOptions options) {
        return layer(layer, options);
    }

    public DefaultStackBuilder group(@NotNull LayerGroup group) {
        Objects.requireNonNull(group, "group");
        layers.get(group.role()).addAll(group.layers());
        return this;
    }

    public DefaultStackBuilder layer(@NotNull Layer layer, @NotNull LayerOptions options) {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(options, "options");
        layers.get(layer.role()).add(new LayerRegistration(layer, options));
        return this;
    }

    public DefaultStack build() {
        if (requireDurable && layers.get(Role.DURABLE).isEmpty()) {
            throw new IllegalStateException("Durable layer is required but none was configured.");
        }
        return new DefaultStack(layers, codecs, executor, keyGenerator);
    }

}
