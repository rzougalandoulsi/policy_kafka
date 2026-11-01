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

/** Rewrites a built bundle with a different revision so tests can exercise activation and rollback. */
public final class Bundles {
  private Bundles() {}

  public static byte[] withRevision(byte[] bundle, String revision) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveInputStream in = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(bundle)));
         TarArchiveOutputStream out = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] content = in.readAllBytes();
        if (entry.getName().endsWith(".manifest")) {
          content = ("{\"revision\":\"" + revision + "\",\"roots\":[\"governance\"],\"rego_version\":1}").getBytes(StandardCharsets.UTF_8);
        }
        TarArchiveEntry copy = new TarArchiveEntry(entry.getName());
        copy.setSize(content.length);
        out.putArchiveEntry(copy);
        out.write(content);
        out.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }
}
