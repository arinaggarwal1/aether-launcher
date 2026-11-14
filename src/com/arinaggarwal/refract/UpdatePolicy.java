package com.arinaggarwal.refract;

import java.net.URI;
import java.util.Locale;

/** Small, platform-independent checks used before accepting release metadata or URLs. */
final class UpdatePolicy {
    static final String PACKAGE = "com.arinaggarwal.refract";
    static final String CERT = "c2810322898575eba8240544dde1fa4dbe87a7ca1d926951d81ceef9c44bdce5";
    static final long MAX_APK = 100L * 1024 * 1024;
    private static final String RELEASE_PATH = "/arinaggarwal1/refract-launcher/releases/download/";

    private UpdatePolicy() { }

    static boolean trustedUrl(String url) {
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null
                    || u.getPort() != -1 || u.getHost() == null) return false;
            String host = u.getHost().toLowerCase(Locale.US);
            return host.equals("api.github.com") || host.equals("github.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean releaseAssetUrl(String url) {
        try {
            URI u = new URI(url);
            String path = u.getPath();
            if (!trustedUrl(url) || !"github.com".equalsIgnoreCase(u.getHost())
                    || path == null || !path.startsWith(RELEASE_PATH)
                    || path.indexOf('\\') >= 0 || u.getRawQuery() != null || u.getRawFragment() != null)
                return false;
            for (String segment : path.split("/")) if (segment.equals(".") || segment.equals("..")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{64}");
    }

    static void validateMetadata(String pkg, long code, String cert, String sha) {
        if (!PACKAGE.equals(pkg) || code <= 0 || code > 2100000000L
                || !CERT.equalsIgnoreCase(cert) || !validHash(sha)) {
            throw new IllegalArgumentException("Release details don't match this launcher.");
        }
    }
}
