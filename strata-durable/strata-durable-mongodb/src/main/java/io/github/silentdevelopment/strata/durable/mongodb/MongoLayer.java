package io.github.silentdevelopment.strata.durable.mongodb;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB DURABLE layer that stores metadata and indexes in Mongo documents.
 */
public final class MongoLayer implements Layer {

    private final String name;
    private final MongoCollection<Document> collection;

    private MongoLayer(@NotNull String name, @NotNull MongoCollection<Document> collection) {
        this.name = Objects.requireNonNull(name, "name");
        this.collection = Objects.requireNonNull(collection, "collection");
        ensureIndexes();
    }

    public static @NotNull MongoLayer create(@NotNull MongoDatabase database) {
        Objects.requireNonNull(database, "database");
        return named("mongodb", database.getCollection("strata_entries"));
    }

    public static @NotNull MongoLayer named(@NotNull String name, @NotNull MongoDatabase database, @NotNull String collection) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(collection, "collection");
        return named(name, database.getCollection(collection));
    }

    public static @NotNull MongoLayer named(@NotNull String name, @NotNull MongoCollection<Document> collection) {
        return new MongoLayer(name, collection);
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Role role() {
        return Role.DURABLE;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.keyValue(Role.DURABLE).withQuery(true).withIndex(true).withTtl(true);
    }

    @Override
    public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> loadNow(key), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> saveNow(envelope, context), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> deleteNow(key), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> queryNow(type, query), context.executor());
    }

    private Result<Envelope> loadNow(Key<?> key) {
        Instant start = Instant.now();

        try {
            Document document = collection.find(Filters.eq("_id", key.external())).first();

            if (document == null) {
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }

            Envelope envelope = toEnvelope(document, key);

            if (envelope.metadata().expired()) {
                collection.deleteOne(Filters.eq("_id", key.external()));
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }

            return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();

        try {
            Document document = toDocument(envelope, context.saveOptions().ttl());
            SaveCondition condition = context.saveOptions().condition();
            Bson filter = Filters.eq("_id", envelope.key().external());

            if (condition.expectedStamp() != null) {
                Bson conditionalFilter = Filters.and(filter, Filters.eq("stamp", condition.expectedStamp().value()));
                UpdateResult result = collection.replaceOne(conditionalFilter, document);

                if (result.getMatchedCount() == 0L) {
                    Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }

                return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
            }

            collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();

        try {
            collection.deleteOne(Filters.eq("_id", key.external()));
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> type, Query query) {
        Instant start = Instant.now();

        try {
            List<Envelope> result = new ArrayList<>();
            Bson filter = queryFilter(type, query);
            var iterable = collection.find(filter).skip(query.offset());

            if (query.limit() > 0) {
                iterable = iterable.limit(query.limit());
            }

            for (Document document : iterable) {
                Envelope envelope = toEnvelope(document, type);

                if (envelope.metadata().expired()) {
                    collection.deleteOne(Filters.eq("_id", envelope.key().external()));
                    continue;
                }

                if (!query.matches(envelope)) {
                    continue;
                }

                result.add(envelope);
            }

            return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private void ensureIndexes() {
        collection.createIndex(Indexes.ascending("namespace", "type", "path"));
        collection.createIndex(Indexes.ascending("type"));
        collection.createIndex(new Document("indexes.$**", 1));
        collection.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).sparse(true));
    }

    private Bson queryFilter(Type<?> type, Query query) {
        Bson typeFilter = Filters.eq("type", type.id());

        if (query.operator() == Query.Operator.ALL) {
            return typeFilter;
        }

        String field = "indexes." + query.field();

        Bson indexFilter = switch (query.operator()) {
            case EQ -> Filters.eq(field, query.value());
            case NE -> Filters.ne(field, query.value());
            case GT -> Filters.gt(field, query.value());
            case GTE -> Filters.gte(field, query.value());
            case LT -> Filters.lt(field, query.value());
            case LTE -> Filters.lte(field, query.value());
            case ALL -> Filters.exists(field);
        };

        return Filters.and(typeFilter, indexFilter);
    }

    private Document toDocument(Envelope envelope, Duration ttl) {
        Metadata metadata = envelope.metadata();
        Instant expiresAt = metadata.expiresAt();

        if (ttl != null) {
            expiresAt = Instant.now().plus(ttl);
        }

        return new Document("_id", envelope.key().external())
                .append("namespace", envelope.key().namespace().value())
                .append("type", envelope.key().type().id())
                .append("path", envelope.key().path())
                .append("stamp", envelope.stamp().value())
                .append("contentType", envelope.encoded().contentType())
                .append("payload", envelope.encoded().bytes())
                .append("tombstone", envelope.tombstone())
                .append("createdAt", Date.from(metadata.createdAt()))
                .append("updatedAt", Date.from(metadata.updatedAt()))
                .append("expiresAt", expiresAt == null ? null : Date.from(expiresAt))
                .append("values", new Document(metadata.values()))
                .append("indexes", new Document(metadata.indexes()));
    }

    private Envelope toEnvelope(Document document, Key<?> key) {
        Encoded encoded = new Encoded(payload(document), document.getString("contentType"));
        Metadata metadata = new Metadata(instant(document, "createdAt"), instant(document, "updatedAt"), nullableInstant(document, "expiresAt"), stringMap(document, "values"), stringMap(document, "indexes"));
        return new Envelope(key, encoded, Stamp.of(document.getString("stamp")), metadata, document.getBoolean("tombstone", false));
    }

    private Envelope toEnvelope(Document document, Type<?> type) {
        Namespace namespace = Namespace.of(document.getString("namespace"));
        Key<?> key = Key.of(namespace, type, document.getString("path"));
        return toEnvelope(document, key);
    }

    private static byte[] payload(Document document) {
        Object value = document.get("payload");

        if (value instanceof Binary binary) {
            return binary.getData();
        }

        if (value instanceof byte[] bytes) {
            return bytes;
        }

        if (value == null) {
            throw new IllegalStateException("MongoDB payload is missing.");
        }

        throw new IllegalStateException("Unexpected MongoDB payload type: " + value.getClass().getName());
    }

    private static Instant instant(Document document, String field) {
        Date date = document.getDate(field);

        if (date == null) {
            return Instant.EPOCH;
        }

        return date.toInstant();
    }

    private static Instant nullableInstant(Document document, String field) {
        Date date = document.getDate(field);

        if (date == null) {
            return null;
        }

        return date.toInstant();
    }

    private static Map<String, String> stringMap(Document document, String field) {
        Document source = document.get(field, Document.class);

        if (source == null) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }

        return result;
    }

}