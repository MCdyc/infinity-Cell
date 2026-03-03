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
        public long totalBytesOverflow = 0; // 高位，记录爆了多少次 Long.MAX_VALUE
        public long totalTypes = 0;
        public long totalItemCount = 0;
        public long totalItemCountOverflow = 0; // 高位，记录爆了多少次 Long.MAX_VALUE

        // 增量 NBT 缓存支持：避免每次保存都把几十万个物品重新转化一遍 NBT
        private final it.unimi.dsi.fastutil.objects.Object2ObjectMap<T, NBTTagCompound> nbtCache =
                new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
        private final java.util.Set<T> dirtyItems = new java.util.HashSet<>();
        private boolean isFullDirty = true; // 初次启动时需要全量重建缓存

        /**
         * 以 O(1) 性能存取海量物品。
         * 处理数量的增减，自动在达到 0 余量时剔除项以保持 map 的干净。
         * 并维护双 long 作为大数寄存器累加整体数量与字节。
         *
         * @param stack      待变动数量的物品样板。
         * @param deltaCount 欲增加（此值为正）或扣除（此值为负）的真实个数/豪桶数。
         * @param deltaBytes 根据此次加减行为带来的占用字节数加减。
         * @param deltaTypes 若因此次操作让该盘内多了一个前所未有的品种则为1，归零出借则为-1，否则为0。
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

            // 模拟 int128：双 long 加减法计算总物品数
            if (deltaCount > 0) {
                if (Long.MAX_VALUE - this.totalItemCount < deltaCount) {
                    this.totalItemCountOverflow++;
                    // 进位后，原数值减去所需差额，等价于 (this.totalItemCount + deltaCount) - Long.MAX_VALUE
                    this.totalItemCount = deltaCount - (Long.MAX_VALUE - this.totalItemCount);
                } else {
                    this.totalItemCount += deltaCount;
                }
            } else if (deltaCount < 0) {
                long absDelta = -deltaCount;
                if (this.totalItemCount < absDelta) {
                    if (this.totalItemCountOverflow > 0) {
                        this.totalItemCountOverflow--;
                        // 借位后，用借来的 Long.MAX_VALUE 补足差额
                        this.totalItemCount = Long.MAX_VALUE - (absDelta - this.totalItemCount);
                    } else {
                        this.totalItemCount = 0; // 防止负向底穿
                    }
                } else {
                    this.totalItemCount += deltaCount;
                }
            }
            
            // 模拟 int128：双 long 加减法计算总字节数
            if (deltaBytes > 0) {
                if (Long.MAX_VALUE - this.totalBytes < deltaBytes) {
                    this.totalBytesOverflow++;
                    this.totalBytes = deltaBytes - (Long.MAX_VALUE - this.totalBytes);
                } else {
                    this.totalBytes += deltaBytes;
                }
            } else if (deltaBytes < 0) {
                long absDelta = -deltaBytes;
                if (this.totalBytes < absDelta) {
                    if (this.totalBytesOverflow > 0) {
                        this.totalBytesOverflow--;
                        this.totalBytes = Long.MAX_VALUE - (absDelta - this.totalBytes);
                    } else {
                        this.totalBytes = 0; // 防止负向底穿
                    }
                } else {
                    this.totalBytes += deltaBytes;
                }
            }

            this.totalTypes += deltaTypes;
        }

        /**
         * 供外部获取安全的单一 long 数值（用于显示或网络发包）。
         * 如果内部因为无限灌注累计到溢出过了，这里只显示长整型的最大数值以防前端解码成负数异常。
         * @return 受保护的物品总体积数量。
         */
        public long getDisplayItemCount() {
            return this.totalItemCountOverflow > 0 ? Long.MAX_VALUE : this.totalItemCount;
        }

        /**
         * 供外部获取安全的总字节数占用显示。
         * @return 受保护的总字节数占用。
         */
        public long getDisplayBytes() {
            return this.totalBytesOverflow > 0 ? Long.MAX_VALUE : this.totalBytes;
        }

        /**
         * 增量计算当前频道的所有 NBT 列表。
         * 采用只对本tick被打上脏标记（发生过变动）的那小撮物品做 NBT 序列化，其余的从缓存列表直出的机制，
         * 用于保障 10 万级的多品种极端存储元件落在磁盘时的瞬时性能（避免停顿）。
         *
         * @return 准备交给原生机制落盘的最终成品 NBT 集合。
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
     * 由于 Forge 不提供安全的方法来干掉一个没用的存档数据文件，
     * 当我们判定这个文件已经可以寿终正寝时，通过反射将父类的 `dirty` 置零强行断开它的求生欲保存脉络。
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
            channelNbt.setLong("TotalBytesOverflow", data.totalBytesOverflow);
            channelNbt.setLong("TotalTypes", data.totalTypes);
            channelNbt.setLong("TotalItemCount", data.totalItemCount);
            channelNbt.setLong("TotalItemCountOverflow", data.totalItemCountOverflow);

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
        data.totalBytesOverflow = channelNbt.getLong("TotalBytesOverflow");
        data.totalTypes = channelNbt.getLong("TotalTypes");
        data.totalItemCount = channelNbt.getLong("TotalItemCount");
        data.totalItemCountOverflow = channelNbt.getLong("TotalItemCountOverflow");

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
