package com.itjiang;

import javax.swing.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.itjiang.gui.App;

public class Socks5ProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(Socks5ProxyClient.class);

    public static void main(String[] args) throws InterruptedException {
        SwingUtilities.invokeLater(() -> {
            new App().setVisible(true);
        });
    }
}
