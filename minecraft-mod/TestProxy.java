import java.net.*;
import java.io.*;
import javax.net.ssl.*;

public class TestProxy {
    public static void main(String[] args) throws Exception {
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/1.0.26/neoform-runtime-1.0.26.pom";
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("http.proxyHost: " + System.getProperty("http.proxyHost"));
        System.out.println("http.proxyPort: " + System.getProperty("http.proxyPort"));
        System.out.println("https.proxyHost: " + System.getProperty("https.proxyHost"));
        System.out.println("https.proxyPort: " + System.getProperty("https.proxyPort"));
        System.out.println("socksProxyHost: " + System.getProperty("socksProxyHost"));
        System.out.println("socksProxyPort: " + System.getProperty("socksProxyPort"));
        System.out.println();

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 7890));
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection(proxy);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();
            System.out.println("HTTP PROXY: Response code: " + conn.getResponseCode());
            System.out.println("SUCCESS with HTTP proxy!");
        } catch (Exception e) {
            System.out.println("HTTP PROXY FAILED: " + e.getMessage());
        }

        System.out.println();

        Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 7890));
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection(socksProxy);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();
            System.out.println("SOCKS5 PROXY: Response code: " + conn.getResponseCode());
            System.out.println("SUCCESS with SOCKS5 proxy!");
        } catch (Exception e) {
            System.out.println("SOCKS5 PROXY FAILED: " + e.getMessage());
        }
    }
}
