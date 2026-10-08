package com.cassandrastudio.engine.cql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import org.junit.jupiter.api.Test;

class CellCodecTest {
    private final CodecRegistry registry = CodecRegistry.DEFAULT;

    @Test
    void textIsAlwaysTheLiteralValueIncludingApostrophes() {
        assertThat(CellCodec.toLiteral(DataTypes.TEXT, "hello", registry)).isEqualTo("'hello'");
        // Review finding: a value that looks quoted must keep its apostrophes.
        assertThat(CellCodec.toLiteral(DataTypes.TEXT, "'hello'", registry)).isEqualTo("'''hello'''");
        assertThat(CellCodec.toLiteral(DataTypes.TEXT, "it's", registry)).isEqualTo("'it''s'");
    }

    @Test
    void otherTypesAreParsedAndValidated() {
        assertThat(CellCodec.toLiteral(DataTypes.INT, "42", registry)).isEqualTo("42");
        assertThat(CellCodec.toLiteral(DataTypes.TIMESTAMP, "2024-03-01T10:00:00.000Z", registry)).startsWith("'2024-03-01T10:00:00");
        assertThat(CellCodec.toLiteral(DataTypes.TEXT, null, registry)).isEqualTo("null");
        assertThatThrownBy(() -> CellCodec.toLiteral(DataTypes.INT, "forty", registry)).hasMessageContaining("not a valid int");
    }
}
