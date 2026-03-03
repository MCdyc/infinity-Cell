package com.mcdyc.infinitycell.storage;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 后端数据中心 (FastUtil 版)
 * 完全抛弃 AE 自带的 IItemList 链表与慢速 HashMap，
 * 采用高性能 Object2LongOpenCustomHashMap 直接建立 `UUID` 到 `真实数据` 的映射库。
 */
public class AdvancedCellData extends WorldSavedData
{

    public static class ChannelData<T extends IAEStack<T>>
    {
        public Object2LongMap<T> counts = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
        public long totalBytes = 0;
        public long totalTypes = 0;
        public long totalItemCount = 0;

        // 增量 NBT 缓存支持：避免每次保存都把几十万个物品重新转化一遍 NBT
        private final it.unimi.dsi.fastutil.objects.Object2ObjectMap<T, NBTTagCompound> nbtCache =
                new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
        private final java.util.Set<T> dirtyItems = new java.util.HashSet<>();
        private boolean isFullDirty = true; // 初次启动时需要全量重建缓存

        /**
         * 以 O(1) 性能存取海量物品
         */
        public void modify(T stack, long deltaCount, long deltaBytes, long deltaTypes)
        {
            long currentCount = counts.getLong(stack);
            long newCount = currentCount + deltaCount;
            if (newCount <= 0) {
                counts.removeLong(stack);
                nbtCache.remove(stack); // 被抽干了，从缓存里杀掉
                dirtyItems.remove(stack);
            } else {
                counts.put(stack, newCount);
                dirtyItems.add(stack);  // 标记为脏数据，等待下一次落盘时只序列化它
            }
            this.totalItemCount += deltaCount;
            this.totalBytes += deltaBytes;
            this.totalTypes += deltaTypes;
        }

        /**
         * 增量计算当前频道的所有 NBT 列表
         */
        public NBTTagList getOrUpdateNbtList()
        {
            if (isFullDirty) {
                nbtCache.clear();
                for (Object2LongMap.Entry<T> entry : counts.object2LongEntrySet()) {
                    NBTTagCompound itemTag = new NBTTagCompound();
                    entry.getKey().writeToNBT(itemTag);
                    itemTag.setLong("CountLimitless", entry.getLongValue());
                    nbtCache.put(entry.getKey(), itemTag);
                }
                isFullDirty = false;
                dirtyItems.clear();
            } else if (!dirtyItems.isEmpty()) {
                // 仅对发生变动的这几样物品重新序列化
                for (T dirtyItem : dirtyItems) {
                    NBTTagCompound itemTag = new NBTTagCompound();
                    dirtyItem.writeToNBT(itemTag);
                    itemTag.setLong("CountLimitless", counts.getLong(dirtyItem));
                    nbtCache.put(dirtyItem, itemTag);
                }
                dirtyItems.clear();
            }

            NBTTagList itemsNbt = new NBTTagList();
            for (NBTTagCompound tag : nbtCache.values()) {
                itemsNbt.appendTag(tag);
            }
            return itemsNbt;
        }
    }

    private final Map<IStorageChannel<?>, ChannelData<?>> channels = new HashMap<>();

    public AdvancedCellData(String name)
    {
        super(name);
    }

    @SuppressWarnings("unchecked")
    public <T extends IAEStack<T>> ChannelData<T> getChannelData(IStorageChannel<T> channel)
    {
        return (ChannelData<T>) channels.computeIfAbsent(channel, c -> new ChannelData<T>());
    }

    public boolean isEmpty()
    {
        if (channels.isEmpty()) return true;
        for (ChannelData<?> data : channels.values()) {
            if (data.totalItemCount > 0) return false;
        }
        return true;
    }

    /**
     * 清除 dirty 标记，用于分离空磁盘时防止重新保存。
     * WorldSavedData.dirty 是 protected 字段，通过反射访问。
     */
    public void clearDirty()
    {
        try {
            java.lang.reflect.Field dirtyField = WorldSavedData.class.getDeclaredField("dirty");
            dirtyField.setAccessible(true);
            dirtyField.set(this, false);
        } catch (Exception e) {
            // 反射失败时静默忽略，方案三的 isEmpty() 检查仍会生效
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        if (isEmpty()) {
            return nbt;  // 返回空nbt，不创建文件内容
        }
        NBTTagList channelList = new NBTTagList();

        for (Map.Entry<IStorageChannel<?>, ChannelData<?>> entry : channels.entrySet()) {
            NBTTagCompound channelNbt = new NBTTagCompound();
            channelNbt.setString("ChannelType", entry.getKey().getClass().getName());

            ChannelData<?> data = entry.getValue();
            channelNbt.setLong("TotalBytes", data.totalBytes);
            channelNbt.setLong("TotalTypes", data.totalTypes);
            channelNbt.setLong("TotalItemCount", data.totalItemCount);

            channelNbt.setTag("Items", data.getOrUpdateNbtList());

            channelList.appendTag(channelNbt);
        }

        nbt.setTag("Channels", channelList);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        channels.clear();
        NBTTagList channelList = nbt.getTagList("Channels", 10);

        for (int i = 0; i < channelList.tagCount(); i++) {
            NBTTagCompound channelNbt = channelList.getCompoundTagAt(i);
            String channelClass = channelNbt.getString("ChannelType");

            IStorageChannel<?> foundChannel = null;
            for (IStorageChannel<?> ch : AEApi.instance().storage().storageChannels()) {
                if (ch.getClass().getName().equals(channelClass)) {
                    foundChannel = ch;
                    break;
                }
            }

            if (foundChannel != null) {
                readChannelData(foundChannel, channelNbt);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends IAEStack<T>> void readChannelData(IStorageChannel<T> channel, NBTTagCompound channelNbt)
    {
        ChannelData<T> data = getChannelData(channel);
        data.totalBytes = channelNbt.getLong("TotalBytes");
        data.totalTypes = channelNbt.getLong("TotalTypes");
        data.totalItemCount = channelNbt.getLong("TotalItemCount");

        // 读取完毕后，下达全局脏指令，让缓存和真实计数器同步
        data.isFullDirty = true;

        NBTTagList itemsNbt = channelNbt.getTagList("Items", 10);
        for (int j = 0; j < itemsNbt.tagCount(); j++) {
            NBTTagCompound itemTag = itemsNbt.getCompoundTagAt(j);
            T stack = channel.createFromNBT(itemTag);
            if (stack != null) {
                long count = itemTag.getLong("CountLimitless");
                data.counts.put(stack, count);
            }
        }
    }
}
