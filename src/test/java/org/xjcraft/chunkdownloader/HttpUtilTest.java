package org.xjcraft.chunkdownloader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xjcraft.chunkdownloader.config.HttpConfig;

import static org.junit.Assert.assertEquals;

public class HttpUtilTest {

    private HttpConfig original;

    @Before
    public void setUp() {
        original = HttpConfig.config;
        HttpConfig cfg = new HttpConfig();
        cfg.setBaseUrl("https://example.com/world");
        HttpConfig.config = cfg;
    }

    @After
    public void tearDown() {
        HttpConfig.config = original;
    }

    @Test
    public void basicPath() {
        assertEquals(
                "https://example.com/world/MainLand/dimensions/minecraft/overworld/region/r.8.-6.mca",
                HttpUtil.buildUrl("MainLand/dimensions/minecraft/overworld/region/r.8.-6.mca")
        );
    }

    @Test
    public void leadingSlashInRelativePath() {
        assertEquals(
                "https://example.com/world/MainLand/dimensions/minecraft/overworld/region/r.8.-6.mca",
                HttpUtil.buildUrl("/MainLand/dimensions/minecraft/overworld/region/r.8.-6.mca")
        );
    }

    @Test
    public void trailingSlashInBaseUrl() {
        HttpConfig.config.setBaseUrl("https://example.com/world/");
        assertEquals(
                "https://example.com/world/MainLand/region/r.1.1.mca",
                HttpUtil.buildUrl("MainLand/region/r.1.1.mca")
        );
    }
}
