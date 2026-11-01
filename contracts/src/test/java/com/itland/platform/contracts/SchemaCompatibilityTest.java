package com.itland.platform.contracts;

import com.itland.orchestrator.avro.EndOfBatch;
import com.itland.orchestrator.avro.RepoConfirmed;
import com.itland.orchestrator.avro.RepoDesired;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the promise made to the existing Orchestrator/Processor deployment: records written with the
 * original (pre-governance) schemas must still be readable with the extended ones.
 */
class SchemaCompatibilityTest {

  private static final Schema LEGACY_REPO_DESIRED = new Schema.Parser().parse("""
      {"type":"record","name":"RepoDesired","namespace":"com.itland.orchestrator.avro","fields":[
        {"name":"teamId","type":"string"},{"name":"repoId","type":"string"}]}""");

  private static final Schema LEGACY_REPO_CONFIRMED = new Schema.Parser().parse("""
      {"type":"record","name":"RepoConfirmed","namespace":"com.itland.orchestrator.avro","fields":[
        {"name":"teamId","type":"string"},{"name":"repoId","type":"string"},
        {"name":"topics","type":{"type":"array","items":"string"},"default":[]},
        {"name":"pools","type":{"type":"array","items":"string"},"default":[]},
        {"name":"rbacsKey","type":{"type":"array","items":"string"},"default":[]}]}""");

  private static final Schema LEGACY_END_OF_BATCH = new Schema.Parser().parse("""
      {"type":"record","name":"EndOfBatch","namespace":"com.itland.orchestrator.avro","fields":[
        {"name":"type","type":"string"},{"name":"correlationId","type":"string"},{"name":"expectedCount","type":"int"}]}""");

  @Test
  void extendedSchemasReadLegacyRecords() {
    assertBackwardCompatible(RepoDesired.getClassSchema(), LEGACY_REPO_DESIRED);
    assertBackwardCompatible(RepoConfirmed.getClassSchema(), LEGACY_REPO_CONFIRMED);
    assertBackwardCompatible(EndOfBatch.getClassSchema(), LEGACY_END_OF_BATCH);
  }

  @Test
  void legacySchemasReadExtendedRecords() {
    // Forward compatibility: an un-upgraded consumer ignores the new fields.
    assertBackwardCompatible(LEGACY_REPO_CONFIRMED, RepoConfirmed.getClassSchema());
    assertBackwardCompatible(LEGACY_END_OF_BATCH, EndOfBatch.getClassSchema());
  }

  private static void assertBackwardCompatible(Schema reader, Schema writer) {
    var result = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
    assertThat(result.getType())
        .as(result.getDescription())
        .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
  }
}
