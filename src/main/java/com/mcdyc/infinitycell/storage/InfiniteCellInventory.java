package com.mcdyc.infinitycell.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;

/**
 * INF 阶层盘专用存取代理——无容量上限、无物品种类上限。
 *
 * <p>公共逻辑（数据加载、提取、样板方法）在 {@link AbstractAdvancedCellInventory} 中实现。
 * 本类只负责无限容量策略：注入时不做任何字节检查，所有容量方法返回安全定值（不做大数乘法）。
 *
 * @param <T> 存储通道的数据类型
 */
public class InfiniteCellInventory<T extends IAEStack<T>> extends AbstractAdvancedCellInventory<T>
{
    // 展示用常量：传给 GUI 的"总容量"，不参与任何乘法运算
    private static final long DISPLAY_BYTES = Long.MAX_VALUE / 2;

    public InfiniteCellInventory(ItemStack cellItem, ISaveProvider saveProvider, IStorageChannel<T> channel)
    {
        super(cellItem, saveProvider, channel);
    }

    // -------------------------------------------------------------------------
    //  注入逻辑：无容量拦截，直接接受全部输入
    // -------------------------------------------------------------------------

    // 单种物品的存量上限，防止 stackSize 本身溢出导致对外显示爆 long
    private static final long PER_TYPE_MAX = Long.MAX_VALUE / 2;

    @Override
    public T injectItems(T input, Actionable type, IActionSource src)
    {
        if (input == null || input.getStackSize() == 0) return null;

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        long currentCount = chanData.counts.getLong(input);
        boolean isNewType = currentCount == 0;

        // 单种物品上限拦截：避免单个种类的 stackSize 溢出 long 后对外显示乱码
        if (currentCount >= PER_TYPE_MAX) {
            return input; // 该种类已达上限，整批拒绝
        }

        long count = input.getStackSize();
        long canAdd = PER_TYPE_MAX - currentCount; // 还能追加多少
        long actualAdd = Math.min(count, canAdd);  // 实际能放入的数量

        // 1 item = 1 byte（纯 1:1 计数，绝不溢出，仅用于 totalBytes 统计）
        if (type == Actionable.MODULATE) {
            chanData.modify(input, actualAdd, actualAdd, isNewType ? 1 : 0);
            saveChanges();
        }

        if (actualAdd < count) {
            // 塞不下的部分原路返回
            T rejected = input.copy();
            rejected.setStackSize(count - actualAdd);
            return rejected;
        }
        return null; // 全部接收，无余料
    }

    // -------------------------------------------------------------------------
    //  容量信息：全部返回安全定值，不做任何大数乘法
    // -------------------------------------------------------------------------

    @Override
    public long getTotalBytes()
    {
        return DISPLAY_BYTES;
    }

    @Override
    public long getFreeBytes()
    {
        // usedBytes 远小于 DISPLAY_BYTES，此减法绝不溢出
        return DISPLAY_BYTES - getUsedBytes();
    }

    @Override
    public long getTotalItemTypes()
    {
        return DISPLAY_BYTES;
    }

    @Override
    public long getRemainingItemTypes()
    {
        return DISPLAY_BYTES; // 直接返回定值，无需减法
    }

    @Override
    public long getRemainingItemCount()
    {
        // 返回定值作为“剩余空间”，而不是根据当前已存数量扣减。
        // 如果这里返回 `DISPLAY_BYTES - stored`，那么一旦存满 DISPLAY_BYTES 个物品，
        // remaining 会变成 0，AE2 网络将拒绝再存入任何物品，这就成有限盘了。
        // 保持返回定值，真正的无底洞！
        return DISPLAY_BYTES;
    }

    @Override
    public int getUnusedItemCount()
    {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean canHoldNewItem()
    {
        return true; // 永远可以存入新种类
    }

    // -------------------------------------------------------------------------
    //  状态灯：只有绿(1)和蓝(4)，永不显示橙/红
    // -------------------------------------------------------------------------

    @Override
    public int getStatusForCell()
    {
        return data.getChannelData(channel).totalBytes == 0 ? 4 : 1;
    }
}
