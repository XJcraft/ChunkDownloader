package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class FtpListingTest {

    @Test
    public void versionStampsAreSortedDescending() {
        List<String> stamps = FtpListing.sortStampsDescending(
                new String[]{"20261018-2000", "20261019-0000", "20261017-1600"});
        assertEquals(3, stamps.size());
        assertEquals("20261019-0000", stamps.get(0));
        assertEquals("20261018-2000", stamps.get(1));
        assertEquals("20261017-1600", stamps.get(2));
    }
}
