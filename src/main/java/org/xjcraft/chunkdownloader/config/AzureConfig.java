package org.xjcraft.chunkdownloader.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.HashMap;
import java.util.Map;

@Data
@RConfig(configName = "azure.yml")
public class AzureConfig {
    @Instance
    public static AzureConfig config = new AzureConfig();
    String clientId = "clientId";
    String clientSecret = "clientSecret";
    String tenantId = "tenantId";
    String userId = "userId";

    String fname = "r.%x%.%y%.mca";
    Map<String,String> worlds = new HashMap<String, String>(){{
        put("MainLand","map/MainLand/region/%s");
        put("MainLand_nether","MainLand_nether/DIM-1/region/%s");
        put("MainLand_the_end","MainLand_the_end/DIM1/region/%s");
    }};
}
