package com.example.modid.storage;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import com.example.modid.item.AdvancedCellItem;
import net.minecraft.item.ItemStack;

public class AdvancedCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is != null && is.getItem() instanceof AdvancedCellItem;
    }

    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack is, ISaveProvider host,
            IStorageChannel<T> channel) {

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

        return new AdvancedCellInventory<>(is, host, channel);
    }

    @Override
    public double cellIdleDrain(ItemStack is, ICellInventoryHandler handler) {
        return 0.5D; // 原版 64k 差不多也就吃耗电。我们设为固定小耗电
    }

    @Override
    public int getStatusForCell(ItemStack is, ICellInventoryHandler handler) {
        if (handler instanceof AdvancedCellInventory) {
            return ((AdvancedCellInventory<?>) handler).getStatusForCell();
        }
        return 1;
    }
}
