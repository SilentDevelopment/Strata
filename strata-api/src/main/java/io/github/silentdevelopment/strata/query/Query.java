package io.github.silentdevelopment.strata.query;

import io.github.silentdevelopment.strata.entry.Envelope;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Capability-dependent query predicate for indexed entry lookup.
 *
 * <p>Queries are evaluated by layers that report query support. Not all layers
 * support all operators, and some layers, such as Memcached-backed layers, do
 * not support query or index operations because safe enumeration is unavailable.</p>
 *
 * <p>The query model is intentionally storage-neutral. Layers may execute the
 * query directly in the backend, translate it to a native query language, or
 * evaluate it in memory after collecting candidate envelopes. Unsupported
 * operators, sort fields, or query shapes should be reported as unsupported
 * rather than silently returning incorrect results.</p>
 */
public final class Query {

    /**
     * Field marker for an all-records query.
     */
    public static final String ALL_FIELD = "*";

    private final String field;
    private final Operator operator;
    private final String value;
    private final int limit;
    private final int offset;
    private final List<Sort> sorts;

    private Query(String field, Operator operator, String value, int limit, int offset, List<Sort> sorts) {
        this.field = Objects.requireNonNull(field, "field");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.value = Objects.requireNonNull(value, "value");
        this.limit = limit;
        this.offset = offset;
        this.sorts = List.copyOf(Objects.requireNonNull(sorts, "sorts"));

        if (field.isBlank()) {
            throw new IllegalArgumentException("field cannot be blank.");
        }

        if (limit < 0) {
            throw new IllegalArgumentException("limit cannot be negative.");
        }

        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative.");
        }
    }

    /**
     * Creates a query that matches every indexed entry of a type.
     *
     * @return all-records query
     */
    public static @NotNull Query all() {
        return new Query(ALL_FIELD, Operator.ALL, "", 0, 0, List.of());
    }

    /**
     * Starts a field-specific query builder.
     *
     * @param field indexed field name
     * @return field query builder
     */
    public static @NotNull FieldBuilder where(@NotNull String field) {
        return new FieldBuilder(field);
    }

    /**
     * Returns a copy with an explicit result limit.
     *
     * <p>A limit of {@code 0} means no explicit limit. Layers may still apply
     * backend-specific safety limits if documented by the layer implementation.</p>
     *
     * @param limit maximum number of results, or {@code 0} for no explicit limit
     * @return query with updated limit
     */
    public @NotNull Query limit(int limit) {
        return new Query(field, operator, value, limit, offset, sorts);
    }

    /**
     * Returns a copy with an explicit result offset.
     *
     * @param offset result offset
     * @return query with updated offset
     */
    public @NotNull Query offset(int offset) {
        return new Query(field, operator, value, limit, offset, sorts);
    }

    /**
     * Returns a copy with an additional sort.
     *
     * <p>Sorts are applied in insertion order. A layer with native sort support
     * may translate this model to backend-specific sorting. A layer without sort
     * support should either evaluate the sort after collecting candidates or
     * return an unsupported result.</p>
     *
     * @param field indexed or mapped field name
     * @param direction sort direction
     * @return query with the additional sort
     */
    public @NotNull Query sortBy(@NotNull String field, @NotNull SortDirection direction) {
        List<Sort> copy = new ArrayList<>(sorts);
        copy.add(new Sort(field, direction));
        return new Query(this.field, operator, value, limit, offset, copy);
    }

    /**
     * Returns a copy with an ascending sort.
     *
     * @param field indexed or mapped field name
     * @return query with the additional ascending sort
     */
    public @NotNull Query sortAsc(@NotNull String field) {
        return sortBy(field, SortDirection.ASC);
    }

    /**
     * Returns a copy with a descending sort.
     *
     * @param field indexed or mapped field name
     * @return query with the additional descending sort
     */
    public @NotNull Query sortDesc(@NotNull String field) {
        return sortBy(field, SortDirection.DESC);
    }

    /**
     * Returns the indexed field used by this query.
     *
     * @return indexed field name, or {@link #ALL_FIELD} for all-records queries
     */
    public @NotNull String field() {
        return field;
    }

    /**
     * Returns the comparison operator used by this query.
     *
     * @return comparison operator
     */
    public @NotNull Operator operator() {
        return operator;
    }

    /**
     * Returns the comparison value encoded as text.
     *
     * @return comparison value, or an empty string for all-records queries
     */
    public @NotNull String value() {
        return value;
    }

    /**
     * Returns the maximum number of results.
     *
     * @return maximum number of results, or {@code 0} for no explicit limit
     */
    public int limit() {
        return limit;
    }

    /**
     * Returns the result offset.
     *
     * @return result offset
     */
    public int offset() {
        return offset;
    }

    /**
     * Returns the configured sorts.
     *
     * @return immutable sort list in evaluation order
     */
    public @NotNull List<Sort> sorts() {
        return sorts;
    }

    /**
     * Returns whether this query has at least one configured sort.
     *
     * @return {@code true} when one or more sorts are configured
     */
    public boolean hasSorts() {
        return !sorts.isEmpty();
    }

    /**
     * Returns the index name required by this query.
     *
     * @return index name, or empty for all-records queries
     */
    public @NotNull Optional<String> indexName() {
        if (operator == Operator.ALL) {
            return Optional.empty();
        }

        return Optional.of(field);
    }

    /**
     * Evaluates this query against an encoded envelope's metadata indexes.
     *
     * <p>Comparison values are stored as text in metadata indexes. Numeric-looking
     * values are compared numerically by this in-memory evaluator; otherwise
     * values are compared lexicographically.</p>
     *
     * @param envelope envelope to inspect
     * @return {@code true} when the envelope matches
     */
    public boolean matches(@NotNull Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");

        if (operator == Operator.ALL) {
            return true;
        }

        String indexed = envelope.metadata().indexes().get(field);

        if (indexed == null) {
            return false;
        }

        int comparison = compare(indexed, value);

        return switch (operator) {
            case EQ -> indexed.equals(value);
            case NE -> !indexed.equals(value);
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case ALL -> true;
        };
    }

    /**
     * Applies this query to an already-collected envelope list.
     *
     * <p>This method is intended for layers that cannot translate all query
     * behavior to the backend. It applies predicate matching, sorting, offset,
     * and limit in that order.</p>
     *
     * @param envelopes candidate envelopes
     * @return immutable result list after filtering, sorting, and pagination
     */
    public @NotNull List<Envelope> apply(@NotNull List<Envelope> envelopes) {
        Objects.requireNonNull(envelopes, "envelopes");

        List<Envelope> result = envelopes.stream()
                .filter(this::matches)
                .collect(Collectors.toCollection(ArrayList::new));

        if (!sorts.isEmpty()) {
            result.sort(comparator());
        }

        int from = Math.min(offset, result.size());
        int to = result.size();

        if (limit > 0) {
            to = Math.min(from + limit, result.size());
        }

        return List.copyOf(result.subList(from, to));
    }

    private static int compare(String left, String right) {
        Double leftNumber = parseDouble(left);
        Double rightNumber = parseDouble(right);

        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }

        return left.compareTo(right);
    }

    private Comparator<Envelope> comparator() {
        Comparator<Envelope> comparator = null;

        for (Sort sort : sorts) {
            Comparator<Envelope> next = Comparator.comparing(envelope -> sortValue(envelope, sort.field()), Query::compare);

            if (sort.direction() == SortDirection.DESC) {
                next = next.reversed();
            }

            if (comparator == null) {
                comparator = next;
                continue;
            }

            comparator = comparator.thenComparing(next);
        }

        return comparator == null ? (left, right) -> 0 : comparator;
    }

    private static String sortValue(Envelope envelope, String field) {
        String value = envelope.metadata().indexes().get(field);

        if (value == null) {
            return "";
        }

        return value;
    }

    private static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Query comparison operators supported by the API model.
     */
    public enum Operator {

        /**
         * Matches all records of the queried type.
         */
        ALL,

        /**
         * Matches values equal to the query value.
         */
        EQ,

        /**
         * Matches values not equal to the query value.
         */
        NE,

        /**
         * Matches values greater than the query value.
         */
        GT,

        /**
         * Matches values greater than or equal to the query value.
         */
        GTE,

        /**
         * Matches values less than the query value.
         */
        LT,

        /**
         * Matches values less than or equal to the query value.
         */
        LTE

    }

    /**
     * Builder for a field-specific query predicate.
     *
     * @param field indexed field name
     */
    public record FieldBuilder(@NotNull String field) {

        public FieldBuilder {
            Objects.requireNonNull(field, "field");

            if (field.isBlank()) {
                throw new IllegalArgumentException("field cannot be blank.");
            }
        }

        /**
         * Creates an equality query.
         *
         * @param value comparison value
         * @return equality query
         */
        public @NotNull Query eq(@NotNull Object value) {
            return query(Operator.EQ, value);
        }

        /**
         * Creates an inequality query.
         *
         * @param value comparison value
         * @return inequality query
         */
        public @NotNull Query ne(@NotNull Object value) {
            return query(Operator.NE, value);
        }

        /**
         * Creates a greater-than query.
         *
         * @param value comparison value
         * @return greater-than query
         */
        public @NotNull Query gt(@NotNull Object value) {
            return query(Operator.GT, value);
        }

        /**
         * Creates a greater-than-or-equal query.
         *
         * @param value comparison value
         * @return greater-than-or-equal query
         */
        public @NotNull Query gte(@NotNull Object value) {
            return query(Operator.GTE, value);
        }

        /**
         * Creates a less-than query.
         *
         * @param value comparison value
         * @return less-than query
         */
        public @NotNull Query lt(@NotNull Object value) {
            return query(Operator.LT, value);
        }

        /**
         * Creates a less-than-or-equal query.
         *
         * @param value comparison value
         * @return less-than-or-equal query
         */
        public @NotNull Query lte(@NotNull Object value) {
            return query(Operator.LTE, value);
        }

        private Query query(Operator operator, Object value) {
            Objects.requireNonNull(value, "value");
            return new Query(field, operator, String.valueOf(value), 0, 0, List.of());
        }

    }

}