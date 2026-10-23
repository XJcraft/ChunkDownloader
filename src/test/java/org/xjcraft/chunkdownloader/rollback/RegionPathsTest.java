package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RegionPathsTest {

    @Test
    public void positiveChunkMapsToRegion() {
        assertEquals(0, RegionPaths.regionOf(31));
        assertEquals(1, RegionPaths.regionOf(32));
    }

    @Test
    public void negativeChunkFloors() {
        // chunk -1 属于 region -1；chunk -32 也属于 region -1；chunk -33 属于 region -2
        assertEquals(-1, RegionPaths.regionOf(-1));
        assertEquals(-1, RegionPaths.regionOf(-32));
        assertEquals(-2, RegionPaths.regionOf(-33));
    }

    @Test
    public void fileNameUsesRegionCoords() {
        assertEquals("r.0.-1.mca", RegionPaths.regionFileName(0, -1));
        assertEquals("r.-2.3.mca", RegionPaths.regionFileName(-2, 3));
    }

    @Test
    public void versionSuffixIsParsed() {
        assertEquals("20261018-2000",
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca.20261018-2000"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-2.mca.20261018-2000"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca.20261018-2000.partial"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca"));
    }
}
