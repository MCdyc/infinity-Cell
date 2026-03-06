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

    /**
     * 抽象父类的共有构造器。
     * 自动分配加载或接管存档后端的 `AdvancedCellData` 持久化内存映射。
     *
     * @param cellItem     表示该磁盘自身实体的物品堆栈。
     * @param saveProvider 存档世界的数据托管方（如 ME 驱动方块实体）。
     * @param channel      此元件对应存放物品或流体的通道媒介。
     */
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

        // 如果是客户端，直接返回内存占位符对象，禁止读写磁盘文件
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            AdvancedCellData proxy = new AdvancedCellData(diskUuid);
            AdvancedCellData.ChannelData<T> chanData = proxy.getChannelData(channel);
            if (chanData != null) {
                chanData.totalBytes = nbt.getLong("UsedBytes");
                chanData.totalBytesOverflow = nbt.getLong("UsedBytesOverflow");
                chanData.totalTypes = nbt.getLong("StoredTypes");
                chanData.totalItemCount = nbt.getLong("StoredItemCount");
                chanData.totalItemCountOverflow = nbt.getLong("StoredItemCountOverflow");
            }
            return proxy;
        }

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

    /**
     * 提取货物的核心方法。
     * 当外部尝试从当前元件往外倒腾东西时被触发，由 FastUtil 容器提供性能保障。
     *
     * @param request 期待被取出的一批物品样板清单与数量。
     * @param mode    动作指示标签：SIMULATE 为只探测余额不真扣钱，MODULATE 为来真的扣减并同步磁盘状态。
     * @param src     事件下达的指令源动作发起人。
     * @return 如果盘里并没有想要找的此类库存或数量为零，返回 null；有货则按实际存在值返回满足其要求的分量。
     */
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
            long bytesDelta = getBytesForStoredAmount(newSize) - getBytesForStoredAmount(currentCount);
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

    /**
     * 获取此刻盘内所有有存货的实物列表清单，它是 ME 网络向终端 UI 发送清单数据包的基石。
     * 将我们独立构建的 `counts` 快速查找表里的元素挨个转换成 AE 网络认得出来的可读格式发走。
     *
     * @param out 需要装满并传出来的初始空气链表。
     * @return 装满此盘存货种类的链表句柄。
     */
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

    /**
     * @return 当前盘已被占据的总体积大小字节统计。
     */
    @Override
    public long getUsedBytes()
    {
        return data.getChannelData(channel).getDisplayBytes();
    }

    /**
     * @return 当前盘内混放着的各类不一样物品 ID 的品种数量总和。
     */
    @Override
    public long getStoredItemTypes()
    {
        return data.getChannelData(channel).totalTypes;
    }

    /**
     * @return 当前这块盘内部收容下的物料原子的绝对微观单体总数。
     */
    @Override
    public long getStoredItemCount()
    {
        return data.getChannelData(channel).getDisplayItemCount();
    }

    @Override
    public int getBytesPerType()
    {
        return 0; // 不收索引占用税
    }

    /**
     * 计算某一物种在当前通道下占用的字节数。
     * 对无限盘，这个值仅用于提取时与注入保持一致地回退统计数据。
     */
    protected long getBytesForStoredAmount(long amount)
    {
        if (amount <= 0) {
            return 0;
        }
        long unitsPerByte = Math.max(1, channel.getUnitsPerByte() / 8L);
        return (amount + unitsPerByte - 1) / unitsPerByte;
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

    /**
     * 当盘内数据有实质性流转出入时调用。
     * 打上脏标记以便主分时保存循环知道下一次得将这块内存落盘；
     * 并将少量前台信息反写至本元件 NBT 提供外网Tooltip浮窗读取支持；最后通知宿主环境变更。
     */
    protected void saveChanges()
    {
        if (data.isEmpty()) {
            data.clearDirty();
        } else {
            data.markDirty();
        }

        // 将统计数据同步到 ItemStack 的 NBT，供客户端 Tooltip 读取
        syncStatsToNBT();

        if (saveProvider != null) {
            saveProvider.saveChanges(this);
        }
    }

    /**
     * 将统计数据（usedBytes, storedTypes）同步到 ItemStack 的 NBT。
     * 客户端通过 NBT 读取这些数据来显示正确的 Tooltip，而不需要访问服务端的 MapStorage。
     */
    private void syncStatsToNBT()
    {
        NBTTagCompound nbt = cellItem.getTagCompound();
        if (nbt == null) {
            nbt = new NBTTagCompound();
            cellItem.setTagCompound(nbt);
        }

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        nbt.setLong("UsedBytes", chanData.totalBytes);
        nbt.setLong("UsedBytesOverflow", chanData.totalBytesOverflow);
        nbt.setLong("StoredTypes", chanData.totalTypes);
        nbt.setLong("StoredItemCount", chanData.totalItemCount);
        nbt.setLong("StoredItemCountOverflow", chanData.totalItemCountOverflow);
    }

}
