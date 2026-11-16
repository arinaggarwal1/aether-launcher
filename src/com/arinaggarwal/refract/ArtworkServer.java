package com.arinaggarwal.refract;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/** A short-lived, token-protected upload endpoint for one selected app. */
final class ArtworkServer implements AutoCloseable {
    interface Importer { boolean importImage(File file); }
    private final ServerSocket server;
    private final File cache;
    private final byte[] page;
    private final Importer importer;
    private final Runnable completed;
    private final String token;
    private final java.util.Set<Socket> clients = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(4);
    private final java.util.concurrent.atomic.AtomicBoolean importing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private static final long LIMIT = 16L * 1024 * 1024;

    ArtworkServer(File cache, byte[] page, Importer importer, Runnable completed) throws IOException {
        this.cache = cache; this.page = page; this.importer = importer; this.completed = completed;
        byte[] random = new byte[8]; new SecureRandom().nextBytes(random);
        StringBuilder key = new StringBuilder();
        for (byte b : random) key.append(String.format(Locale.US, "%02x", b & 255));
        token = key.toString();
        server = new ServerSocket(0);
    }
    String url() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress())
                        return "http://" + a.getHostAddress() + ":" + server.getLocalPort() + "/" + token;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
    void start() {
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket s = server.accept();
                    if (!slots.tryAcquire()) { s.close(); continue; }
                    clients.add(s);
                    new Thread(() -> {
                        try { handle(s); } catch (IOException ignored) { }
                        finally {
                            clients.remove(s);
                            try { s.close(); } catch (IOException ignored) { }
                            slots.release();
                        }
                    }, "artwork-client").start();
                } catch (IOException ignored) { }
            }
        }, "artwork-upload").start();
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Header too long");
        }
        return b.toString("UTF-8");
    }
    private void handle(Socket s) throws IOException {
        s.setSoTimeout(30000);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String[] request = line(in).split(" ");
        if (request.length < 2) return;
        long length = -1; int total = 0;
        for (String h; !(h = line(in)).isEmpty();) {
            total += h.length(); if (total > 16384) throw new IOException("Headers too long");
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                try { length = Long.parseLong(h.substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        if (!request[1].equals("/" + token)) { respond(out, 404, "text/plain", new byte[0]); return; }
        if (request[0].equals("GET")) { respond(out, 200, "text/html; charset=utf-8", page); return; }
        if (!request[0].equals("POST") || length <= 0 || length > LIMIT) {
            respond(out, 400, "text/plain", new byte[0]); return;
        }
        if (!importing.compareAndSet(false, true)) { respond(out, 409, "text/plain", new byte[0]); return; }
        File temp = null;
        boolean ok = false;
        try {
            temp = File.createTempFile("art-upload-", ".image", cache);
            try (OutputStream dst = new FileOutputStream(temp)) {
                byte[] buf = new byte[32768]; long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) throw new EOFException();
                    dst.write(buf, 0, n); remaining -= n;
                }
            }
            if (!closed) ok = importer.importImage(temp);
            respond(out, ok ? 200 : 400, "text/plain", new byte[0]);
            if (ok) { close(); completed.run(); }
        } finally {
            if (temp != null) temp.delete();
            importing.set(false);
        }
    }
    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String h = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (IOException ignored) { }
        }
    }
}
