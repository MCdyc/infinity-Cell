package com.mcdyc.infinitycell.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import net.minecraft.item.ItemStack;

/**
 * 带有阶梯上限校验和高性能读写的通用盘代理器（有限容量盘专用）。
 *
 * <p>公共逻辑（数据加载、提取、样板方法）在 {@link AbstractAdvancedCellInventory} 中实现。
 * 本类只负责容量上限相关的策略：字节上限、种类上限、注入拦截。
 *
 * @param <T> 存储通道的数据类型
 */
public class AdvancedCellInventory<T extends IAEStack<T>> extends AbstractAdvancedCellInventory<T>
{
    // 根据阶层划定的最大总字节数
    private final long maxBytes;
    // 根据类型划定的最大物品种类数
    private final long maxTypes;
    // 磁盘的物品定义类，用于读取阶层 / 类型信息
    private final AdvancedCellItem parentItem;

    public AdvancedCellInventory(ItemStack cellItem, ISaveProvider saveProvider, IStorageChannel<T> channel)
    {
        super(cellItem, saveProvider, channel);

        // 解析工厂属性，设置先天容量与类型限制
        if (cellItem.getItem() instanceof AdvancedCellItem) {
            this.parentItem = (AdvancedCellItem) cellItem.getItem();
            this.maxTypes = Long.MAX_VALUE / 2;  // 所有阶层均不限种类
            // 原版 1K/4K/16K 等阶梯，换算为真实字节数
            this.maxBytes = this.parentItem.tier.kb * 1024L;
        } else {
            // 安全回退（非常规手段唤醒时）
            this.parentItem = null;
            this.maxBytes = Long.MAX_VALUE / 2;
            this.maxTypes = Long.MAX_VALUE / 2;
        }
    }

    // -------------------------------------------------------------------------
    //  注入逻辑：带容量上限拦截
    // -------------------------------------------------------------------------

    @Override
    public T injectItems(T input, Actionable type, IActionSource src)
    {
        if (input == null || input.getStackSize() == 0) return null;

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        long currentCount = chanData.counts.getLong(input);
        boolean isNewType = currentCount == 0;

        // 种类上限拦截
        if (isNewType && chanData.totalTypes >= maxTypes) {
            return input;
        }

        long count = input.getStackSize();
        long unPerByte = getUnPerByte();

        long oldBytes = (currentCount + unPerByte - 1) / unPerByte;
        long newBytes = (currentCount + count + unPerByte - 1) / unPerByte;
        long bytesDelta = newBytes - oldBytes;
        long freeBytes = maxBytes - chanData.totalBytes;

        // 字节容量上限拦截
        if (bytesDelta > freeBytes) {
            long bytesWeCanAdd = freeBytes < 0 ? 0 : freeBytes;
            long maxBytesForThisType = oldBytes + bytesWeCanAdd;
            long maxStackSizeForThisType = maxBytesForThisType * unPerByte;
            long countWeCanAdd = maxStackSizeForThisType - currentCount;

            if (countWeCanAdd <= 0) return input;

            if (type == Actionable.MODULATE) {
                long actNewBytes = ((currentCount + countWeCanAdd) + unPerByte - 1) / unPerByte;
                long actBytesDelta = actNewBytes - oldBytes;
                chanData.modify(input, countWeCanAdd, actBytesDelta, isNewType ? 1 : 0);
                saveChanges();
            }

            T rejected = input.copy();
            rejected.setStackSize(count - countWeCanAdd);
            return rejected;
        }

        if (type == Actionable.MODULATE) {
            chanData.modify(input, count, bytesDelta, isNewType ? 1 : 0);
            saveChanges();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    //  容量信息
    // -------------------------------------------------------------------------

    @Override
    public long getTotalBytes()
    {
        return maxBytes;
    }

    @Override
    public long getFreeBytes()
    {
        return Math.max(0, maxBytes - getUsedBytes());
    }

    @Override
    public long getTotalItemTypes()
    {
        return maxTypes;
    }

    @Override
    public long getRemainingItemTypes()
    {
        return Math.max(0, getTotalItemTypes() - getStoredItemTypes());
    }

    @Override
    public long getRemainingItemCount()
    {
        return getFreeBytes() * getUnPerByte();
    }

    @Override
    public int getUnusedItemCount()
    {
        long remain = getRemainingItemCount();
        return remain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remain;
    }

    @Override
    public boolean canHoldNewItem()
    {
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        return chanData.totalTypes < maxTypes && chanData.totalBytes < maxBytes;
    }

    @Override
    public int getStatusForCell()
    {
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
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
     * 多少个物品/mB 算一个 Byte（换算密度，不是容量）。
     * 普通盘按通道类型区分，无限盘走 {@link InfiniteCellInventory} 不调用此方法。
     */
    private long getUnPerByte()
    {
        return channel.getUnitsPerByte() / 8;
    }
}
