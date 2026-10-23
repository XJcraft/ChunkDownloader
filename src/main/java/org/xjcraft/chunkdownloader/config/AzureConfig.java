package org.xjcraft.chunkdownloader.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.HashMap;
import java.util.Map;

@Data
@RConfig(configName = "azure.yml")
public class AzureConfig {
    public enum ShareMethod {ONEDRIVE, FTP, HTTP}

    @Instance
    public static AzureConfig config = new AzureConfig();

    String clientId = "clientId";
    String clientSecret = "clientSecret";
    String tenantId = "tenantId";
    String userId = "userId";

    String fname = "r.%x%.%y%.mca";
    Map<String, String> worlds = new HashMap<String, String>() {{
        put("MainLand", "map/MainLand/region/%s");
        put("MainLand_nether", "MainLand_nether/DIM-1/region/%s");
        put("MainLand_the_end", "MainLand_the_end/DIM1/region/%s");
    }};

    ShareMethod shareMethod = ShareMethod.ONEDRIVE;

    String ftpHost = "";
    int ftpPort = 21;
    String ftpUser = "";
    String ftpPassword = "";
    // Path prefix on the FTP server. Leave empty when directory layout mirrors OneDrive.
    String ftpRoot = "";
    boolean ftpPassive = true;

    // ---- OP hot rollback ----
    String ftpRollbackUser = "";
    String ftpRollbackPassword = "";
    // 回滚专用主机；留空回退到 ftpHost。内网备份机与分享用的公网地址通常不同
    String ftpRollbackHost = "";
    int ftpRollbackPort = 0;
    // 回滚账号在 FTP 上的根目录；与分享用的 ftpRoot 是两个账号，含义不同，不能共用
    String ftpRollbackRoot = "";
    // vsftpd 多用自签证书；仅在内网可信环境打开
    boolean ftpTrustAllCerts = false;
    int rollbackTokenTtlSeconds = 300;
    // teleport: 传送到同世界出生点; kick: 直接断开
    String rollbackKickMode = "teleport";

    // world 名 -> 该世界 region 文件在 FTP 上的目录（相对 ftpRoot）
    Map<String, String> rollbackWorlds = new HashMap<String, String>() {{
        put("MainLand", "MainLand/dimensions/minecraft/overworld/region");
        put("MainLand_nether", "MainLand_nether/dimensions/minecraft/the_nether/region");
        put("MainLand_the_end", "MainLand_the_end/dimensions/minecraft/the_end/region");
    }};
}
