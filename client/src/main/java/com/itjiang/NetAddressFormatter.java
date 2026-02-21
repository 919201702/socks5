package com.itjiang;

public final class NetAddressFormatter {

    private NetAddressFormatter() {
    }

    public static String hostPort(String host, int port) {
        if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }
}
