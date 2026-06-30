package com.mcdyc.infinitycell.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import net.minecraft.item.ItemStack;

/**
 * 统一存储元件代理器：一套实现覆盖有限阶层盘 (1K~16384K) 与无限盘 (INF)。
 *
 * <p>差异仅在“容量策略”，由构造期确定的 {@link #infinite} 标志分流：
 * <ul>
 *   <li><b>有限档</b>：字节上限 = {@code tier.kb*1024}，注入受字节余量约束，
 *       状态灯按占用比给绿 / 橙 / 红 / 蓝；</li>
 *   <li><b>无限档</b>：无字节上限，注入只受单类型存量上限 {@link #PER_TYPE_MAX} 约束，
 *       对外容量一律上报有界哨兵 {@link #DISPLAY_BYTES}，状态灯只有蓝 / 绿。</li>
 * </ul>
 * 所有阶层均不限制物种种类数。公共逻辑（数据加载、提取、样板方法、套娃保护）在
 * {@link AbstractAdvancedCellInventory} 中实现。
 *
 * @param <T> 存储通道的数据类型
 */
public class AdvancedCellInventory<T extends IAEStack<T>> extends AbstractAdvancedCellInventory<T>
{
    // 对外上报“无限”时统一使用的有界容量哨兵（远低于 Long.MAX，留足跨元件求和余量）；
    // 同时用作“不限种类”的种类上限上报值。
    private static final long DISPLAY_BYTES = 1L << 50;

    // 单一物种的存量上限，防止单类型 stackSize 逼近 long 上限后对外显示异常。
    private static final long PER_TYPE_MAX = Long.MAX_VALUE / 2;

    private final boolean infinite;
    private final long maxBytes;   // 有限档的字节上限；无限档不参与注入判定
    private final long maxTypes;   // “不限种类”的上报值（有界哨兵）
    private final AdvancedCellItem parentItem;

    /**
     * 构建统一库存处理器，按物品自身的阶层决定走有限还是无限策略。
     *
     * @param cellItem     实际物理存储元件栈。
     * @param saveProvider 持久化托管方（驱动器 / ME 接口等）。
     * @param channel      此元件映射的数据通道（物品 / 流体 / 气体）。
     */
    public AdvancedCellInventory(ItemStack cellItem, ISaveProvider saveProvider, IStorageChannel<T> channel)
    {
        super(cellItem, saveProvider, channel);

        if (cellItem.getItem() instanceof AdvancedCellItem) {
            this.parentItem = (AdvancedCellItem) cellItem.getItem();
            this.infinite = parentItem.tier == AdvancedCellItem.StorageTier.INF;
            this.maxTypes = DISPLAY_BYTES;                                  // 所有阶层均不限种类
            this.maxBytes = infinite ? DISPLAY_BYTES : parentItem.tier.kb * 1024L; // 原版 1K/4K... 换算字节
        } else {
            // 安全回退（非常规手段唤醒时）：按无限盘处理，照单全收
            this.parentItem = null;
            this.infinite = true;
            this.maxBytes = DISPLAY_BYTES;
            this.maxTypes = DISPLAY_BYTES;
        }
    }

    // -------------------------------------------------------------------------
    //  注入逻辑：套娃保护后按“无限 / 有限”分流
    // -------------------------------------------------------------------------

    @Override
    public T injectItems(T input, Actionable type, IActionSource src)
    {
        if (input == null || input.getStackSize() <= 0L) return null;
        if (rejectsAsNestedCell(input)) return input; // 套娃保护：拒绝把存储元件存进盘
        return infinite ? injectInfinite(input, type, src) : injectFinite(input, type, src);
    }

    /**
     * 无限档注入：无字节上限，仅按单类型存量上限拦截，其余照单全收。
     */
    private T injectInfinite(T input, Actionable type, IActionSource src)
    {
        AdvancedCellData workingData = type == Actionable.MODULATE ? getDataForMutation() : data;
        AdvancedCellData.ChannelData<T> chanData = workingData == null ? null : workingData.getChannelData(channel);
        if (type == Actionable.MODULATE && chanData == null) {
            return input;
        }

        long currentCount = chanData == null ? 0L : chanData.getStoredAmount(input);
        boolean isNewType = currentCount == 0;

        // 单种物品上限拦截：避免单个种类逼近 long 上限
        if (currentCount >= PER_TYPE_MAX) {
            return input; // 该种类已达上限，整批拒绝
        }

        long count = input.getStackSize();
        long canAdd = StorageChannelUtil.safePositiveSubtract(PER_TYPE_MAX, currentCount); // 还能追加多少
        long actualAdd = Math.min(count, canAdd);
        if (actualAdd <= 0L) {
            return input;
        }

        // 无限盘 1 stored unit = 1 byte（纯 1:1 计数，仅用于 totalBytes 统计）
        if (type == Actionable.MODULATE) {
            chanData.modify(input, actualAdd, actualAdd, isNewType ? 1 : 0);
            saveChanges();
        }

        if (actualAdd < count) {
            T rejected = input.copy();
            rejected.setStackSize(count - actualAdd);
            return rejected;
        }
        return null;
    }

    /**
     * 有限档注入：同时受单类型存量与字节余量约束，余量不足时截断退回。
     */
    private T injectFinite(T input, Actionable type, IActionSource src)
    {
        AdvancedCellData workingData = type == Actionable.MODULATE ? getDataForMutation() : data;
        AdvancedCellData.ChannelData<T> chanData = workingData == null ? null : workingData.getChannelData(channel);
        if (type == Actionable.MODULATE && chanData == null) {
            return input;
        }

        long currentCount = chanData == null ? 0L : chanData.getStoredAmount(input);
        boolean isNewType = currentCount == 0;

        // 种类上限拦截（实际不限种类，maxTypes 为有界哨兵）
        if (isNewType && chanData != null && chanData.typeCount() >= maxTypes) {
            return input;
        }

        long count = input.getStackSize();
        long unPerByte = getUnPerByte();

        long oldBytes = StorageChannelUtil.bytesForAmount(channel, currentCount);
        long usedBytes = chanData == null ? 0L : chanData.totalBytes;
        long freeBytes = StorageChannelUtil.safePositiveSubtract(maxBytes, usedBytes);
        long maxBytesForThisType = StorageChannelUtil.safeAdd(oldBytes, freeBytes);
        long maxStackSizeForThisType = StorageChannelUtil.safeMultiply(maxBytesForThisType, unPerByte);
        long countWeCanAdd = StorageChannelUtil.safePositiveSubtract(maxStackSizeForThisType, currentCount);

        long acceptedCount = Math.min(count, countWeCanAdd);

        // 字节容量上限拦截
        if (acceptedCount < count) {
            if (acceptedCount <= 0L) return input;

            if (type == Actionable.MODULATE) {
                long actNewBytes = StorageChannelUtil.bytesForAmount(channel, StorageChannelUtil.safeAdd(currentCount, acceptedCount));
                long actBytesDelta = actNewBytes - oldBytes;
                chanData.modify(input, acceptedCount, actBytesDelta, isNewType ? 1 : 0);
                saveChanges();
            }

            T rejected = input.copy();
            rejected.setStackSize(count - acceptedCount);
            return rejected;
        }

        if (type == Actionable.MODULATE) {
            long newBytes = StorageChannelUtil.bytesForAmount(channel, StorageChannelUtil.safeAdd(currentCount, acceptedCount));
            long bytesDelta = newBytes - oldBytes;
            chanData.modify(input, acceptedCount, bytesDelta, isNewType ? 1 : 0);
            saveChanges();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    //  容量信息：按“无限 / 有限”分流
    // -------------------------------------------------------------------------

    @Override
    public long getTotalBytes()
    {
        return infinite ? DISPLAY_BYTES : maxBytes;
    }

    @Override
    public long getFreeBytes()
    {
        // 无限盘永不报告负余量；有限盘 = 上限 - 已用。
        return infinite ? DISPLAY_BYTES : StorageChannelUtil.safePositiveSubtract(maxBytes, getUsedBytes());
    }

    @Override
    public long getTotalItemTypes()
    {
        return maxTypes; // 有界哨兵，所有档均“不限种类”
    }

    @Override
    public long getRemainingItemTypes()
    {
        return infinite ? DISPLAY_BYTES : Math.max(0, getTotalItemTypes() - getStoredItemTypes());
    }

    @Override
    public long getRemainingItemCount()
    {
        // 无限盘返回定值（永不满载，避免被伪满容量卡住）；有限盘按剩余字节换算。
        return infinite ? DISPLAY_BYTES : StorageChannelUtil.safeMultiply(getFreeBytes(), getUnPerByte());
    }

    @Override
    public int getUnusedItemCount()
    {
        if (infinite) return Integer.MAX_VALUE;
        long remain = getRemainingItemCount();
        return remain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remain;
    }

    @Override
    public boolean canHoldNewItem()
    {
        if (infinite) return true;
        if (data == null) return true;
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        return chanData.typeCount() < maxTypes && chanData.totalBytes < maxBytes;
    }

    /**
     * 无限盘字节按 1:1 计数（覆盖基类的按通道密度换算），保证提取时回退统计与注入一致；
     * 有限盘沿用基类的真实字节换算。
     */
    @Override
    protected long getBytesForStoredAmount(long amount)
    {
        if (infinite) {
            return Math.max(0, amount);
        }
        return super.getBytesForStoredAmount(amount);
    }

    /**
     * 驱动器三色指示灯。
     * 无限盘：4=空(蓝)、1=有货(绿)，永不橙 / 红。
     * 有限盘：1=绿、2=橙(≥75%)、3=红(满)、4=蓝(空)。
     */
    @Override
    public int getStatusForCell()
    {
        if (data == null) return 4;
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        if (infinite) {
            return chanData.totalBytes == 0 ? 4 : 1;
        }
        if (chanData.totalBytes >= maxBytes) {
            return 3; // 红色 - 已满
        } else if (chanData.totalBytes == 0) {
            return 4; // 蓝色 - 空盘
        } else if (maxBytes > 0 && (double) chanData.totalBytes / maxBytes >= 0.75) {
            return 2; // 橙色 - 临界告警 (≥75%)
        }
        return 1; // 绿色 - 正常
    }

    // -------------------------------------------------------------------------
    //  单位换算
    // -------------------------------------------------------------------------

    /**
     * 多少个物品 / mB 算一个 Byte（换算密度，不是容量），按通道类型区分。
     * 无限盘走 1:1 计数，不调用此方法。
     */
    private long getUnPerByte()
    {
        return StorageChannelUtil.unitsPerByte(channel);
    }
}
