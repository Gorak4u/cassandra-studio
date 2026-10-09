package com.cassandrastudio.engine.gclog;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A GC log uploaded from the UI (GCL-1): plain text, .gz, or a .zip of logs (also .gz inside),
 * detected by content, streamed into the parser without holding the file in memory.
 */
public final class GcLogUpload {
    private GcLogUpload() {}

    /** Thrown when the decompressed content goes over the limit. */
    public static final class TooLargeException extends IOException {
        TooLargeException(long limit) {
            super("The upload is larger than " + GcLogFiles.mb(limit) + " uncompressed");
        }
    }

    /** Parses {@code body}; returns the files it contained (one for plain or .gz uploads). */
    public static List<GcReport.SourceFile> read(InputStream body, String name, long maxBytes, GcLogParser parser)
            throws IOException {
        Limit limit = new Limit(maxBytes);
        BufferedInputStream in = new BufferedInputStream(body, 1 << 16);
        List<GcReport.SourceFile> files = new ArrayList<>();
        int kind = magic(in);
        if (kind == 2) {
            ZipInputStream zip = new ZipInputStream(in);
            List<String> names = new ArrayList<>();
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (e.isDirectory() || n.startsWith("__MACOSX/") || base.startsWith(".")) continue;
                BufferedInputStream entry = new BufferedInputStream(nonClosing(zip), 1 << 16);
                long before = limit.count;
                feed(entry, magic(entry), limit, parser);
                files.add(new GcReport.SourceFile(n, limit.count - before, false));
                names.add(n);
            }
            if (names.isEmpty()) throw new IOException("The zip file " + name + " has no files");
        } else {
            feed(in, kind, limit, parser);
            files.add(new GcReport.SourceFile(name, limit.count, false));
        }
        return files;
    }

    private static void feed(BufferedInputStream in, int kind, Limit limit, GcLogParser parser) throws IOException {
        InputStream src = kind == 1 ? new GZIPInputStream(nonClosing(in), 1 << 16) : in;
        parser.feed(limit.wrap(src));
    }

    /** 1 gzip, 2 zip, 0 anything else (text). */
    private static int magic(BufferedInputStream in) throws IOException {
        in.mark(4);
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        in.reset();
        if (a == 0x1f && b == 0x8b) return 1;
        if (a == 'P' && b == 'K' && c == 3 && d == 4) return 2;
        return 0;
    }

    private static InputStream nonClosing(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() {
                // the zip stream is closed by its owner
            }
        };
    }

    /** Counts decompressed bytes across all files and stops at the limit. */
    private static final class Limit {
        final long max;
        long count;

        Limit(long max) {
            this.max = max;
        }

        InputStream wrap(InputStream in) {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int r = super.read();
                    if (r >= 0) add(1);
                    return r;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int r = super.read(b, off, len);
                    if (r > 0) add(r);
                    return r;
                }

                @Override
                public void close() {
                    // the caller closes the request body
                }
            };
        }

        void add(long n) throws TooLargeException {
            count += n;
            if (count > max) throw new TooLargeException(max);
        }
    }
}
