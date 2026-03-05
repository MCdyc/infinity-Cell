package com.mcdyc.infinitycell.network;

import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端元件数据缓存
 * 用于存储从服务器接收的元件数据，供 JEI 等客户端功能使用
 */
@SideOnly(Side.CLIENT)
public class CellDataCache {

    private static final CellDataCache INSTANCE = new CellDataCache();

    // 使用 UUID 作为 key 的缓存
    private final Map<String, CachedCellData> cacheByUuid = new ConcurrentHashMap<>();

    // 使用 ItemStack 哈希作为 key 的临时缓存（用于没有 UUID 的物品）
    private final Map<Integer, CachedCellData> cacheByHash = new ConcurrentHashMap<>();

    // 缓存过期时间（毫秒）
    private static final long CACHE_EXPIRE_TIME = 30 * 1000; // 30秒

    // 最大缓存数量
    private static final int MAX_CACHE_SIZE = 100;

    public static CellDataCache getInstance() {
        return INSTANCE;
    }

    private CellDataCache() {
    }

    /**
     * 更新缓存
     */
    public void updateCache(PacketReturnCellData data) {
        String uuid = getUuidFromStack(data.getCellStack());
        CachedCellData cachedData = new CachedCellData(
                data.getStoredStacks(),
                data.getStoredItemCount(),
                data.getStoredItemTypes(),
                data.getUsedBytes(),
                data.getTotalBytes(),
                System.currentTimeMillis()
        );

        if (uuid != null) {
            cacheByUuid.put(uuid, cachedData);
        } else {
            cacheByHash.put(getStackHash(data.getCellStack()), cachedData);
        }

        // 清理过期缓存
        cleanExpiredCache();
    }

    /**
     * 获取缓存的元件数据
     * @param stack 元件 ItemStack
     * @return 缓存数据，如果不存在或已过期则返回 null
     */
    public CachedCellData getCachedData(ItemStack stack) {
        String uuid = getUuidFromStack(stack);
        CachedCellData data;

        if (uuid != null) {
            data = cacheByUuid.get(uuid);
        } else {
            data = cacheByHash.get(getStackHash(stack));
        }

        if (data != null && !data.isExpired()) {
            return data;
        }

        return null;
    }

    /**
     * 检查是否有缓存数据
     */
    public boolean hasCachedData(ItemStack stack) {
        return getCachedData(stack) != null;
    }

    /**
     * 请求元件数据（如果缓存不存在或已过期）
     * @param stack 元件 ItemStack
     * @param maxItems 最大物品数量
     * @return true 如果发送了请求，false 如果缓存有效
     */
    public boolean requestDataIfNeeded(ItemStack stack, int maxItems) {
        CachedCellData data = getCachedData(stack);
        if (data == null) {
            requestData(stack, maxItems);
            return true;
        }
        return false;
    }

    /**
     * 发送数据请求到服务器
     */
    public void requestData(ItemStack stack, int maxItems) {
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            PacketHandler.INSTANCE.sendToServer(new PacketRequestCellData(stack, maxItems));
        }
    }

    /**
     * 清除指定元件的缓存
     */
    public void invalidateCache(ItemStack stack) {
        String uuid = getUuidFromStack(stack);
        if (uuid != null) {
            cacheByUuid.remove(uuid);
        } else {
            cacheByHash.remove(getStackHash(stack));
        }
    }

    /**
     * 清除所有缓存
     */
    public void clearAll() {
        cacheByUuid.clear();
        cacheByHash.clear();
    }

    private String getUuidFromStack(ItemStack stack) {
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey("disk_uuid")) {
            return stack.getTagCompound().getString("disk_uuid");
        }
        return null;
    }

    private int getStackHash(ItemStack stack) {
        return Objects.hash(stack.getItem(), stack.getMetadata(),
                stack.hasTagCompound() ? stack.getTagCompound().hashCode() : 0);
    }

    private void cleanExpiredCache() {
        long now = System.currentTimeMillis();

        // 清理 UUID 缓存
        cacheByUuid.entrySet().removeIf(entry -> entry.getValue().isExpired());

        // 清理哈希缓存
        cacheByHash.entrySet().removeIf(entry -> entry.getValue().isExpired());

        // 如果缓存数量超过限制，移除最旧的
        if (cacheByUuid.size() + cacheByHash.size() > MAX_CACHE_SIZE) {
            // 简单处理：清除一半的缓存
            Iterator<String> uuidIterator = cacheByUuid.keySet().iterator();
            int toRemove = cacheByUuid.size() / 2;
            for (int i = 0; i < toRemove && uuidIterator.hasNext(); i++) {
                uuidIterator.next();
                uuidIterator.remove();
            }
        }
    }

    /**
     * 缓存的元件数据
     */
    public static class CachedCellData {
        private final List<IAEStack<?>> storedStacks;
        private final long storedItemCount;
        private final long storedItemTypes;
        private final long usedBytes;
        private final long totalBytes;
        private final long timestamp;

        public CachedCellData(List<IAEStack<?>> storedStacks, long storedItemCount,
                              long storedItemTypes, long usedBytes, long totalBytes, long timestamp) {
            this.storedStacks = storedStacks != null ? new ArrayList<>(storedStacks) : Collections.emptyList();
            this.storedItemCount = storedItemCount;
            this.storedItemTypes = storedItemTypes;
            this.usedBytes = usedBytes;
            this.totalBytes = totalBytes;
            this.timestamp = timestamp;
        }

        public List<IAEStack<?>> getStoredStacks() {
            return storedStacks;
        }

        public long getStoredItemCount() {
            return storedItemCount;
        }

        public long getStoredItemTypes() {
            return storedItemTypes;
        }

        public long getUsedBytes() {
            return usedBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getRemainingItemCount() {
            // 计算剩余容量 (totalBytes - usedBytes)，需要转换为物品数量
            // 这里简化处理
            return totalBytes - usedBytes;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }
}
