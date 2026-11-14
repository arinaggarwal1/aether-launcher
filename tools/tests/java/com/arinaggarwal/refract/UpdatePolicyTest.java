package com.arinaggarwal.refract;

/** Exercises the trust boundary without Android or a network connection. */
public class UpdatePolicyTest {
    static void yes(boolean b) { if (!b) throw new AssertionError(); }
    static void no(boolean b) { yes(!b); }
    static void invalid(String pkg, long version, String cert, String sha) {
        try { UpdatePolicy.validateMetadata(pkg, version, cert, sha); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        String ok = "https://github.com/arinaggarwal1/refract-launcher/releases/download/v1/release.json";
        yes(UpdatePolicy.releaseAssetUrl(ok));
        no(UpdatePolicy.releaseAssetUrl("https://github.com/other/refract-launcher/releases/download/v1/release.json"));
        no(UpdatePolicy.releaseAssetUrl("https://github.com.evil.example/arinaggarwal1/refract-launcher/releases/download/x"));
        no(UpdatePolicy.releaseAssetUrl("http://github.com/arinaggarwal1/refract-launcher/releases/download/x"));
        no(UpdatePolicy.releaseAssetUrl(ok + "?redirect=evil"));
        no(UpdatePolicy.releaseAssetUrl("https://github.com/arinaggarwal1/refract-launcher/releases/download/../../other/file"));
        no(UpdatePolicy.releaseAssetUrl("https://github.com/arinaggarwal1/refract-launcher/releases/download/%2e%2e/other/file"));
        no(UpdatePolicy.trustedUrl("https://github.com@evil.example/a"));
        no(UpdatePolicy.trustedUrl("https://release-assets.githubusercontent.com:443/a"));
        yes(UpdatePolicy.trustedUrl("https://release-assets.githubusercontent.com/a?token=x"));
        String hash = "a".repeat(64);
        UpdatePolicy.validateMetadata(UpdatePolicy.PACKAGE, 1, UpdatePolicy.CERT, hash);
        invalid("wrong.package", 1, UpdatePolicy.CERT, hash);
        invalid(UpdatePolicy.PACKAGE, 0, UpdatePolicy.CERT, hash);
        invalid(UpdatePolicy.PACKAGE, 2100000001L, UpdatePolicy.CERT, hash);
        invalid(UpdatePolicy.PACKAGE, 1, hash, hash);
        invalid(UpdatePolicy.PACKAGE, 1, UpdatePolicy.CERT, "short");
        System.out.println("Update policy checks passed");
    }
}
