package org.xjcraft.chunkdownloader;

import org.xjcraft.chunkdownloader.config.AzureConfig;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class FtpUtil {

    public static String buildUrl(String relativePath) {
        AzureConfig cfg = AzureConfig.config;

        String user = encode(cfg.getFtpUser());
        String pass = encode(cfg.getFtpPassword());

        String root = cfg.getFtpRoot();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        String fullPath = root.isEmpty() ? path : root + "/" + path;

        return "ftp://" + user + ":" + pass + "@"
                + cfg.getFtpHost() + ":" + cfg.getFtpPort()
                + "/" + fullPath;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
