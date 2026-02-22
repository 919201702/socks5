package com.itjiang;

import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Locale;

public class Client {
    static {configureWindowsConsoleEncoding();}
    private static void configureWindowsConsoleEncoding() {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try {
            Charset gbk = Charset.forName("GBK");
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, gbk));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, gbk));
        } catch (UnsupportedCharsetException ex) {
            System.err.println("Windows console encoding switch failed: GBK charset support is missing (for native-image, enable -H:+AddAllCharsets), will fall back to JVM default output stream.");
        } catch (Exception ex) {
            System.err.println("Windows console encoding switch failed, will fall back to JVM default output stream: " + ex.getMessage());
        }
    }
    public static void main(String[] args) throws Exception {
        ClientRuntime runtime = new ClientRuntime(List.of(
                new ClientRuntime.ServiceSpec("socks5", Config.CLIENT_SOCKS5_ENABLED,
                        () -> Socks5ProxyClient.start(Config.CLIENT_SOCKS5_PORT)),
                new ClientRuntime.ServiceSpec("http", Config.CLIENT_HTTP_ENABLED,
                        () -> HttpProxyClient.start(false, Config.CLIENT_HTTP_PORT)),
                new ClientRuntime.ServiceSpec("https", Config.CLIENT_HTTPS_ENABLED,
                        () -> HttpProxyClient.start(true, Config.CLIENT_HTTPS_PORT))
        ));
        runtime.start();
    }
}
