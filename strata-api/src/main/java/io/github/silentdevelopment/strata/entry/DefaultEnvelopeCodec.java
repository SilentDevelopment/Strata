package io.github.silentdevelopment.strata.entry;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Binary serializer for Strata envelopes used by backend layers that store opaque bytes.
 */
public final class DefaultEnvelopeCodec {

    public static final DefaultEnvelopeCodec INSTANCE = new DefaultEnvelopeCodec();

    private static final int MAGIC = 0x53545241;
    private static final int VERSION = 1;

    private DefaultEnvelopeCodec() {
    }

    public byte @NotNull [] encode(@NotNull Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);

                writeString(output, envelope.key().namespace().value());
                writeString(output, envelope.key().type().id());
                writeString(output, envelope.key().path());

                writeString(output, envelope.stamp().value());
                writeString(output, envelope.encoded().contentType());

                writeInstant(output, envelope.metadata().createdAt());
                writeInstant(output, envelope.metadata().updatedAt());
                writeNullableInstant(output, envelope.metadata().expiresAt());

                writeMap(output, envelope.metadata().values());
                writeMap(output, envelope.metadata().indexes());

                output.writeBoolean(envelope.tombstone());

                byte[] payload = envelope.encoded().bytes();
                output.writeInt(payload.length);
                output.write(payload);
            }

            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to encode envelope.", exception);
        }
    }

    public @NotNull Envelope decode(byte @NotNull [] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();

            if (magic != MAGIC) {
                throw new IllegalArgumentException("Invalid Strata envelope magic.");
            }

            int version = input.readInt();

            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported Strata envelope version: " + version);
            }

            Namespace namespace = Namespace.of(readString(input));
            Type<Object> type = Type.of(readString(input), Object.class);
            String path = readString(input);

            Stamp stamp = Stamp.of(readString(input));
            String contentType = readString(input);

            Instant createdAt = readInstant(input);
            Instant updatedAt = readInstant(input);
            Instant expiresAt = readNullableInstant(input);

            Map<String, String> values = readMap(input);
            Map<String, String> indexes = readMap(input);

            boolean tombstone = input.readBoolean();

            int payloadLength = input.readInt();

            if (payloadLength < 0) {
                throw new IllegalArgumentException("Negative envelope payload length.");
            }

            byte[] payload = input.readNBytes(payloadLength);

            if (payload.length != payloadLength) {
                throw new IllegalArgumentException("Unexpected end of envelope payload.");
            }

            Metadata metadata = new Metadata(createdAt, updatedAt, expiresAt, values, indexes);
            Encoded encoded = Encoded.of(payload, contentType);
            Key<Object> key = Key.of(namespace, type, path);

            return new Envelope(key, encoded, stamp, metadata, tombstone);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to decode envelope.", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws Exception {
        int length = input.readInt();

        if (length < 0) {
            throw new IllegalArgumentException("Negative string length.");
        }

        byte[] bytes = input.readNBytes(length);

        if (bytes.length != length) {
            throw new IllegalArgumentException("Unexpected end of string.");
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeInstant(DataOutputStream output, Instant instant) throws Exception {
        output.writeLong(instant.toEpochMilli());
    }

    private static Instant readInstant(DataInputStream input) throws Exception {
        return Instant.ofEpochMilli(input.readLong());
    }

    private static void writeNullableInstant(DataOutputStream output, @Nullable Instant instant) throws Exception {
        output.writeBoolean(instant != null);

        if (instant == null) {
            return;
        }

        writeInstant(output, instant);
    }

    private static Instant readNullableInstant(DataInputStream input) throws Exception {
        boolean present = input.readBoolean();

        if (!present) {
            return null;
        }

        return readInstant(input);
    }

    private static void writeMap(DataOutputStream output, Map<String, String> map) throws Exception {
        Map<String, String> sorted = new TreeMap<>(map);
        output.writeInt(sorted.size());

        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readMap(DataInputStream input) throws Exception {
        int size = input.readInt();

        if (size < 0) {
            throw new IllegalArgumentException("Negative map size.");
        }

        Map<String, String> result = new LinkedHashMap<>();

        for (int index = 0; index < size; index++) {
            result.put(readString(input), readString(input));
        }

        return result;
    }

}