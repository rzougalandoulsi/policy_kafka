package com.itland.governance.support;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Rewrites a built bundle (revision, root, extra files) so tests can exercise activation, rollback and bad bundles. */
public final class Bundles {
  private Bundles() {}

  /** Same content, new revision, declared root {@code governance/<bundle>}. */
  public static byte[] withRevision(byte[] bundle, String revision, String bundleName) throws IOException {
    return rewrite(bundle, revision, bundleName, null);
  }

  /** Adds a Rego file that does not parse, under the bundle's root: OPA refuses to activate it and keeps the previous one. */
  public static byte[] withBrokenPolicy(byte[] bundle, String revision, String bundleName) throws IOException {
    return rewrite(bundle, revision, bundleName, "package governance." + bundleName + ".broken\n\nbroken := (\n");
  }

  private static byte[] rewrite(byte[] bundle, String revision, String bundleName, String brokenRego) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveInputStream in = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(bundle)));
         TarArchiveOutputStream out = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] content = in.readAllBytes();
        if (entry.getName().endsWith(".manifest")) {
          content = ("{\"revision\":\"" + revision + "\",\"roots\":[\"governance/" + bundleName + "\"],\"rego_version\":1}").getBytes(StandardCharsets.UTF_8);
        }
        put(out, entry.getName(), content);
      }
      if (brokenRego != null) put(out, "/broken.rego", brokenRego.getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toByteArray();
  }

  private static void put(TarArchiveOutputStream out, String name, byte[] content) throws IOException {
    TarArchiveEntry copy = new TarArchiveEntry(name);
    copy.setSize(content.length);
    out.putArchiveEntry(copy);
    out.write(content);
    out.closeArchiveEntry();
  }
}
