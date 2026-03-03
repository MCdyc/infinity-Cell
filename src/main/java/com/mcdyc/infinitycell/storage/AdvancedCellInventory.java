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

    /**
     * 构建一个有限约束的磁盘库存处理器。
     * 会在初始化时解析物品自身设定的阶层约束和类型（最大允许占据的字节数上限）。
     *
     * @param cellItem     表示此存储元件栈的实际物理物品。
     * @param saveProvider 管理存储状态并将数据同步至服务端持久化机制的服务提供者。
     * @param channel      此元件映射的数据通道形式（如：物品/流体/气体）。
     */
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

    /**
     * 向该有限盘注入物品的拦截与处理逻辑。
     * 会同时检查种类上限和字节占用余量（剩余空间），剩余空间不足时会发生截断退回。
     *
     * @param input 即将存入系统的对象引用样例及数量。
     * @param type  指明此操作为模拟探测（SIMULATE）或是真实写入（MODULATE）。
     * @param src   指明触发写入的源头。
     * @return 那些塞不下而被退绝返回的物品栈，如果全部消化完毕则为 null。
     */
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

    /**
     * 获取总容量字节数。
     * @return 本元件设定的容量天花板。
     */
    @Override
    public long getTotalBytes()
    {
        return maxBytes;
    }

    /**
     * 获取剩余的可用字节数。
     * @return 最大受限容量扣除已用字节数之差，确保返回不小于0的数据。
     */
    @Override
    public long getFreeBytes()
    {
        return Math.max(0, maxBytes - getUsedBytes());
    }

    /**
     * 获取最大许可的存入种类数。
     * @return 63 种（如果是传统的 1K-64K 物品盘）。在我们的系统里所有有限盘都不限制种类。
     */
    @Override
    public long getTotalItemTypes()
    {
        return maxTypes;
    }

    /**
     * 盘内仍然可容纳的新独立种类数量。
     * @return 种类容量天花板与当前已存种子的差。
     */
    @Override
    public long getRemainingItemTypes()
    {
        return Math.max(0, getTotalItemTypes() - getStoredItemTypes());
    }

    /**
     * 根据当前通道的转换密度，该盘所能塞进的具体基础元件（物品数/流体mB数）。
     * @return 估算的系统最高上限可容纳数目。
     */
    @Override
    public long getRemainingItemCount()
    {
        return getFreeBytes() * getUnPerByte();
    }

    /**
     * 原生旧版兼容接口：获取剩余空位个数（向下兼容到 int）。
     * @return 被强制降级到不超过 Integer.MAX_VALUE 的物品容量空位。
     */
    @Override
    public int getUnusedItemCount()
    {
        long remain = getRemainingItemCount();
        return remain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remain;
    }

    /**
     * 在客户端试图执行拖拽预判验证时调用的简易检查。
     * @return 若该元件不论是从种类还是从字节容纳来说，都没有被塞满，则反馈 {@code true}。
     */
    @Override
    public boolean canHoldNewItem()
    {
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        return chanData.totalTypes < maxTypes && chanData.totalBytes < maxBytes;
    }

    /**
     * 主导硬盘被插在 ME 驱动器上时外面三色指示灯的信号。
     * @return 1=纯绿（正常），2=橙色（逼近75%满载告警），3=红色（数据写满被阻截），4=淡蓝色（完全空）。
     */
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
