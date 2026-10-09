package com.cassandrastudio.engine.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.internal.core.type.UserDefinedTypeBuilder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValueConverterTest {
    private final ValueConverter conv = new ValueConverter(CodecRegistry.DEFAULT, null, null, null, ValueConverter.BlobFormat.HEX);
    private final UserDefinedType address = new UserDefinedTypeBuilder("ks", "address")
            .withField("street", DataTypes.TEXT).withField("zip", DataTypes.INT).build();
    private final TupleType pair = DataTypes.tupleOf(DataTypes.INT, DataTypes.TEXT);

    /** format -> parse and toJson -> fromJson give the value back, for every type. */
    @Test
    void roundTripsEveryType() throws Exception {
        UdtValue udt = address.newValue().setString(0, "Main St, 1 \"A\"").setInt(1, 12345);
        TupleValue tup = pair.newValue().setInt(0, 7).setString(1, "it's");
        Object[][] cases = {
                {DataTypes.TEXT, "héllo, \"world\"\nline2"},
                {DataTypes.ASCII, "plain"},
                {DataTypes.INT, 42}, {DataTypes.BIGINT, Long.MIN_VALUE}, {DataTypes.SMALLINT, (short) -3},
                {DataTypes.TINYINT, (byte) 9}, {DataTypes.VARINT, new BigInteger("123456789012345678901234567890")},
                {DataTypes.DECIMAL, new BigDecimal("3.14159265358979323846264338327950")},
                {DataTypes.FLOAT, 1.5f}, {DataTypes.DOUBLE, 0.1}, {DataTypes.DOUBLE, Double.NaN},
                {DataTypes.BOOLEAN, true},
                {DataTypes.UUID, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")},
                {DataTypes.TIMEUUID, UUID.fromString("e3d9b7a0-5b0c-11ee-8c99-0242ac120002")},
                {DataTypes.TIMESTAMP, Instant.parse("2024-05-01T10:15:30.123Z")},
                {DataTypes.DATE, LocalDate.of(1999, 12, 31)},
                {DataTypes.TIME, LocalTime.of(23, 59, 58, 123_456_789)},
                {DataTypes.INET, InetAddress.getByName("2001:db8::1")},
                {DataTypes.BLOB, ByteBuffer.wrap(new byte[] {0, 1, (byte) 0xfe, (byte) 0xff})},
                {DataTypes.DURATION, CqlDuration.from("1mo2d3h4m5s")},
                {DataTypes.listOf(DataTypes.TEXT), List.of("a", "b'c", "d,e")},
                {DataTypes.setOf(DataTypes.INT), new LinkedHashSet<>(List.of(1, 2, 3))},
                {DataTypes.mapOf(DataTypes.TEXT, DataTypes.TIMESTAMP), Map.of("k", Instant.parse("2020-01-01T00:00:00Z"))},
                {DataTypes.mapOf(DataTypes.INT, DataTypes.listOf(DataTypes.TEXT)), Map.of(1, List.of("x"))},
                {address, udt},
                {pair, tup},
                {DataTypes.listOf(address), List.of(udt)},
        };
        for (Object[] c : cases) {
            DataType t = (DataType) c[0];
            Object v = c[1];
            String text = conv.format(t, v);
            assertThat(conv.parse(t, text)).as("text %s for %s", text, t).isEqualTo(v);
            var json = ValueConverter.JSON.readTree(ValueConverter.JSON.writeValueAsString(conv.toJson(t, v)));
            assertThat(conv.fromJson(t, json)).as("json %s for %s", json, t).isEqualTo(v);
        }
    }

    @Test
    void formatsScalarsInTheirNaturalForm() {
        assertThat(conv.format(DataTypes.TIMESTAMP, Instant.parse("2024-05-01T10:15:30Z"))).isEqualTo("2024-05-01T10:15:30Z");
        assertThat(conv.format(DataTypes.BLOB, ByteBuffer.wrap(new byte[] {(byte) 0xca, (byte) 0xfe}))).isEqualTo("0xcafe");
        assertThat(conv.format(DataTypes.listOf(DataTypes.TEXT), List.of("a"))).isEqualTo("['a']");
        assertThat(conv.toJson(DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT), Map.of("a", 1)).toString()).isEqualTo("{\"a\":1}");
        ValueConverter b64 = new ValueConverter(CodecRegistry.DEFAULT, null, null, null, ValueConverter.BlobFormat.BASE64);
        assertThat(b64.format(DataTypes.BLOB, ByteBuffer.wrap(new byte[] {(byte) 0xca, (byte) 0xfe}))).isEqualTo("yv4=");
    }

    @Test
    void acceptsCommonInputForms() {
        Instant t = Instant.parse("2024-01-02T03:04:05Z");
        assertThat(conv.parse(DataTypes.TIMESTAMP, "2024-01-02 03:04:05.000+0000")).isEqualTo(t);
        assertThat(conv.parse(DataTypes.TIMESTAMP, "2024-01-02T05:04:05+02:00")).isEqualTo(t);
        assertThat(conv.parse(DataTypes.TIMESTAMP, "2024-01-02T03:04:05")).isEqualTo(t);
        assertThat(conv.parse(DataTypes.TIMESTAMP, String.valueOf(t.toEpochMilli()))).isEqualTo(t);
        assertThat(conv.parse(DataTypes.TIMESTAMP, "2024-01-02")).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"));
        assertThat(conv.parse(DataTypes.BOOLEAN, "Yes")).isEqualTo(true);
        assertThat(conv.parse(DataTypes.INT, " 1e3 ")).isEqualTo(1000);
        assertThat(conv.parse(DataTypes.BLOB, "yv4=")).isEqualTo(ByteBuffer.wrap(new byte[] {(byte) 0xca, (byte) 0xfe}));
        assertThat(conv.parse(DataTypes.BLOB, "cafe")).isEqualTo(ByteBuffer.wrap(new byte[] {(byte) 0xca, (byte) 0xfe}));
        // JSON inside a CSV field for collections and UDTs
        assertThat(conv.parse(DataTypes.listOf(DataTypes.TEXT), "[\"a\",\"b\"]")).isEqualTo(List.of("a", "b"));
        assertThat(conv.parse(DataTypes.setOf(DataTypes.INT), "[3,1]")).isEqualTo(Set.of(1, 3));
        UdtValue u = (UdtValue) conv.parse(address, "{\"street\":\"x\",\"zip\":1}");
        assertThat(u.getString("street")).isEqualTo("x");
        assertThat(conv.parse(address, "{street: 'y', zip: 2}")).isEqualTo(address.newValue().setString(0, "y").setInt(1, 2));
    }

    @Test
    void usesPatternsAndZones() {
        ValueConverter c = new ValueConverter(CodecRegistry.DEFAULT, "dd/MM/yyyy HH:mm", "dd.MM.yyyy", ZoneId.of("Europe/Berlin"), null);
        Instant t = Instant.parse("2024-07-01T08:30:00Z");
        assertThat(c.format(DataTypes.TIMESTAMP, t)).isEqualTo("01/07/2024 10:30");
        assertThat(c.parse(DataTypes.TIMESTAMP, "01/07/2024 10:30")).isEqualTo(t);
        assertThat(c.parse(DataTypes.DATE, "31.12.1999")).isEqualTo(LocalDate.of(1999, 12, 31));
        assertThat(c.format(DataTypes.DATE, LocalDate.of(1999, 12, 31))).isEqualTo("31.12.1999");
        assertThat(ValueConverter.checkPattern("yyyy-MM-dd")).isNull();
        assertThat(ValueConverter.checkPattern("{bad")).contains("not a valid");
    }

    @Test
    void rejectsBadValuesWithAReason() {
        assertThatThrownBy(() -> conv.parse(DataTypes.INT, "abc")).hasMessageContaining("'abc' is not a valid int");
        assertThatThrownBy(() -> conv.parse(DataTypes.INT, "3000000000")).hasMessageContaining("not a valid int");
        assertThatThrownBy(() -> conv.parse(DataTypes.TINYINT, "1.5")).hasMessageContaining("tinyint");
        assertThatThrownBy(() -> conv.parse(DataTypes.TIMEUUID, "0f8fad5b-d9cb-469f-a165-70867728950e")).hasMessageContaining("version 1");
        assertThatThrownBy(() -> conv.parse(DataTypes.INET, "example.com")).hasMessageContaining("host names");
        assertThatThrownBy(() -> conv.parse(DataTypes.BOOLEAN, "maybe")).hasMessageContaining("true/false");
        assertThatThrownBy(() -> conv.parse(DataTypes.TIMESTAMP, "yesterday")).hasMessageContaining("not a valid timestamp");
        assertThatThrownBy(() -> conv.parse(DataTypes.ASCII, "é")).hasMessageContaining("non-ASCII");
        assertThatThrownBy(() -> conv.parse(DataTypes.COUNTER, "1")).hasMessageContaining("counter");
        assertThatThrownBy(() -> conv.parse(DataTypes.listOf(DataTypes.INT), "[1, x]")).hasMessageContaining("list<int>");
        assertThatThrownBy(() -> conv.parse(address, "{\"nope\":1}")).hasMessageContaining("'nope' is not part");
    }
}
