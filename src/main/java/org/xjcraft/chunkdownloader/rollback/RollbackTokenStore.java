package org.xjcraft.chunkdownloader.rollback;

import lombok.Data;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性 token：绑定发起者、目标 region 和版本列表快照，消费即失效。
 * 时间以秒为单位由调用方传入，便于测试。
 */
public class RollbackTokenStore {

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 32;

    private final int ttlSeconds;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<String, Entry>();

    public RollbackTokenStore(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Entry create(String ownerId, String world, int regionX, int regionZ,
                        String regionFileName, List<String> stamps, long nowSeconds) {
        purgeExpired(nowSeconds);
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        Entry entry = new Entry();
        entry.setToken(sb.toString());
        entry.setOwnerId(ownerId);
        entry.setWorld(world);
        entry.setRegionX(regionX);
        entry.setRegionZ(regionZ);
        entry.setRegionFileName(regionFileName);
        entry.setStamps(new ArrayList<String>(stamps));
        entry.setExpiresAt(nowSeconds + ttlSeconds);
        tokens.put(entry.getToken(), entry);
        return entry;
    }

    /** 取用并删除。token 不存在、已过期或发起者不符时返回 null。 */
    public Entry take(String token, String ownerId, long nowSeconds, int index) {
        Entry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.getExpiresAt() < nowSeconds
                || !entry.getOwnerId().equals(ownerId)
                || index < 0
                || entry.getStamps() == null
                || index >= entry.getStamps().size()) {
            return null;
        }
        // 校验通过才消费：越界/过期/冒用都不消耗 token
        tokens.remove(token);
        return entry;
    }

    public void removeByOwner(String ownerId) {
        for (Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().getOwnerId().equals(ownerId)) {
                it.remove();
            }
        }
    }

    private void purgeExpired(long nowSeconds) {
        for (Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().getExpiresAt() < nowSeconds) {
                it.remove();
            }
        }
    }

    @Data
    public static class Entry {
        private String token;
        private String ownerId;
        private String world;
        private int regionX;
        private int regionZ;
        private String regionFileName;
        private List<String> stamps;
        private long expiresAt;

        public String stampAt(int index) {
            if (stamps == null || index < 0 || index >= stamps.size()) {
                return null;
            }
            return stamps.get(index);
        }
    }
}
