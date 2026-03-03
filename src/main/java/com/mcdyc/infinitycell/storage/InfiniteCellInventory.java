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

    /**
     * 构建一个无视一切容量和类型约束的真·无限盘存取器。
     *
     * @param cellItem     表示该磁盘自身实体的物品堆栈。
     * @param saveProvider 存档世界的数据托管方，负责最终将数据落盘并刷新 NBT。
     * @param channel      此元件对应存放物品的通道媒介。
     */
    public InfiniteCellInventory(ItemStack cellItem, ISaveProvider saveProvider, IStorageChannel<T> channel)
    {
        super(cellItem, saveProvider, channel);
    }

    // -------------------------------------------------------------------------
    //  注入逻辑：无容量拦截，直接接受全部输入
    // -------------------------------------------------------------------------

    // 单种物品的存量上限，防止 stackSize 本身溢出导致对外显示爆 long
    private static final long PER_TYPE_MAX = Long.MAX_VALUE / 2;

    /**
     * 无限吞吐拦截器逻辑。
     * 由于其本身没有实际的容积上限设定，任何到达此方法的“写入请求”都默认被允许与吃掉，
     * 为了保证在客户端 UI 数据格式化时不出错（显示溢出的不可见代码），限制单一槽位的物品数在溢出界限以内。
     *
     * @param input 网络提交企图灌注到我们身上的物。
     * @param type  指明此操作为模拟还是现实入库。
     * @param src   来源追溯标的物。
     * @return 返回那些无法被系统接受的多余货物。由于我们是无限盘，往往直接返回 null 为全吃进。
     */
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

    /**
     * 获取当前对所有界面与接口层宣传的总容量字节数。
     * @return 绝不被溢出和跨度影响的一个巨大的安全数字界限常量。
     */
    @Override
    public long getTotalBytes()
    {
        return DISPLAY_BYTES;
    }

    /**
     * 获取对外展示时，可利用的余留字节空间。
     * @return 一个远超常规范畴但不会抛异常导致服务端溢出的极大值。
     */
    @Override
    public long getFreeBytes()
    {
        // usedBytes 远小于 DISPLAY_BYTES，此减法绝不溢出
        return DISPLAY_BYTES - getUsedBytes();
    }

    /**
     * 允许在此内部盘内存放多少个不同种类的 ID 项目。
     * @return 这个盘能支持全 Minecraft 里所有的种类，故返回极限宽容值。
     */
    @Override
    public long getTotalItemTypes()
    {
        return DISPLAY_BYTES;
    }

    /**
     * @return 系统中还能塞进多出多少不重样的物品空间，即无穷尽。
     */
    @Override
    public long getRemainingItemTypes()
    {
        return DISPLAY_BYTES; // 直接返回定值，无需减法
    }

    /**
     * 能够放入的实体方块/流体数目统计。如果这里做相减容易由于塞满伪容量后停止吸收产生伪有限上限。
     * @return 直接返回极限量，表明这是永远无法满载的黑洞。
     */
    @Override
    public long getRemainingItemCount()
    {
        // 返回定值作为“剩余空间”，而不是根据当前已存数量扣减。
        // 如果这里返回 `DISPLAY_BYTES - stored`，那么一旦存满 DISPLAY_BYTES 个物品，
        // remaining 会变成 0，AE2 网络将拒绝再存入任何物品，这就成有限盘了。
        // 保持返回定值，真正的无底洞！
        return DISPLAY_BYTES;
    }

    /**
     * @return 原版向下整数兼容的最高闲暇空置项目栏。
     */
    @Override
    public int getUnusedItemCount()
    {
        return Integer.MAX_VALUE;
    }

    /**
     * 预测我们是否有余量接受陌生的拜访者数据。
     * @return 我们是无限元件，照单全收：永远 true。
     */
    @Override
    public boolean canHoldNewItem()
    {
        return true; // 永远可以存入新种类
    }

    // -------------------------------------------------------------------------
    //  状态灯：只有绿(1)和蓝(4)，永不显示橙/红
    // -------------------------------------------------------------------------

    /**
     * 指示前方 ME 驱动箱的 LED 前缀面板该点亮什么颜色的信号灯。
     * @return {@code 4} = 空空如也呈天蓝色；{@code 1} = 呈健康稳定的草绿色。它永不衰竭（无橙无红）。
     */
    @Override
    public int getStatusForCell()
    {
        return data.getChannelData(channel).totalBytes == 0 ? 4 : 1;
    }
}
