package org.xjcraft.chunkdownloader.rollback;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RemoteEntry {
    private String name;
    private long size;
    /** 远端修改时间（毫秒）；LIST 回退时服务器可能不提供，为 null。 */
    private Long modify;
}
