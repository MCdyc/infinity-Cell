package com.mcdyc.infinitycell.storage;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import net.minecraft.item.ItemStack;

/**
 * 高级存储元件总线处理器。
 * AE2 初始化时通过反射将其注册到 Cell 注册表中（作为拦截安检门），
 * 以此接管本模组所有“高级存储盘”和“无限存储盘”的插入、状态读取以及操作。
 */
public class AdvancedCellHandler extends appeng.core.features.registries.cell.BasicCellHandler
{
    /**
     * 判断一个物品栈是否是我们模组发行的合法存储元件。
     *
     * @param is 被检视的物品栈。
     * @return 如果是 {@link AdvancedCellItem} 则返回 {@code true}，否则 {@code false}。
     */
    @Override
    public boolean isCell(ItemStack is)
    {
        return is != null && is.getItem() instanceof AdvancedCellItem;
    }

    /**
     * 当合法元件被放入驱动器并连入网络时，通过此工厂方法派生对应的存取控制实例。
     *
     * @param is      被放入插槽的存储元件。
     * @param host    承载此元件的宿主介质（如驱动器方块、ME 接口）。
     * @param channel 试图在此元件上建立存取的数据通道类型。
     * @param <T>     泛型栈类型。
     * @return 负责掌控数据流通的内存代理对象 {@link ICellInventoryHandler}。如果不匹配拦截规则则返回 null 交给其他处理。
     */
    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack is, ISaveProvider host,
                                                                             IStorageChannel<T> channel)
    {

        if (!isCell(is)) return null;

        AdvancedCellItem cell = (AdvancedCellItem) is.getItem();

        // 使用 instanceof 精确匹配通道类型，防止类名误判
        boolean isItemChan = channel instanceof IItemStorageChannel;
        boolean isFluidChan = channel instanceof IFluidStorageChannel;
        // 气体通道既不是物品也不是流体，则认为是气体（或其他附属通道）
        boolean isGasChan = !isItemChan && !isFluidChan;

        // 防止把流体塞进物品盘（严格对应）
        if (cell.type == AdvancedCellItem.StorageType.ITEM && !isItemChan) return null;
        if (cell.type == AdvancedCellItem.StorageType.FLUID && !isFluidChan) return null;
        if (cell.type == AdvancedCellItem.StorageType.GAS && !isGasChan) return null;



        // INF 阶层使用独立的无限盘实现（无容量算术，零溢出风险）
        if (cell.tier == AdvancedCellItem.StorageTier.INF) {
            return new InfiniteCellInventory<>(is, host, channel);
        }
        return new AdvancedCellInventory<>(is, host, channel);
    }

    /**
     * 定义该硬盘处于待机静默状态时所消耗的 AE 能量。
     * 耗电与 AE2 原版保持一致：0.5 * log4(x) + 0.5 AE/t，其中 x 为 KB 数。
     * 无限元件不耗电 (0.0 AE/t)。
     *
     * @param is      正在被处理的元件物品栈。
     * @param handler 当前掌控此元件的控制器实例。
     * @return 常驻耗电率。
     */
    @Override
    public double cellIdleDrain(ItemStack is, ICellInventoryHandler handler)
    {
        if (is != null && is.getItem() instanceof AdvancedCellItem) {
            AdvancedCellItem cell = (AdvancedCellItem) is.getItem();
            if (cell.tier == AdvancedCellItem.StorageTier.INF) {
                return 0.0D;
            }
            double log4x = Math.log(cell.tier.kb) / Math.log(4.0);
            return 0.5D * log4x + 0.5D;
        }
        return 0.5D;
    }

    /**
     * 汇报当前的指示灯颜色给渲染引擎进行面板驱动灯光的刷新绘制。
     *
     * @param is      驱动器上的原件物品栈。
     * @param handler 管理着它的当前实例。
     * @return 代理到各自容器类的结果数值，用来驱动绿、橙、红、蓝状态。
     */
    @Override
    public int getStatusForCell(ItemStack is, ICellInventoryHandler handler)
    {
        // 两个子类均继承 AbstractAdvancedCellInventory，统一由基类引用调用
        if (handler instanceof AbstractAdvancedCellInventory) {
            return ((AbstractAdvancedCellInventory<?>) handler).getStatusForCell();
        }
        return 1;
    }
}
