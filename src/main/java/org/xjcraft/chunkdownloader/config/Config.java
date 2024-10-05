package org.xjcraft.chunkdownloader.config;

import lombok.Data;
import org.xjcraft.annotation.Instance;
import org.xjcraft.annotation.RConfig;

import java.util.HashMap;
import java.util.Map;

@Data
@RConfig
public class Config {
    @Instance
    public static Config config = new Config();

}
