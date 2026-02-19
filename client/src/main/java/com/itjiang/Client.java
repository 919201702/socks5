package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : System.getProperty("client.mode", "socks5");
        switch (mode.toLowerCase()) {
            case "socks5" -> Socks5ProxyClient.main(args);
            case "http" -> HttpProxyClient.main(args);
            case "https" -> HttpsProxyClient.main(args);
            default -> {
                logger.error("未知 client 模式: {}，支持模式: socks5/http/https", mode);
                System.exit(1);
            }
        }
    }
}
