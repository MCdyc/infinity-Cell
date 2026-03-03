package com.mcdyc.infinitycell.storage.client;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

/**
 * 客户端专用的磁盘处理器实现。
 * 客户端只需要读取显示信息，绝对不能读取物理保存文件或参与逻辑运算！
 */
public class ClientAdvancedCellInventory<T extends IAEStack<T>> implements ICellInventory<T>, ICellInventoryHandler<T> {

    protected final ItemStack cellItem;
    protected final IStorageChannel<T> channel;

    public ClientAdvancedCellInventory(ItemStack cellItem, IStorageChannel<T> channel) {
        this.cellItem = cellItem;
        this.channel = channel;
    }

    // --- 数据提取，全部从 NBT 获取 ---

    @Override
    public long getUsedBytes() {
        NBTTagCompound nbt = cellItem.getTagCompound();
        return nbt != null ? nbt.getLong("UsedBytes") : 0;
    }

    @Override
    public long getStoredItemTypes() {
        NBTTagCompound nbt = cellItem.getTagCompound();
        return nbt != null ? nbt.getLong("StoredTypes") : 0;
    }

    @Override
    public long getStoredItemCount() {
        NBTTagCompound nbt = cellItem.getTagCompound();
        return nbt != null ? nbt.getLong("StoredItemCount") : 0;
    }

    @Override
    public int getBytesPerType() {
        return 0; 
    }

    @Override
    public long getTotalBytes() {
        AdvancedCellItem item = (AdvancedCellItem) cellItem.getItem();
        return item.tier == AdvancedCellItem.StorageTier.INF ? Long.MAX_VALUE / 2 : item.tier.kb * 1024L;
    }

    @Override
    public long getFreeBytes() {
        return getTotalBytes() - getUsedBytes();
    }

    @Override
    public long getTotalItemTypes() {
        return 63;
    }

    @Override
    public long getRemainingItemTypes() {
        return getTotalItemTypes() - getStoredItemTypes();
    }

    @Override
    public long getRemainingItemCount() {
        long stored = getStoredItemCount();
        long remaining = getTotalBytes() - stored;
        return remaining > 0 ? remaining : 0;
    }

    @Override
    public int getUnusedItemCount() {
        return (int) Math.min(Integer.MAX_VALUE, getRemainingItemCount());
    }

    @Override
    public boolean canHoldNewItem() {
        return getRemainingItemTypes() > 0 && getRemainingItemCount() > 0;
    }

    @Override
    public int getStatusForCell() {
        // UI 图标指示灯
        if (getRemainingItemTypes() == 0) return 3; // 红灯
        if (getRemainingItemTypes() == getTotalItemTypes()) return 1; // 绿灯
        return 2; // 橙灯
    }

    // --- 所有修改操作全部拦截 / NO-OP ---

    @Override
    public T injectItems(T input, Actionable type, IActionSource src) {
        return input; 
    }

    @Override
    public T extractItems(T request, Actionable mode, IActionSource src) {
        return null;
    }

    @Override
    public IItemList<T> getAvailableItems(IItemList<T> out) {
        // 客户端不需要通过这个读列表，真正的列表 AE 会发包
        return out;
    }

    // --- ICellInventory API ---
    @Override
    public ICellInventory<T> getCellInv() {
        return this;
    }

    @Override
    public IStorageChannel<T> getChannel() {
        return channel;
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ; // 客户端只读
    }

    @Override
    public boolean isPrioritized(T input) { return false; }

    @Override
    public boolean canAccept(T input) { return false; }

    @Override
    public int getPriority() { return 0; }

    @Override
    public int getSlot() { return 0; }

    @Override
    public boolean validForPass(int i) { return true; }

    @Override
    public ItemStack getItemStack() { return cellItem; }

    @Override
    public double getIdleDrain() { return 0; }

    @Override
    public FuzzyMode getFuzzyMode() { return FuzzyMode.IGNORE_ALL; }

    @Override
    public IItemHandler getConfigInventory() { return null; }

    @Override
    public IItemHandler getUpgradesInventory() { return null; }

    @Override
    public boolean isPreformatted() { return false; }

    @Override
    public boolean isFuzzy() { return false; }

    @Override
    public IncludeExclude getIncludeExcludeMode() { return IncludeExclude.WHITELIST; }

    @Override
    public void persist() { }
}
