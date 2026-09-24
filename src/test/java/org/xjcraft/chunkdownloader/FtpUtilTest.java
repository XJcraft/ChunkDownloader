package org.xjcraft.chunkdownloader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xjcraft.chunkdownloader.config.AzureConfig;

import static org.junit.Assert.assertEquals;

public class FtpUtilTest {

    private AzureConfig original;

    @Before
    public void setUp() {
        original = AzureConfig.config;
        AzureConfig cfg = new AzureConfig();
        cfg.setFtpHost("ftp.example.com");
        cfg.setFtpPort(21);
        cfg.setFtpUser("reader");
        cfg.setFtpPassword("pass123");
        cfg.setFtpRoot("");
        AzureConfig.config = cfg;
    }

    @After
    public void tearDown() {
        AzureConfig.config = original;
    }

    @Test
    public void basicPath() {
        assertEquals(
            "ftp://reader:pass123@ftp.example.com:21/map/MainLand/region/r.8.-6.mca",
            FtpUtil.buildUrl("map/MainLand/region/r.8.-6.mca")
        );
    }

    @Test
    public void leadingSlashInRelativePath() {
        assertEquals(
            "ftp://reader:pass123@ftp.example.com:21/map/MainLand/region/r.8.-6.mca",
            FtpUtil.buildUrl("/map/MainLand/region/r.8.-6.mca")
        );
    }

    @Test
    public void ftpRootNonEmpty() {
        AzureConfig.config.setFtpRoot("mapdata/");
        assertEquals(
            "ftp://reader:pass123@ftp.example.com:21/mapdata/map/MainLand/region/r.8.-6.mca",
            FtpUtil.buildUrl("map/MainLand/region/r.8.-6.mca")
        );
    }

    @Test
    public void specialCharsInPassword() {
        AzureConfig.config.setFtpPassword("p@ss:w/ord");
        assertEquals(
            "ftp://reader:p%40ss%3Aw%2Ford@ftp.example.com:21/map/r.1.1.mca",
            FtpUtil.buildUrl("map/r.1.1.mca")
        );
    }

    @Test
    public void nonDefaultPort() {
        AzureConfig.config.setFtpPort(2121);
        assertEquals(
            "ftp://reader:pass123@ftp.example.com:2121/map/r.1.1.mca",
            FtpUtil.buildUrl("map/r.1.1.mca")
        );
    }
}
