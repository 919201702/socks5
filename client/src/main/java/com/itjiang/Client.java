package com.itjiang;

import java.util.List;

public class Client {

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
