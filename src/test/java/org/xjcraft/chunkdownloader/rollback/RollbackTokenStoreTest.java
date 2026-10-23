package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RollbackTokenStoreTest {

    private RollbackTokenStore newStore() {
        return new RollbackTokenStore(60);
    }

    private RollbackTokenStore.Entry issue(RollbackTokenStore store, String owner, long now) {
        return store.create(owner, "MainLand", 0, -1, "r.0.-1.mca",
                Arrays.asList("20261018-2000", "20261017-1600"), now);
    }

    @Test
    public void createdTokenCanBeTakenOnce() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        RollbackTokenStore.Entry taken =
                store.take(issued.getToken(), "uuid-1", 0, 1);
        assertEquals("20261017-1600", taken.stampAt(1));
        assertNull(store.take(issued.getToken(), "uuid-1", 0, 1));
    }

    @Test
    public void tokenRejectsOtherOwner() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        assertNull(store.take(issued.getToken(), "uuid-2", 0, 1));
        // 冒用失败不消费 token，真正的持有者仍可使用
        assertEquals("20261017-1600",
                store.take(issued.getToken(), "uuid-1", 0, 1).stampAt(1));
    }

    @Test
    public void tokenExpires() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        assertNull(store.take(issued.getToken(), "uuid-1", 61, 0));
    }

    @Test
    public void tokenStillValidAtExactTtl() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        assertEquals("20261018-2000",
                store.take(issued.getToken(), "uuid-1", 60, 0).stampAt(0));
    }

    @Test
    public void indexOutOfRangeIsRejected() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        // 越界序号不消费 token
        assertNull(store.take(issued.getToken(), "uuid-1", 0, 9));
        RollbackTokenStore.Entry taken =
                store.take(issued.getToken(), "uuid-1", 0, 0);
        assertNull(taken.stampAt(-1));
        assertNull(taken.stampAt(2));
    }
}
