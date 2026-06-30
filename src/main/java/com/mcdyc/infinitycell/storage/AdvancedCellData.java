package com.mcdyc.infinitycell.storage;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端数据中心 (FastUtil 版)
 * 用高性能 Object2LongOpenHashMap 直接建立 `物种样板 -> 数量` 的映射库。
 * 总数量 / 总字节以单 long 饱和累加维护（超过 Long.MAX 即封顶，下限 0），
 * 不再使用双 long 溢出寄存器——单盘存量逼近 9.2e18 的情形现实中不可达。
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
         * 以 O(1) 性能存取海量物品。
         * 处理数量的增减，自动在达到 0 余量时剔除项以保持 map 的干净；
         * 总数量与字节用饱和加法维护（封顶 Long.MAX，下限 0）。
         *
         * @param stack      待变动数量的物品样板。
         * @param deltaCount 欲增加（正）或扣除（负）的真实个数 / 毫桶数。
         * @param deltaBytes 本次加减带来的占用字节数变化。
         * @param deltaTypes 兼容旧签名保留，已不使用——品种数恒等于 {@code counts.size()}。
         */
        public void modify(T stack, long deltaCount, long deltaBytes, long deltaTypes)
        {
            if (stack == null || (deltaCount == 0L && deltaBytes == 0L)) {
                return;
            }

            // 数量索引：直接哈希命中，O(1)。AE 栈的 equals/hashCode 基于物种身份（不含 stackSize），键稳定，
            // 故无需再线性扫描寻找“规范键”。
            long currentCount = counts.getLong(stack); // 缺省值 0，即等同“不存在”
            long newCount = StorageChannelUtil.safeAdd(currentCount, deltaCount);
            if (newCount <= 0) {
                counts.removeLong(stack);
                nbtCache.remove(stack); // 被抽干了，从缓存里杀掉
                dirtyItems.remove(stack);
            } else if (currentCount == 0) {
                // 全新种类：存入不可变副本作键，避免外部复用同一 IAEStack 实例改动 stackSize 污染键
                T key = stack.copy();
                counts.put(key, newCount);
                dirtyItems.add(key);
            } else {
                // 已有等价键：fastutil 保留原键对象、仅更新值；脏集合同理只认等价键
                counts.put(stack, newCount);
                dirtyItems.add(stack);
            }

            // 总量 / 字节：单 long 饱和累加（safeAdd 正向封顶 Long.MAX，再用 max(0,..) 兜住负向底穿）
            this.totalItemCount = Math.max(0L, StorageChannelUtil.safeAdd(this.totalItemCount, deltaCount));
            this.totalBytes = Math.max(0L, StorageChannelUtil.safeAdd(this.totalBytes, deltaBytes));
            this.totalTypes = counts.size();
        }

        /**
         * 供外部获取安全的单一 long 物品总数（用于显示或网络发包）。
         * 数值在累加时已饱和封顶，绝不会解码成负数。
         * @return 物品总数。
         */
        public long getDisplayItemCount() {
            return this.totalItemCount;
        }

        /**
         * 供外部获取安全的总字节数占用显示（已在累加时饱和封顶）。
         * @return 总字节数占用。
         */
        public long getDisplayBytes() {
            return this.totalBytes;
        }

        /**
         * 仅取迭代到的前 N 个品种（哈希迭代顺序，非排序）。
         * 用于网络预览采样：海量品种时早停，避免全量物化 + O(n log n) 排序卡服务端主线程。
         *
         * @param max 最多采集的品种数（通常为 63）。
         * @return 已设好 stackSize 的物品栈列表，长度 ≤ max。
         */
        public List<T> collectFirstN(int max) {
            List<T> out = new ArrayList<>(Math.min(Math.max(max, 0), 64));
            if (max <= 0) return out;
            for (Object2LongMap.Entry<T> entry : counts.object2LongEntrySet()) {
                if (entry.getLongValue() <= 0L) {
                    continue;
                }
                T copy = entry.getKey().copy();
                copy.setStackSize(entry.getLongValue());
                out.add(copy);
                if (out.size() >= max) break;   // 拿够立刻收手
            }
            return out;
        }

        /**
         * @return 当前通道内真实的品种数（直接取 map 大小，永不漂移）。
         */
        public int typeCount() {
            return counts.size();
        }

        public long getStoredAmount(T stack)
        {
            return counts.getLong(stack); // O(1) 哈希命中，缺省 0
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
                    if (entry.getLongValue() <= 0L) {
                        continue;
                    }
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
                    long count = counts.getLong(dirtyItem);
                    if (count <= 0L) {
                        nbtCache.remove(dirtyItem);
                        continue;
                    }
                    NBTTagCompound itemTag = new NBTTagCompound();
                    dirtyItem.writeToNBT(itemTag);
                    itemTag.setLong("CountLimitless", count);
                    nbtCache.put(dirtyItem, itemTag);
                }
                dirtyItems.clear();
            }

            NBTTagList itemsNbt = new NBTTagList();
            for (NBTTagCompound tag : nbtCache.values()) {
                itemsNbt.appendTag(tag);
            }
            totalTypes = counts.size();
            return itemsNbt;
        }
    }

    private final Map<IStorageChannel<?>, ChannelData<?>> channels = new HashMap<>();

    public AdvancedCellData(String name)
    {
        super(name);
    }

    /**
     * 按 UUID 从主世界 MapStorage 只读加载后端数据（服务端专用）。
     * 复用 {@link com.mcdyc.infinitycell.command.CommandCleanEmptyCells} 的读取约定。
     *
     * <p>注意：刻意使用 {@code getOrLoadData} 而非 {@code getOrCreateData}，
     * 绝不调用 {@code setData}——否则每次预览都会为空盘生成幽灵 .dat 文件。
     *
     * @param diskUuid 元件的 disk_uuid。
     * @return 对应后端数据；若该 UUID 尚无文件则返回 {@code null}（调用方按空盘处理）。
     */
    public static AdvancedCellData loadByUuid(String diskUuid)
    {
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) return null;
        return (AdvancedCellData) overworld.getMapStorage()
                .getOrLoadData(AdvancedCellData.class, "infinite/" + diskUuid);
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
            if (!data.counts.isEmpty() || data.totalItemCount > 0) return false;
        }
        return true;
    }

    /**
     * @return 人类可读的内容摘要 "N types, M stored"（遍历已存在的通道，不创建空通道）。
     */
    public String summary()
    {
        long types = 0, count = 0;
        for (ChannelData<?> cd : channels.values()) {
            types += cd.typeCount();
            count = StorageChannelUtil.safeAdd(count, cd.getDisplayItemCount());
        }
        return types + " types, " + count + " stored";
    }

    /**
     * @return 第一个有内容的存储通道（用于 recover 时推断该给哪种类型的盘）；无内容则 {@code null}。
     */
    public IStorageChannel<?> firstNonEmptyChannel()
    {
        for (Map.Entry<IStorageChannel<?>, ChannelData<?>> e : channels.entrySet()) {
            if (e.getValue().typeCount() > 0) {
                return e.getKey();
            }
        }
        return null;
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
        data.totalBytes = Math.max(0L, channelNbt.getLong("TotalBytes"));
        data.totalTypes = Math.max(0L, channelNbt.getLong("TotalTypes"));
        data.totalItemCount = Math.max(0L, channelNbt.getLong("TotalItemCount"));

        // 兼容旧档：曾用双 long 溢出寄存器记录“爆过几次 Long.MAX”，
        // 现已改单 long 饱和；若旧档高位 > 0，直接折叠为饱和 Long.MAX。
        if (channelNbt.getLong("TotalItemCountOverflow") > 0L) {
            data.totalItemCount = Long.MAX_VALUE;
        }
        if (channelNbt.getLong("TotalBytesOverflow") > 0L) {
            data.totalBytes = Long.MAX_VALUE;
        }

        // 读取完毕后，下达全局脏指令，让缓存和真实计数器同步
        data.isFullDirty = true;

        NBTTagList itemsNbt = channelNbt.getTagList("Items", 10);
        for (int j = 0; j < itemsNbt.tagCount(); j++) {
            NBTTagCompound itemTag = itemsNbt.getCompoundTagAt(j);
            T stack = channel.createFromNBT(itemTag);
            if (stack != null) {
                long count = itemTag.getLong("CountLimitless");
                if (count > 0L) {
                    data.counts.put(stack, count);
                }
            }
        }
        data.totalTypes = data.counts.size();
    }
}
