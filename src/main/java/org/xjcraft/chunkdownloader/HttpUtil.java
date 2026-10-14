package org.xjcraft.chunkdownloader;

import org.xjcraft.chunkdownloader.config.HttpConfig;

public class HttpUtil {

    public static String buildUrl(String relativePath) {
        HttpConfig cfg = HttpConfig.config;

        String base = cfg.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        return base + "/" + path;
    }
}
