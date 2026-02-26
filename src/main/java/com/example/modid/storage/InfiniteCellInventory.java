package com.example.modid.storage;

// AE2 操作行为枚举，表明这是一次“模拟（SIMULATE，只看能不能装）”还是“真实（MODULATE，真的装进去）”操作
import appeng.api.config.Actionable;
// AE2 事件溯源对象，用来记录这个拔插存取动作是谁、哪个机器干的（通常用来发安全日志或权限验证）
import appeng.api.networking.security.IActionSource;
// AE2 中非常核心的网络存取口接口。所有的终端/总线想存取某处的物品时，调用的就是它
import appeng.api.storage.IMEInventoryHandler;
// AE2 的进度保存回调提供者。当数据发生变动时，可以叫它立刻存档
import appeng.api.storage.ISaveProvider;
// 存储频道枚举，区分是装固体物品还是流体
import appeng.api.storage.IStorageChannel;
// AE 的物品栈包装类
import appeng.api.storage.data.IAEItemStack;
// AE 物品集合表
import appeng.api.storage.data.IItemList;
// Minecraft 物品实体
import net.minecraft.item.ItemStack;
// Minecraft NBT 数据处理
import net.minecraft.nbt.NBTTagCompound;
// Minecraft 世界维度概念
import net.minecraft.world.World;
// Forge 提供的维度管理工具，用来随时调取物理存档映射
import net.minecraftforge.common.DimensionManager;
// Java 全局唯一标识符类
import java.util.UUID;
// AE2 存取权限类型，支持只拿不进或只进不拿等
import appeng.api.config.AccessRestriction;

/**
 * 无限存储行为代理者 (InventoryHandler)
 * 如果说 ItemInfiniteCell 是一个“优盘外壳”，InfiniteCellData 是存在云端的“阿里云服务器”，
 * 那么这个类就可以看成是在 ME 驱动器里的“传输线缆 / 通信网段”。
 * AE 系统的终端在试图向无限盘塞东西或者取东西时，打交道的其实就是这个类。
 * 我们在这里彻底绕过了 AE 的所有限制（不用算 bytes 字节配额）。
 */
public class InfiniteCellInventory implements appeng.api.storage.ICellInventoryHandler<IAEItemStack> {
    // 玩家实际插在机器中，或者拿在手上的那一张“盘”物品
    private final ItemStack cellItem;
    // ME 驱动器/箱子 提供给盘的一个保存钩子，告诉它“只要你变了，就戳我一下，我就汇报网络”
    private final ISaveProvider saveProvider;
    // 上一个代码里写的终极核心云端数据！所有这块盘的物品数据读写请求最后都导给它
    private final InfiniteCellData data;

    public InfiniteCellInventory(ItemStack cellItem, ISaveProvider saveProvider) {
        this.cellItem = cellItem;
        this.saveProvider = saveProvider;
        // 在这根通讯线缆插上的一瞬间，我们就去连接对应的云端数据
        this.data = getOrCreateData();
    }

    /**
     * 自动绑定或者拉取云端硬盘数据
     */
    private InfiniteCellData getOrCreateData() {
        NBTTagCompound nbt = cellItem.getTagCompound();
        // 第一次拿到手里，完全没 NBT 时帮它生成一个
        if (nbt == null) {
            nbt = new NBTTagCompound();
            cellItem.setTagCompound(nbt);
        }

        String uuid;
        // 如果这是一张没有被存过任何东西的新黑洞片
        if (!nbt.hasKey("disk_uuid")) {
            // 给它挂载一个全球唯一的序列号，并烙印在玩家手里这块优盘的外壳 NBT 上
            uuid = UUID.randomUUID().toString();
            nbt.setString("disk_uuid", uuid);
        } else {
            // 这是一张有主的老盘，直接拿到它的序列号
            uuid = nbt.getString("disk_uuid");
        }

        // 我们钦定：所有这种极高危海量数据永远寄存在主世界 (Dimension 0)，防止玩家在末地拔盘崩溃
        World world = DimensionManager.getWorld(0);
        // 单盘独立文件的独家绝活：将数据存储在 data/infinite 文件夹下
        String dataName = "infinite/" + uuid;

        // 确保该目录存在，否则持久化写文件时会抛出 FileNotFoundException
        java.io.File dir = new java.io.File(world.getSaveHandler().getWorldDirectory(), "data/infinite");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 尝试从原生世界引擎里寻找是否有这个大块头在内存里
        InfiniteCellData savedData = (InfiniteCellData) world.getMapStorage().getOrLoadData(InfiniteCellData.class,
                dataName);
        if (savedData == null) {
            // 完全没找到记录，说明这是新盘第一次插进来，我们正式为它在硬盘里划出一块专有 `.dat` 保存位
            savedData = new InfiniteCellData(dataName);
            world.getMapStorage().setData(dataName, savedData);
        }
        return savedData;
    }

    /**
     * AE 系统请求向这个盘“存”入东西
     * 
     * @param input 要存进来的东西
     * @param type  这是模拟(SIMULATE 试探性能)还是真的注入(MODULATE)
     * @return 返回“无法存入/溢出塞不下的那部分物品”。由于我们是无限盘，永远返回 null (零退回，通吃)。
     */
    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable type, IActionSource src) {
        // 空气存个屁
        if (input == null || input.getStackSize() == 0)
            return null;

        long count = input.getStackSize();
        // 直接不啰嗦，去我们的云端数据大池子里翻，有没有跟它一模一样的同种物品堆栈存在
        IAEItemStack existing = data.getItems().findPrecise(input);

        if (type == Actionable.MODULATE) {
            // 这是一次真的提交：
            if (existing != null) {
                // 如果云端已经有同一类的破烂了，我们直接数字暴力相加！管它是不是超过 21 亿限制。
                existing.setStackSize(existing.getStackSize() + count);
            } else {
                // 如果这是一种全新的破烂，把它全套 NBT + 数量拷贝下来，扔进云端池子里作为一种新的类型
                IAEItemStack copy = input.copy();
                data.getItems().add(copy);
            }
            // 提交并且发通知，要求机器标脏防作弊
            saveChanges();
        }

        // 我们永远有空间，绝不给你退一滴滴出来
        return null;
    }

    /**
     * AE 系统请求从这个盘“取”东西
     * 
     * @param request 你想让我倒腾什么东西，量是多少？
     * @param mode    这是试探模拟，还是来真抽？
     * @return 系统实际成功被你抽出来的东西的复印件和抽到的数量
     */
    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, IActionSource src) {
        if (request == null)
            return null;

        // 先去云端池子里精确搜索，有没有你想要取的这种东西？
        IAEItemStack existing = data.getItems().findPrecise(request);
        if (existing != null) {
            // 我们手里有的量和你要提取的量，取一个最小值 (你要 100 颗，我只有 2 颗，那只能给你抽两颗)
            long extractable = Math.min(existing.getStackSize(), request.getStackSize());
            // 把这部分打印成一份复印件准备发给拔线的 AE 系统线缆
            IAEItemStack ret = existing.copy();
            ret.setStackSize(extractable);

            if (mode == Actionable.MODULATE) {
                // 如果是真的取走了，我们需要狠心把云端数据的老本给扣这对应的数量
                existing.setStackSize(existing.getStackSize() - extractable);
                // 防御性拦截，防止不知道哪里的鬼才操作弄出了负数导致后面坏档
                if (existing.getStackSize() <= 0) {
                    existing.setStackSize(0);
                }
                saveChanges();
            }
            // 将准备好数量的物品还给 AE 网络
            return ret;
        }

        // 你想要的这种东西我云端里压根没存过，退下吧
        return null;
    }

    /**
     * 打开 ME 终端面板的一瞬间，AE 系统问这个盘：“你肚子里有什么，全部报上来我给客户端渲染 UI”
     */
    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out) {
        // 无视各种算力限制，直接暴力把云端所有还有数量的物品，一个不剩地打印副件倒进收集器里
        for (IAEItemStack is : data.getItems()) {
            if (is.getStackSize() > 0) {
                out.add(is.copy());
            }
        }
        return out;
    }

    // 限定我是存固体方块/物品的。
    @Override
    public IStorageChannel<IAEItemStack> getChannel() {
        return appeng.api.AEApi.instance().storage()
                .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
    }

    // 这个盘既运行存也可以取，是完整的读写权限
    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ_WRITE;
    }

    // 是否优先接受特定的某物品？对于无敌盘来说一视同仁，不管
    @Override
    public boolean isPrioritized(IAEItemStack input) {
        return false;
    }

    // 我是否允许这种类型的物品尝试接纳？我胃口巨大，当然全都要
    @Override
    public boolean canAccept(IAEItemStack input) {
        return true;
    }

    // 默认权重是 0 级（没有单独的存取排在其他正常盘之前或之后）
    @Override
    public int getPriority() {
        return 0;
    }

    // 返回当前我属于几号槽位，我们自己没必要关心这个（外面的包装器可能会管）
    @Override
    public int getSlot() {
        return 0;
    }

    // 同上，直接通过放行
    @Override
    public boolean validForPass(int i) {
        return true;
    }

    // --- ICellInventoryHandler 新增的方法 ---
    @Override
    public appeng.api.storage.ICellInventory<IAEItemStack> getCellInv() {
        return null; // 我们是一个纯粹的自定义海量盘，不需要标准的 Cell UI 状态上报
    }

    @Override
    public boolean isPreformatted() {
        return false;
    }

    @Override
    public boolean isFuzzy() {
        return false;
    }

    @Override
    public appeng.api.config.IncludeExclude getIncludeExcludeMode() {
        return appeng.api.config.IncludeExclude.WHITELIST;
    }

    /**
     * 执行保存指令。这是一个安全栓机制。
     */
    private void saveChanges() {
        // 第一，给原生 Minecraft 引擎打个 Tag 标脏（提醒引擎记得在 SaveTick 拿刀逼它写进硬盘里的 dat）
        data.markDirty();
        if (saveProvider != null) {
            // 第二，通知上报给外层的 AE2 机柜网元，可能此时网元还要算电费耗损之类的回调
            // 自定义存储单元可以直接传 null
            saveProvider.saveChanges(null);
        }
    }
}
