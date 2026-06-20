package io.github.silentdevelopment.strata.generator;


import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
/**
 * Generates key path components for insert operations.
 */
public interface KeyGenerator {

    @NotNull String generate();

    static KeyGenerator uuid() {
        return () -> UUID.randomUUID().toString();
    }

    static KeyGenerator ulid() {
        return new UlidGenerator();
    }

    final class UlidGenerator implements KeyGenerator {

        private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
        private final SecureRandom random = new SecureRandom();

        @Override
        public synchronized @NotNull String generate() {
            long time = Instant.now().toEpochMilli();
            byte[] bytes = new byte[16];
            bytes[0] = (byte) (time >>> 40);
            bytes[1] = (byte) (time >>> 32);
            bytes[2] = (byte) (time >>> 24);
            bytes[3] = (byte) (time >>> 16);
            bytes[4] = (byte) (time >>> 8);
            bytes[5] = (byte) time;
            byte[] randomBytes = new byte[10];
            random.nextBytes(randomBytes);
            System.arraycopy(randomBytes, 0, bytes, 6, 10);
            return encode(bytes);
        }

        private String encode(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            StringBuilder builder = new StringBuilder(26);
            int bitBuffer = 0;
            int bitCount = 0;
            for (byte current : bytes) {
                bitBuffer = (bitBuffer << 8) | (current & 0xff);
                bitCount += 8;
                while (bitCount >= 5) {
                    int index = (bitBuffer >>> (bitCount - 5)) & 31;
                    builder.append(ENCODING[index]);
                    bitCount -= 5;
                }
            }
            if (bitCount > 0) {
                builder.append(ENCODING[(bitBuffer << (5 - bitCount)) & 31]);
            }
            while (builder.length() < 26) {
                builder.append('0');
            }
            if (builder.length() > 26) {
                return builder.substring(0, 26);
            }
            return builder.toString();
        }

    }

}
