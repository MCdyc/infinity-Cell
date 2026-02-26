package com.example.modid.storage;

// Minecraft 原生 NBT 标签复合体，类似于 JSON 或字典，是保存和持久化所有复杂数据的基石
import net.minecraft.nbt.NBTTagCompound;
// Minecraft 原生 NBT 标签列表，用于在一个复合标签下保存一系列相同数据类型（例如多个物品的 NBT）的地方
import net.minecraft.nbt.NBTTagList;
// Minecraft 原生世界保存数据基类，用于摆脱实体和区块限制，将数据自由地挂载并随世界存档保存下来
import net.minecraft.world.storage.WorldSavedData;
// 引入 AE2 的核心数据接口，用于表示和存储 AE 网络中的单个物品堆叠 (包含物品本身、数量和复杂的 NBT 数据)
import appeng.api.storage.data.IAEItemStack;
// 引入 AE2 的列表接口，这是一种针对 ME 存储进行了特殊优化的集合，用于聚合相同类型（包括 NBT 匹配）的物品堆叠
import appeng.api.storage.data.IItemList;
// 引入 AE2 的通用 API 入口点，通过它可以获取注册表、工厂方法和其他核心服务
import appeng.api.AEApi;
import appeng.api.storage.channels.IItemStorageChannel;

/**
 * 无限存储核心数据类 (WorldSavedData)
 * 这个类的作用是将“假盘”(ItemInfiniteCell) 里的真实数据“外置化”。
 * 玩家手里拿的或者放在 ME 驱动器里的那个物品，实际上里面只有一个 UUID 字符串，它是空的！
 * 这个类才是真正长驻内存并在关服时被写入世界存档（world/data/infinite/xxx.dat）里的“大仓库”。
 */
public class InfiniteCellData extends WorldSavedData {
    // 每一个实例对应一个真实的磁盘存储文件，这个 UUID 是该盘的唯一标识
    private final String uuid;
    // 这是我们在内存中维持的物品总表。它负责对存入的同种物品自动合并数量
    private final IItemList<IAEItemStack> items;

    /**
     * 构造函数。Forge 会在需要加载或新建时，通过反射或指定工厂调用它
     * 
     * @param name 这个名称不仅是标识符，还会原封不动变成存档文件的文件名 (.dat 前面的部分)
     */
    public InfiniteCellData(String name) {
        super(name);
        // 从文件名中剔除前缀，保留纯粹的 UUID 字符串以作备用
        this.uuid = name.replace("infinite/", "");
        // 利用 AE2 核心 API，创建一个全新的空物品列表准备接待存入的物品
        // 注意：如果你使用 FastUtil 改造追求极限性能，可以将这里替换为 Object2LongOpenHashMap
        this.items = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class).createList();
    }

    /**
     * 暴露给外部（例如 InventoryHandler）获取当前内存中所有物品的接口
     * 
     * @return 返回 AE2 专属的包装列表
     */
    public IItemList<IAEItemStack> getItems() {
        return items;
    }

    /**
     * 从硬盘持久化文件 (.dat) 反序列化（提取数据）到内存
     * 系统在玩家第一次插盘或开服加载这个 UUID 对应的文件时会自动调用这里
     */
    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        // 先清空当前的内存列表，防止重复合并残留数据
        items.resetStatus();

        // 从刚才传进来的 NBT 大字典中，拿取名为 "items" 的列表分支
        // 第二个参数 10 代表 NBTTagCompound 类型 (即列表中装的都是一个一个小字典)
        NBTTagList list = nbt.getTagList("items", 10);

        // 遍历这成千上万种保存下来的物品
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);

            // 重要：让 AE2 的 API 直接把 NBT 解析回 IAEItemStack。这会恢复物品的基础信息 (ID/Damage/NBT)
            IAEItemStack stack = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)
                    .createFromNBT(tag);
            if (stack != null) {
                // 核心关键点1：传统的序列化上限通常是整型 (int 21亿)。
                // 破除数量上限：我们将自定义的长整型钥匙("CountLimitless")，强行赋予给恢复出来的对象栈
                stack.setStackSize(tag.getLong("CountLimitless"));

                // 放回我们的内存大列表里
                items.add(stack);
            }
        }
    }

    /**
     * 将内存数据序列化写入硬盘进行持久化
     * 系统定时保存（Save-All）或者我们在 Inventory 中主动调用 markDirty() 之后被引擎抓取时触发
     */
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        // 准备一个空列表，用来装即将变身成字符串的无数种物品
        NBTTagList list = new NBTTagList();

        // 遍历我们当前无限盘里装的所有种类
        for (IAEItemStack stack : items) {
            // 只保存存在的实质物品（排除各种可能由于负数溢出或抽空导致的空壳）
            if (stack != null && stack.getStackSize() > 0) {
                NBTTagCompound tag = new NBTTagCompound();
                // 让老代码自己把附魔、耐久等东西变成 NBT 写进小字典 tag 中
                stack.writeToNBT(tag);

                // 核心关键点2：由于原生的 writeToNBT 碰到超过 Integer.MAX_VALUE 就会写爆或截断为负数
                // 我们在这里另外准备一把大锁，显式地用长整型("CountLimitless") 记录这几百亿的实际数量！
                tag.setLong("CountLimitless", stack.getStackSize());

                // 把包好的某个物品塞入大军列表
                list.appendTag(tag);
            }
        }

        // 将列表大军挂载回世界要保存的主干包裹上，名称为 "items" (和读的时候相对应)
        nbt.setTag("items", list);
        return nbt;
    }
}
