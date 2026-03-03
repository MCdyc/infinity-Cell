package com.mcdyc.infinitycell.storage;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.items.IItemHandler;

import java.io.File;

/**
 * 自定义盘的公共抽象基类，包含两个子类共享的所有逻辑：
 * <ul>
 *   <li>UUID 数据加载 ({@link #getOrCreateData})</li>
 *   <li>物品提取 ({@link #extractItems})</li>
 *   <li>可用物品列举 ({@link #getAvailableItems})</li>
 *   <li>所有 ICellInventory / ICellInventoryHandler 样板方法</li>
 * </ul>
 *
 * <p>子类只需实现与容量相关的差异方法（{@link #injectItems}、{@link #getTotalBytes} 等）。
 *
 * @param <T> 存储通道的数据类型
 */
public abstract class AbstractAdvancedCellInventory<T extends IAEStack<T>>
        implements ICellInventory<T>, ICellInventoryHandler<T>
{
    protected final ItemStack cellItem;
    protected final ISaveProvider saveProvider;
    protected final IStorageChannel<T> channel;
    protected final AdvancedCellData data;

    protected AbstractAdvancedCellInventory(ItemStack cellItem, ISaveProvider saveProvider,
                                            IStorageChannel<T> channel)
    {
        this.cellItem = cellItem;
        this.saveProvider = saveProvider;
        this.channel = channel;
        this.data = getOrCreateData();
    }

    // -------------------------------------------------------------------------
    //  数据加载（UUID 绑定的全局持久化存储）
    // -------------------------------------------------------------------------

    private AdvancedCellData getOrCreateData()
    {
        NBTTagCompound nbt = cellItem.getTagCompound();
        if (nbt == null || !nbt.hasKey("disk_uuid")) {
            return new AdvancedCellData("empty_no_uuid");
        }

        String diskUuid = nbt.getString("disk_uuid");

        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            return new AdvancedCellData("empty_fallback");
        }

        File infiniteDir = new File(overworld.getSaveHandler().getWorldDirectory(), "data/infinite");
        if (!infiniteDir.exists()) {
            infiniteDir.mkdirs();
        }

        String dataKey = "infinite/" + diskUuid;
        AdvancedCellData storageData = (AdvancedCellData) overworld.getMapStorage()
                .getOrLoadData(AdvancedCellData.class, dataKey);

        if (storageData == null) {
            storageData = new AdvancedCellData(dataKey);
            overworld.getMapStorage().setData(dataKey, storageData);
        }

        return storageData;
    }

    // -------------------------------------------------------------------------
    //  提取逻辑（两个子类完全相同）
    // -------------------------------------------------------------------------

    @Override
    public T extractItems(T request, Actionable mode, IActionSource src)
    {
        if (request == null) return null;

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        long currentCount = chanData.counts.getLong(request);

        if (currentCount == 0) return null;

        long extractable = Math.min(currentCount, request.getStackSize());

        if (mode == Actionable.MODULATE) {
            long newSize = currentCount - extractable;
            long bytesDelta = newSize - currentCount; // 负数，归还字节
            boolean isRemoved = newSize <= 0;
            chanData.modify(request, -extractable, bytesDelta, isRemoved ? -1 : 0);
            saveChanges();
        }

        T extracted = request.copy();
        extracted.setStackSize(extractable);
        return extracted;
    }

    // -------------------------------------------------------------------------
    //  可用物品列举（两个子类完全相同）
    // -------------------------------------------------------------------------

    @Override
    public IItemList<T> getAvailableItems(IItemList<T> out)
    {
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        for (Object2LongMap.Entry<T> entry : chanData.counts.object2LongEntrySet()) {
            T copy = entry.getKey().copy();
            copy.setStackSize(entry.getLongValue());
            out.add(copy);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    //  统计查询（两个子类完全相同）
    // -------------------------------------------------------------------------

    @Override
    public long getUsedBytes()
    {
        return data.getChannelData(channel).totalBytes;
    }

    @Override
    public long getStoredItemTypes()
    {
        return data.getChannelData(channel).totalTypes;
    }

    @Override
    public long getStoredItemCount()
    {
        return data.getChannelData(channel).totalItemCount;
    }

    @Override
    public int getBytesPerType()
    {
        return 0; // 不收索引占用税
    }

    // -------------------------------------------------------------------------
    //  子类必须实现：与容量策略相关的方法
    // -------------------------------------------------------------------------

    @Override public abstract T injectItems(T input, Actionable type, IActionSource src);
    @Override public abstract long getTotalBytes();
    @Override public abstract long getFreeBytes();
    @Override public abstract long getTotalItemTypes();
    @Override public abstract long getRemainingItemTypes();
    @Override public abstract long getRemainingItemCount();
    @Override public abstract int getUnusedItemCount();
    @Override public abstract boolean canHoldNewItem();
    @Override public abstract int getStatusForCell();

    // -------------------------------------------------------------------------
    //  ICellInventoryHandler 代理
    // -------------------------------------------------------------------------

    @Override
    public ICellInventory<T> getCellInv()
    {
        return this;
    }

    // -------------------------------------------------------------------------
    //  ICellInventory 样板方法（两个子类完全相同）
    // -------------------------------------------------------------------------

    @Override
    public IStorageChannel<T> getChannel()
    {
        return channel;
    }

    @Override
    public AccessRestriction getAccess()
    {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    public boolean isPrioritized(T input)
    {
        return false;
    }

    @Override
    public boolean canAccept(T input)
    {
        return true;
    }

    @Override
    public int getPriority()
    {
        return 0;
    }

    @Override
    public int getSlot()
    {
        return 0;
    }

    @Override
    public boolean validForPass(int i)
    {
        return true;
    }

    @Override
    public ItemStack getItemStack()
    {
        return cellItem;
    }

    @Override
    public double getIdleDrain()
    {
        return 0;
    }

    @Override
    public FuzzyMode getFuzzyMode()
    {
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public IItemHandler getConfigInventory()
    {
        return null;
    }

    @Override
    public IItemHandler getUpgradesInventory()
    {
        return null;
    }

    @Override
    public boolean isPreformatted()
    {
        return false;
    }

    @Override
    public boolean isFuzzy()
    {
        return false;
    }

    @Override
    public IncludeExclude getIncludeExcludeMode()
    {
        return IncludeExclude.WHITELIST;
    }

    @Override
    public void persist()
    {
        saveChanges();
    }

    protected void saveChanges()
    {
        // 如果数据为空，清除 dirty 标记而不是设置，防止创建空文件
        if (data.isEmpty()) {
            data.clearDirty();
        } else {
            data.markDirty();
        }
        if (saveProvider != null) {
            saveProvider.saveChanges(this);
        }
    }
}
