package org.xjcraft.chunkdownloader.rollback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 版本戳排序。目录解析本身交给 Commons Net 的 FTPFile。
 */
public final class FtpListing {

    private FtpListing() {
    }

    public static List<String> sortStampsDescending(String[] stamps) {
        List<String> list = new ArrayList<String>(Arrays.asList(stamps));
        // YYYYMMDD-HHMM 定长且字典序等于时间序
        Collections.sort(list, Collections.reverseOrder());
        return list;
    }
}
