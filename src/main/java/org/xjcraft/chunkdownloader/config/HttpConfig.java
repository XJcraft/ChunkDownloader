package org.xjcraft.chunkdownloader.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.HashMap;
import java.util.Map;

@Data
@RConfig(configName = "http.yml")
public class HttpConfig {
    @Instance
    public static HttpConfig config = new HttpConfig();

    String baseUrl = "https://example.com/world";

    String fname = "r.%x%.%y%.mca";
    Map<String, String> worlds = new HashMap<String, String>() {{
        put("MainLand", "MainLand/dimensions/minecraft/overworld/region/%s");
        put("MainLand_nether", "MainLand_nether/dimensions/minecraft/the_nether/region/%s");
        put("MainLand_the_end", "MainLand_the_end/dimensions/minecraft/the_end/region/%s");
    }};
}
