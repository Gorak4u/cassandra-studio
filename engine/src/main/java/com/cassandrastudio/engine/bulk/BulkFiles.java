package com.cassandrastudio.engine.bulk;

import com.cassandrastudio.engine.util.ApiException;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Local files for bulk jobs: where they go by default, overwrite rules, gzip, byte counting. */
public final class BulkFiles {
    private BulkFiles() {}

    /** The user's Downloads folder (XDG_DOWNLOAD_DIR, ~/Downloads), or the home folder. */
    public static Path downloadsDir() {
        String xdg = System.getenv("XDG_DOWNLOAD_DIR");
        if (xdg != null && !xdg.isBlank() && Files.isDirectory(Path.of(xdg))) return Path.of(xdg);
        Path home = Path.of(System.getProperty("user.home"));
        Path dl = home.resolve("Downloads");
        return Files.isDirectory(dl) ? dl : home;
    }

    /** A path the user typed: ~ expanded, relative paths under Downloads. 400 when it is not a path. */
    public static Path resolve(String text) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("path is required");
        String t = text.trim();
        if (t.equals("~") || t.startsWith("~/")) t = System.getProperty("user.home") + t.substring(1);
        try {
            Path p = Path.of(t);
            return (p.isAbsolute() ? p : downloadsDir().resolve(p)).normalize();
        } catch (InvalidPathException e) {
            throw ApiException.badRequest("'" + text + "' is not a valid path: " + e.getReason());
        }
    }

    /** Checks an unload target: its folder exists, it is not a folder, and it exists only when overwrite is set. */
    public static void checkTarget(Path p, boolean overwrite) {
        Path parent = p.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw ApiException.badRequest("Folder " + parent + " does not exist");
        }
        if (Files.isDirectory(p)) throw ApiException.badRequest(p + " is a folder; give a file name");
        if (Files.exists(p) && !overwrite) {
            throw new ApiException(409, "file_exists", p + " already exists. Choose another name or allow overwriting.",
                    Map.of("path", p.toString()));
        }
        if (!Files.isWritable(parent)) throw ApiException.badRequest("Folder " + parent + " is not writable");
    }

    /** Checks a load source: an existing, readable file. */
    public static void checkSource(Path p) {
        if (!Files.exists(p)) throw ApiException.badRequest("File " + p + " does not exist");
        if (!Files.isRegularFile(p)) throw ApiException.badRequest(p + " is not a file");
        if (!Files.isReadable(p)) throw ApiException.badRequest("File " + p + " is not readable");
    }

    public static boolean looksGzip(Path p) {
        if (p.getFileName().toString().toLowerCase().endsWith(".gz")) return true;
        try (InputStream in = Files.newInputStream(p)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens a source, decompressing gzip; {@code counter} counts the raw bytes read from disk. */
    public static InputStream openInput(Path p, boolean gzip, AtomicLong counter) throws IOException {
        InputStream raw = new CountingInput(Files.newInputStream(p), counter);
        return gzip ? new GZIPInputStream(raw, 1 << 16) : new BufferedInputStream(raw, 1 << 16);
    }

    /** Opens a target (create-new unless overwrite), compressing when asked; {@code counter} counts bytes on disk. */
    public static OutputStream openOutput(Path p, boolean overwrite, boolean gzip, AtomicLong counter) throws IOException {
        OutputStream raw = overwrite
                ? Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                : Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        OutputStream counted = new CountingOutput(raw, counter);
        return gzip ? new GZIPOutputStream(counted, 1 << 16) : new java.io.BufferedOutputStream(counted, 1 << 16);
    }

    /** {@code data.csv} + {@code .rejected.csv} = {@code data.rejected.csv} (keeps .gz off the side files). */
    public static Path sibling(Path p, String suffix) {
        String name = p.getFileName().toString();
        if (name.toLowerCase().endsWith(".gz")) name = name.substring(0, name.length() - 3);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return p.resolveSibling(stem + suffix);
    }

    private static final class CountingInput extends FilterInputStream {
        private final AtomicLong counter;

        CountingInput(InputStream in, AtomicLong counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) counter.incrementAndGet();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) counter.addAndGet(n);
            return n;
        }
    }

    private static final class CountingOutput extends FilterOutputStream {
        private final AtomicLong counter;

        CountingOutput(OutputStream out, AtomicLong counter) {
            super(out);
            this.counter = counter;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            counter.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            counter.addAndGet(len);
        }
    }
}
