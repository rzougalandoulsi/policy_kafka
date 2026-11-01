package com.itland.governance.infrastructure.kafka;

import org.apache.avro.Schema;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Exact Avro JSON encoding of SpecificRecords for the outbox/inbox tables (schema resolved by full record name). */
public final class AvroJson {

  private AvroJson() {}

  public static String encode(SpecificRecord record) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      JsonEncoder encoder = EncoderFactory.get().jsonEncoder(record.getSchema(), out);
      DatumWriter<SpecificRecord> writer = new SpecificDatumWriter<>(record.getSchema());
      writer.write(record, encoder);
      encoder.flush();
      return out.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T extends SpecificRecord> T decode(String recordType, String json) {
    try {
      Class<T> type = (Class<T>) Class.forName(recordType);
      Schema schema = SpecificData.get().getSchema(type);
      DatumReader<T> reader = new SpecificDatumReader<>(type);
      return reader.read(null, DecoderFactory.get().jsonDecoder(schema, json));
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Unknown Avro record type " + recordType, e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
