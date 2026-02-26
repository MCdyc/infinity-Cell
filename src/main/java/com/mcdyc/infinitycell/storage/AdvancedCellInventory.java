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
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.items.IItemHandler;

import java.io.File;
import java.util.UUID;

/**
 * 带有阶梯上限校验和高性能读写的通用盘代理器
 * 这个类是 AE2 与我们自定义存储数据引擎之间的桥梁。
 * 每次 AE2 网络想要往这块盘里存取东西时，就会实例化并调用这个类。
 *
 * @param <T> 存储通道的数据类型，例如 IAEItemStack (物品), IAEFluidStack (流体), 或气体类型。
 */
public class AdvancedCellInventory<T extends IAEStack<T>> implements ICellInventory<T>, ICellInventoryHandler<T>
{

    // 代表物理世界中玩家手里拿着的那块磁盘物品实体
    private final ItemStack cellItem;
    // 用于通知 ME 驱动器或工作台保存数据的回调接口
    private final ISaveProvider saveProvider;
    // 当前盘正在处理的 AE 存储频道 (物品、流体或气体)
    private final IStorageChannel<T> channel;
    // 我们自己写的高性能、通过 UUID 挂载在主世界文件里的后端数据模型
    private final AdvancedCellData data;
    // 磁盘的基础物品定义类，用于读取出厂的容量阶层 (Tier) / 类型 (Type)
    private final AdvancedCellItem parentItem;

    // 当前磁盘根据阶层的配置，被划定的最大总存储字节数
    private final long maxBytes;
    // 当前磁盘根据类型配置，被划定的最大允许存储的不同物品种类数
    private final int maxTypes;

    /**
     * 构建一个盘的数据存取代理实例
     */
    public AdvancedCellInventory(ItemStack cellItem, ISaveProvider saveProvider, IStorageChannel<T> channel)
    {
        this.cellItem = cellItem;
        this.saveProvider = saveProvider;
        this.channel = channel;

        // 解析它的工厂属性，来设置这块盘的先天容量与类型限制
        if (cellItem.getItem() instanceof AdvancedCellItem) {
            this.parentItem = (AdvancedCellItem) cellItem.getItem();

            // 所有阶层都不再限制物品种类（统一为无限种）
            this.maxTypes = Integer.MAX_VALUE;

            // 如果是无限盘 (INF)，给予极限容量
            if (this.parentItem.tier == AdvancedCellItem.StorageTier.INF) {
                this.maxBytes = Long.MAX_VALUE / 2; // 给一半防止在某些跨区计算溢出变为负数
            } else {
                // 原版的 1K/4K/16K 等阶梯，换算成真实的字节数 (1K = 1024 bytes)
                this.maxBytes = this.parentItem.tier.kb * 1024L;
            }
        } else {
            // 安全回退预设 (如果被非常规手段唤醒)
            this.parentItem = null;
            this.maxBytes = Long.MAX_VALUE / 2;
            this.maxTypes = Integer.MAX_VALUE;
        }

        // 加载或创建后端 O(1) 数据源
        this.data = getOrCreateData();
    }

    /**
     * 获取或创建跨区块全局 UUID 绑定数据记录表
     */
    private AdvancedCellData getOrCreateData()
    {
        NBTTagCompound nbt = cellItem.getTagCompound();
        if (nbt == null || !nbt.hasKey("disk_uuid")) {
            // UUID 尚未分配（物品还没进入玩家背包），返回空数据
            // UUID 将在 AdvancedCellItem.onUpdate() 中懒加载分配
            return new AdvancedCellData("empty_no_uuid");
        }

        String diskUuid = nbt.getString("disk_uuid");

        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            // 客户端连接远程服务器时主世界可能不可用，返回空数据避免崩溃
            return new AdvancedCellData("empty_fallback");
        }

        // 把文件重定向到 data/infinite/
        File infiniteDir = new File(overworld.getSaveHandler().getWorldDirectory(), "data/infinite");
        if (!infiniteDir.exists()) {
            infiniteDir.mkdirs();
        }

        String dataKey = "infinite/" + diskUuid;
        AdvancedCellData storageData = (AdvancedCellData) overworld.getMapStorage().getOrLoadData(AdvancedCellData.class, dataKey);

        if (storageData == null) {
            storageData = new AdvancedCellData(dataKey);
            overworld.getMapStorage().setData(dataKey, storageData);
            storageData.markDirty();
        }

        return storageData;
    }

    /**
     * 当外部（例如 ME 网络其他接口、输入总线）尝试**放东西进去**时被调用
     * 必须在这里拦截容量已满、种类超限的情况，并返回塞不下的那些物品（余料）。
     *
     * @param input 想往里塞的源物品实体及数量
     * @param type  MODULATE(正式放)，SIMULATE(假装放，模拟看看能不能放下，不改变原数据)
     * @return 返回“无法放下的剩余部分”，如果全吃下了则返回 null
     */
    @Override
    public T injectItems(T input, Actionable type, IActionSource src)
    {
        // 防止空气或 0 数量乱入
        if (input == null || input.getStackSize() == 0) return null;

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        long currentCount = chanData.counts.getLong(input);
        // 如果底层 Hash 字典里存量等于 0，说明这是一件新种类的物品
        boolean isNewType = currentCount == 0;

        // 【限制计算 1】：种类上限墙拦截
        if (isNewType && chanData.totalTypes >= maxTypes) {
            return input; // 种类已经满载了，新东西一点都进不去，全部原路退回
        }

        long count = input.getStackSize(); // 想要放进来的总数量
        long unPerByte = getUnPerByte();   // 物料到字节的基础换算率 (比如物品1:1，液体1000mB:1Byte)

        long oldStackSize = currentCount;
        long newStackSize = oldStackSize + count;

        // 计算体积膨胀差值。巧妙使用 (size + base - 1) / base 来实现严格向上取整，
        // 防止放入 1mB 流体算作 0Byte 而白嫖服务器内存的问题。
        long oldBytes = (oldStackSize + unPerByte - 1) / unPerByte;
        long newBytes = (newStackSize + unPerByte - 1) / unPerByte;
        long bytesDelta = newBytes - oldBytes;

        long freeBytes = maxBytes - chanData.totalBytes;

        // 【限制计算 2】：字节容量上限墙拦截 (非无限盘专享)
        // 也就是这次放进去的新体积，大过了当前盘剩余的安全空隙
        if (bytesDelta > freeBytes) {
            // 我们最多只能添加多少“免费字节”给这次行为？
            long bytesWeCanAdd = freeBytes < 0 ? 0 : freeBytes;
            long maxBytesForThisType = oldBytes + bytesWeCanAdd;
            long maxStackSizeForThisType = maxBytesForThisType * unPerByte;
            // 倒推这种物品到底还能往里硬塞多少个(数量)
            long countWeCanAdd = maxStackSizeForThisType - oldStackSize;

            if (countWeCanAdd <= 0) {
                return input; // 这个物种目前塞满了极限，1个都不进不去，全滚蛋
            }

            // 如果是真实放入行为，就把计算出来能塞下的这些硬吃掉
            if (type == Actionable.MODULATE) {
                long actNewBytes = ((oldStackSize + countWeCanAdd) + unPerByte - 1) / unPerByte;
                long actBytesDelta = actNewBytes - oldBytes; // 注意更新实际字节差
                chanData.modify(input, countWeCanAdd, actBytesDelta, isNewType ? 1 : 0);
                saveChanges();
            }

            // 把吃不下的残渣返回出去（比如本来塞 100个，只吃下去了 30 个，返回 70 个的实体）
            T rejected = input.copy();
            rejected.setStackSize(count - countWeCanAdd);
            return rejected;
        }

        // 完美接受：当前盘容量完全富余，吃下全部请求。
        if (type == Actionable.MODULATE) {
            chanData.modify(input, count, bytesDelta, isNewType ? 1 : 0);
            saveChanges();
        }
        return null;
    }

    /**
     * 当外部网络（例如输出总线或者 ME 终端被玩家左键点击）尝试**取东西出来**时被调用
     * 必须在这里反馈你真正能给出的东西。
     *
     * @param request 你想拿什么东西，打算拿多少数量
     * @param mode    同上，MODULATE 真拿，SIMULATE 假拿看看有没有
     * @return 返回真实成功提取出来的物品和数量
     */
    @Override
    public T extractItems(T request, Actionable mode, IActionSource src)
    {
        if (request == null) return null;

        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        long currentCount = chanData.counts.getLong(request);

        // 要拿的东西，我们仓库里一种都没见过（数量=0），直接抱歉。
        if (currentCount == 0) return null;

        long count = request.getStackSize(); // 请求想要带走的数量
        long extractable = Math.min(currentCount, count); // 实际能给出的数量（库存不足时以库存为准）

        // 如果是拿真家伙
        if (mode == Actionable.MODULATE) {
            long oldStackSize = currentCount;
            long newStackSize = oldStackSize - extractable;
            long unPerByte = getUnPerByte();

            // 计算扣去的差值释放出来的字节。
            long oldBytes = (oldStackSize + unPerByte - 1) / unPerByte;
            long newBytes = newStackSize <= 0 ? 0 : (newStackSize + unPerByte - 1) / unPerByte;
            long bytesDelta = newBytes - oldBytes; // 这是负数，代表还回去变空余了

            // 如果被拿光了，种类(Types) 就得减 1
            boolean isRemoved = newStackSize <= 0;
            chanData.modify(request, -extractable, bytesDelta, isRemoved ? -1 : 0);
            saveChanges(); // 指挥数据表把这次提取入库外存
        }

        T extracted = request.copy();
        extracted.setStackSize(extractable);
        return extracted;
    }

    /**
     * 容量与物品单位计算器：
     * 原版的 AE2 流体其实是以 1000mB 等效等于 1 个物品进行换算的，
     * 但是原本直接走底层 List 很容易导致换算税金磨损（碎片化 Bug）。
     * 我们这里返回一个固定严格的除法系数。
     */
    private long getUnPerByte()
    {
        long base = channel.getUnitsPerByte();
        if (base <= 8) {
            return 1; // 传统物品栈，1 数量等于 1 耗去字节
        } else if (base > 1000) {
            return 1000; // 流体/气体，每 1000mB = 1 耗去字节
        }
        return base;
    }

    /**
     * 返回所有当前这块盘里含有的物品列表，传给 ME 终端去显示
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
    public ICellInventory<T> getCellInv()
    {
        return this;
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
    public int getBytesPerType()
    {
        // [修改黑魔法] 因为原本这块盘每次进一个新物品，系统就要扣你去 8~128 Bytes不等的 "索引占用税".
        // 但是在我们这个 Mod 里面，这玩意我们不想去算了，所以直接让其占用 0B。
        return 0;
    }

    @Override
    public boolean canHoldNewItem()
    {
        // 判断当前的可用通道，是否还没抵达上限
        AdvancedCellData.ChannelData<T> chanData = data.getChannelData(channel);
        return chanData.totalTypes < maxTypes && chanData.totalBytes < maxBytes;
    }

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
    public long getUsedBytes()
    {
        return data.getChannelData(channel).totalBytes;
    }

    @Override
    public long getTotalItemTypes()
    {
        return maxTypes;
    }

    @Override
    public long getStoredItemCount()
    {
        return data.getChannelData(channel).totalItemCount;
    }

    @Override
    public long getStoredItemTypes()
    {
        return data.getChannelData(channel).totalTypes;
    }

    @Override
    public long getRemainingItemTypes()
    {
        return Math.max(0, getTotalItemTypes() - getStoredItemTypes());
    }

    /**
     * 盘还能装多少物体的总量？
     * 在面板上通常以粗细条形式展示。
     */
    @Override
    public long getRemainingItemCount()
    {
        // 由于我们的 Byte 容量也是和数量绑定的，不需要像原版那样去预估推测。
        return getFreeBytes() * getUnPerByte();
    }

    @Override
    public int getUnusedItemCount()
    {
        long remain = getRemainingItemCount();
        return remain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remain;
    }

    /**
     * 获取显示给玩家看、反映容量安全度的驱动器机器上的 LED 灯颜色
     * 1 - 绿色 (良好满裕)
     * 2 - 橙色 (临界告警满载边缘)
     * 3 - 红色 (溢满拒绝或者类型塞满)
     * 4 - 蓝色 (空盘)
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
            return 2; // 橙色 - 临界告警 (>=75%)
        }
        return 1; // 绿色 - 正常
    }

    @Override
    public void persist()
    {
        saveChanges();
    }

    private void saveChanges()
    {
        data.markDirty();
        if (saveProvider != null) {
            saveProvider.saveChanges(this);
        }
    }
}
