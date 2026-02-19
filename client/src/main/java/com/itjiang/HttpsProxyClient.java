package com.itjiang;

public class HttpsProxyClient {
    public static void main(String[] args) throws InterruptedException {
        HttpProxyClient.start(true, Config.CLIENT_HTTPS_PORT);
        HttpProxyClient.start(true);
    }
}
