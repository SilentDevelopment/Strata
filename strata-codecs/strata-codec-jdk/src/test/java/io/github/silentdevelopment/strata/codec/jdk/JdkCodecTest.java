package io.github.silentdevelopment.strata.codec.jdk;


import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;

final class JdkCodecTest {

    @Test
    void roundTripsSerializableValue() {
        Type<TestValue> type = Type.of("test", TestValue.class);
        JdkCodec<TestValue> codec = JdkCodec.create();
        Encoded encoded = codec.encode(new TestValue("a", 1));
        TestValue decoded = codec.decode(encoded, type);
        assertThat(decoded).isEqualTo(new TestValue("a", 1));
    }

    private record TestValue(String name, int count) implements Serializable {
    }

}
