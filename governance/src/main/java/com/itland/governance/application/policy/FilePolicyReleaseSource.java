package com.itland.governance.application.policy;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Release mode: the expected release comes from a release manifest file ({@code contracts/release-manifest.schema.json}
 * in the policy repository: {@code releaseId} plus {@code bundles.<name>.revision}). The file is re-read whenever its
 * modification time or size changes, so a ConfigMap update is picked up without a restart (the deployment rolls the
 * pods anyway because the ConfigMap checksum is a pod annotation). A missing or invalid file, or a {@code releaseId}
 * that does not equal the id computed from the revisions, yields no release: every decision then fails closed and
 * readiness stays DOWN, which is the intended behaviour for a broken promotion.
 */
public final class FilePolicyReleaseSource implements PolicyReleaseSource {

  private static final Logger log = LoggerFactory.getLogger(FilePolicyReleaseSource.class);

  private final Path file;
  private final Json json;
  private volatile Cached cached;

  private record Cached(FileTime modified, long size, Optional<PolicyRelease> release) {}

  public FilePolicyReleaseSource(Path file, Json json) {
    this.file = file;
    this.json = json;
  }

  @Override
  public String mode() {
    return RELEASE;
  }

  @Override
  public String description() {
    return "file:" + file;
  }

  @Override
  public Optional<PolicyRelease> expected() {
    try {
      if (!Files.isRegularFile(file)) {
        warnOnce("Release manifest " + file + " does not exist; no policy release is defined");
        cached = null;
        return Optional.empty();
      }
      FileTime modified = Files.getLastModifiedTime(file);
      long size = Files.size(file);
      Cached c = cached;
      if (c != null && c.modified().equals(modified) && c.size() == size) return c.release();
      Optional<PolicyRelease> parsed = parse(Files.readString(file));
      cached = new Cached(modified, size, parsed);
      parsed.ifPresent(r -> log.info("Policy release {} loaded from {} ({} bundles)", r.id(), file, r.revisions().size()));
      return parsed;
    } catch (IOException | RuntimeException e) {
      log.error("Release manifest {} cannot be used: {}", file, e.toString());
      cached = null;
      return Optional.empty();
    }
  }

  /** Parses a release manifest document; throws when its id does not match its revisions. */
  Optional<PolicyRelease> parse(String content) {
    Map<String, Object> doc = json.readMap(content);
    if (!(doc.get("bundles") instanceof Map<?, ?> bundles) || bundles.isEmpty()) {
      throw new IllegalStateException("release manifest has no bundles");
    }
    Map<String, String> revisions = new TreeMap<>();
    bundles.forEach((name, entry) -> {
      Object revision = entry instanceof Map<?, ?> m ? m.get("revision") : null;
      if (revision == null || String.valueOf(revision).isBlank()) {
        throw new IllegalStateException("release manifest bundle " + name + " has no revision");
      }
      revisions.put(String.valueOf(name), String.valueOf(revision));
    });
    PolicyRelease release = PolicyRelease.of(revisions);
    Object declared = doc.get("releaseId");
    if (declared != null && !release.id().equals(String.valueOf(declared))) {
      throw new IllegalStateException("release manifest declares " + declared + " but its revisions compute to " + release.id());
    }
    return Optional.of(release);
  }

  private String lastWarning;

  private void warnOnce(String message) {
    if (!message.equals(lastWarning)) {
      log.warn(message);
      lastWarning = message;
    }
  }
}
