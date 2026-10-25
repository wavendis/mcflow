package dev.mcflow;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Mc'Flow's web requests (LRCLIB lyrics, GitHub releases). They trust the certificates Java trusts and also the ones
 * Windows trusts: antivirus that scans HTTPS (Avast, Kaspersky, ESET...) puts its own certificate only in Windows'
 * store, and old game Javas (e.g. 8u51 for 1.8.9) don't know today's certificates at all. Without this, lyrics
 * silently never arrive for those players.
 */
final class Net {
    private static final SSLSocketFactory TLS = tls();

    private Net() {}

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (TLS != null && c instanceof HttpsURLConnection https) https.setSSLSocketFactory(TLS);
        c.setRequestProperty("User-Agent", "McFlow (Minecraft mod)");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        return c;
    }

    /** Java's own trust plus Windows' certificate store; null (Java's alone) where Windows' store isn't available. */
    private static SSLSocketFactory tls() {
        try {
            X509TrustManager java = trust(null);
            KeyStore store = KeyStore.getInstance("Windows-ROOT");
            store.load(null, null);
            X509TrustManager windows = trust(store);
            X509TrustManager either = new X509TrustManager() {
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        java.checkServerTrusted(chain, auth);
                    } catch (CertificateException notJava) {
                        windows.checkServerTrusted(chain, auth);
                    }
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    java.checkClientTrusted(chain, auth);
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    X509Certificate[] a = java.getAcceptedIssuers(), b = windows.getAcceptedIssuers();
                    X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
                    System.arraycopy(b, 0, all, a.length, b.length);
                    return all;
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{either}, null);
            return context.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }

    private static X509TrustManager trust(KeyStore store) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager t : factory.getTrustManagers()) {
            if (t instanceof X509TrustManager x) return x;
        }
        throw new IllegalStateException("no X.509 trust manager");
    }

    /** Tries both services on whatever Java runs this (e.g. a launcher's old Java 8): prints the HTTP status or the error. */
    public static void main(String[] args) {
        for (String url : new String[]{"https://lrclib.net/api/search?q=hello", "https://api.github.com/repos/wavendis/mcflow/releases/latest"}) {
            try {
                System.out.println(url + " -> " + open(url).getResponseCode());
            } catch (Exception e) {
                System.out.println(url + " -> " + e);
            }
        }
    }
}
