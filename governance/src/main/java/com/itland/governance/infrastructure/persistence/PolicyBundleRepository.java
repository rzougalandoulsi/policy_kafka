package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.domain.policy.VersionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PolicyBundleRepository {

  private static final String META = "id, revision, sha256, manifest, test_report, status, comment, uploaded_by, uploaded_at, activated_at, retired_at, failure_reason";

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public PolicyBundleRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<PolicyBundleVersion> mapper = (rs, i) -> new PolicyBundleVersion(
      rs.getString("id"), rs.getString("revision"), rs.getString("sha256"), json.readMap(rs.getString("manifest")),
      rs.getString("test_report") == null ? null : json.readMap(rs.getString("test_report")),
      VersionStatus.valueOf(rs.getString("status")), rs.getString("comment"), rs.getString("uploaded_by"),
      Jdbc.instant(rs, "uploaded_at"), Jdbc.instant(rs, "activated_at"), Jdbc.instant(rs, "retired_at"), rs.getString("failure_reason"));

  public void insert(String id, String revision, String sha256, byte[] bundle, Map<String, Object> manifest, Map<String, Object> testReport,
                     String comment, String uploadedBy, Instant now) {
    jdbc.sql("""
        INSERT INTO policy_bundle_version (id, revision, sha256, bundle, manifest, test_report, status, comment, uploaded_by, uploaded_at)
        VALUES (:id, :rev, :sha, :bundle, cast(:manifest as jsonb), cast(:report as jsonb), 'UPLOADED', :comment, :by, :now)
        """)
        .param("id", id).param("rev", revision).param("sha", sha256).param("bundle", bundle).param("manifest", json.write(manifest))
        .param("report", testReport == null ? null : json.write(testReport)).param("comment", comment).param("by", uploadedBy).param("now", Jdbc.ts(now))
        .update();
  }

  public Optional<PolicyBundleVersion> findById(String id) {
    return jdbc.sql("SELECT " + META + " FROM policy_bundle_version WHERE id = :id").param("id", id).query(mapper).optional();
  }

  public Optional<PolicyBundleVersion> findByRevision(String revision) {
    return jdbc.sql("SELECT " + META + " FROM policy_bundle_version WHERE revision = :r").param("r", revision).query(mapper).optional();
  }

  public Optional<PolicyBundleVersion> findActive() {
    return jdbc.sql("SELECT " + META + " FROM policy_bundle_version WHERE status = 'ACTIVE'").query(mapper).optional();
  }

  public List<PolicyBundleVersion> list() {
    return jdbc.sql("SELECT " + META + " FROM policy_bundle_version ORDER BY uploaded_at DESC").query(mapper).list();
  }

  public Optional<byte[]> loadBytes(String id) {
    return jdbc.sql("SELECT bundle FROM policy_bundle_version WHERE id = :id").param("id", id).query(byte[].class).optional();
  }

  public void activate(String id, Instant now) {
    jdbc.sql("UPDATE policy_bundle_version SET status = 'RETIRED', retired_at = :now WHERE status = 'ACTIVE' AND id <> :id")
        .param("now", Jdbc.ts(now)).param("id", id).update();
    int n = jdbc.sql("UPDATE policy_bundle_version SET status = 'ACTIVE', activated_at = :now, retired_at = NULL, failure_reason = NULL WHERE id = :id")
        .param("now", Jdbc.ts(now)).param("id", id).update();
    if (n != 1) throw new IllegalArgumentException("Unknown policy bundle " + id);
  }

  public void markFailed(String id, String reason, Instant now) {
    jdbc.sql("UPDATE policy_bundle_version SET status = 'FAILED', failure_reason = :reason, retired_at = :now WHERE id = :id")
        .param("reason", reason).param("now", Jdbc.ts(now)).param("id", id).update();
  }
}
