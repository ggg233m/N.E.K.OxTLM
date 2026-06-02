import java.net.*;
import java.io.*;
import javax.net.ssl.*;

public class TestSSL {
    public static void main(String[] args) throws Exception {
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/1.0.26/neoform-runtime-1.0.26.pom";
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("Java vendor: " + System.getProperty("java.vendor"));
        System.out.println("trustStoreType: " + System.getProperty("javax.net.ssl.trustStoreType", "default"));
        System.out.println("preferIPv4Stack: " + Boolean.getBoolean("java.net.preferIPv4Stack"));
        System.out.println();
        
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();
            System.out.println("Response code: " + conn.getResponseCode());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < 5) {
                    System.out.println(line);
                    count++;
                }
            }
            System.out.println("SUCCESS: SSL connection works!");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
