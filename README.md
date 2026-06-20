# Strata

Strata is a Java 21 layered storage management library. It is designed to work as a normal Maven dependency, as a shaded dependency, and as a relocated shaded dependency.

## Storage roles

```java
Role.SOFT
Role.BUFFER
Role.DURABLE
```

- `SOFT`: local, fastest, volatile/non-authoritative storage. Example: memory.
- `BUFFER`: intermediate acceleration layer. Examples: Redis, Memcached.
- `DURABLE`: persistent authoritative layer by default. Examples: JDBC, MongoDB, files.

## Usage

```java
Type<PlayerData> PLAYER_DATA = Type.of("player_data", PlayerData.class);
Namespace namespace = Namespace.of("myplugin");
Key<PlayerData> key = namespace.key(PLAYER_DATA, "players", uuid.toString());

Stack stack = Strata.stack()
    .soft(MemoryLayer.create())
    .codec(PLAYER_DATA, GsonCodec.of(PlayerData.class))
    .build();

stack.save(key, new PlayerData("Silent", 100)).join();
Entry<PlayerData> loaded = stack.load(key).join().valueOrThrow();
```

## Maven modules

```text
strata-api
strata-core
strata-soft-memory
strata-buffer-redis-redisson
strata-buffer-redis-lettuce
strata-buffer-redis-jedis
strata-buffer-memcached
strata-durable-jdbc
strata-durable-mongodb
strata-durable-file
strata-codec-gson
strata-codec-jdk
strata-codec-snakeyaml
strata-lock
strata-lock-redis-redisson
strata-journal
strata-journal-file
strata-testkit
strata-examples
```

The grouping directories such as `strata-buffer`, `strata-durable`, and `strata-codecs` are Maven aggregator POMs. Consumers should depend on the concrete modules.

## Design notes

- Async core API with blocking wrappers.
- `Result<T>` for expected operation failures.
- `Namespace + Type<T> + Key<T>` for stable persistent identity.
- No global mutable registry.
- No persisted Java class names.
- Codecs are registered per stack/type.
- Durable storage is authoritative by default.
- Compensation model for partial failures.
- Optional optimistic locking through opaque `Stamp`.
- Optional query/index capability.
- Optional generated keys via default ULID-style generator.

## Build

```bash
mvn test
```

The project targets Java 21.
