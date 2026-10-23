package org.xjcraft.chunkdownloader.rollback;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯坐标/文件名换算，不依赖 Bukkit，便于单测。
 */
public final class RegionPaths {

    private static final Pattern STAMP =
            Pattern.compile("^(.*\\.mca)\\.(\\d{8}-\\d{4})$");

    private RegionPaths() {
    }

    /** 区块坐标 -> region 坐标（32 区块一个 region，负数向下取整）。 */
    public static int regionOf(int chunkCoord) {
        return chunkCoord >> 5;
    }

    public static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /**
     * 从 _history 下的远端文件名解析版本戳（YYYYMMDD-HHMM）。
     * 不是该 region 的版本、或带 .partial 等后缀时返回 null。
     */
    public static String versionStamp(String regionFileName, String remoteFileName) {
        Matcher m = STAMP.matcher(remoteFileName);
        if (!m.matches() || !m.group(1).equals(regionFileName)) {
            return null;
        }
        return m.group(2);
    }
}
