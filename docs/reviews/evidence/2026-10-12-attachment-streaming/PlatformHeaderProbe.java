import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.android.okhttp.OkHttpClient;
import com.android.okhttp.internal.huc.HttpURLConnectionImpl;

public final class PlatformHeaderProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("http.agent", "只读合成附件复现");
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Thread listener = new Thread(() -> {
                try {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            InputStream input = socket.getInputStream();
                            int length = 0;
                            String contentType = null;
                            while (true) {
                                String line = readLine(input);
                                if (line.isEmpty()) break;
                                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                                if (line.toLowerCase().startsWith("content-type:")) contentType = line.substring(13).trim();
                            }
                            for (int offset = 0; offset < length; offset++) if (input.read() < 0) throw new EOFException();
                            System.out.println("attempt=" + attempt + " contentType=" + contentType + " bodyLength=" + length);
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                        }
                    }
                } catch (Exception cause) { throw new RuntimeException(cause); }
            });
            listener.start();
            for (int attempt = 0; attempt < 2; attempt++) {
                OkHttpClient client = new OkHttpClient();
                client.setProxy(Proxy.NO_PROXY);
                client.setDns(host -> java.util.Collections.singletonList(InetAddress.getLoopbackAddress()));
                client.setSocketFactory(javax.net.SocketFactory.getDefault());
                client.setSslSocketFactory((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault());
                client.setConnectionPool(new com.android.okhttp.ConnectionPool(0, 1000));
                java.lang.reflect.Method defaults = OkHttpClient.class.getDeclaredMethod("copyWithDefaults");
                defaults.setAccessible(true);
                client = (OkHttpClient) defaults.invoke(client);
                HttpURLConnection connection = new HttpURLConnectionImpl(new URL("http://127.0.0.1:" + server.getLocalPort() + "/content"), client);
                connection.setRequestMethod("PUT");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(3L);
                if (attempt == 1) connection.setRequestProperty("Content-Type", "image/png");
                connection.connect();
                try (OutputStream out = connection.getOutputStream()) { out.write(new byte[]{1, 2, 3}); }
                System.out.println("response=" + connection.getResponseCode());
                connection.disconnect();
            }
            listener.join(6000);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            if (previous == '\r' && next == '\n') return output.toString(StandardCharsets.US_ASCII).substring(0, output.size() - 1);
            output.write(next);
            previous = next;
        }
    }
}
