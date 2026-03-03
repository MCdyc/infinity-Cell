package com.mcdyc.infinitycell.storage;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.implementations.guiobjects.IPortableCell;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.IConfigManager;
import appeng.container.interfaces.IInventorySlotAware;
import appeng.me.helpers.MEMonitorHandler;
import appeng.util.ConfigManager;
import appeng.util.Platform;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Collections;

/**
 * 将 Infinity Cell / Advanced Cell 伪装成 AE2 的便携式终端 (Portable Cell)。
 * 让玩家可以直接在手中右键打开原生 ME 终端网络进行存取。
 */
public class InfinityCellViewer extends MEMonitorHandler<IAEItemStack> implements IPortableCell, IInventorySlotAware {

    private final ItemStack target;
    private final int inventorySlot;

    /**
     * 构造一个新的便携式存储终端视图对象。
     * 
     * @param is    被玩家拿在手中的物品栈（通常包含存储元件）。
     * @param slot  该物品在玩家物品栏中的具体槽位索引。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public InfinityCellViewer(final ItemStack is, final int slot) {
        super((appeng.api.storage.IMEInventoryHandler) AEApi.instance().registries().cell().getCellInventory(is, null, AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)));
        this.target = is;
        this.inventorySlot = slot;
    }

    /**
     * 实现 {@link IInventorySlotAware} 接口的要求。
     * AE2 会通过这个知道元件具体在玩家的哪个物品槽里，防止拿着它做奇怪的操作被吞。
     *
     * @return 该凭证在物品栏里的槽位编号。
     */
    @Override
    public int getInventorySlot() {
        return this.inventorySlot;
    }

    /**
     * 获取打开的这个虚拟终端所绑定的真实物品。
     *
     * @return 实际被包装的物品栈。
     */
    @Override
    public ItemStack getItemStack() {
        return this.target;
    }

    /**
     * 因为我们无限盘不耗电（不需要电池），所以不管它抽多少，我们假装一直都有足够的电，并返回原本的能量扣除需求。
     * 该方法负责从该终端抽取电量，这里直接返回要求的值实现伪无限电量。
     *
     * @param amt                期望抽取的 AE 能值。
     * @param mode               操作模式（预演或执行）。
     * @param usePowerMultiplier 是否受功耗倍率影响。
     * @return 实际抽取的能量数值（我们这里返回全部要求的值）。
     */
    @Override
    public double extractAEPower(double amt, final Actionable mode, final PowerMultiplier usePowerMultiplier) {
        return amt; 
    }

    /**
     * 插入物品到终端网络中，如果是实际执行，会下发通知更新监听者（触发终端 UI 的自动刷新）。
     *
     * @param input 准备尝试存入的物品。
     * @param mode  操作模式（预演或执行）。
     * @param src   操作的动作来源，通常是玩家本身。
     * @return 由于容量不足等原因，未能成功存入从而被反弹多出来的物品栈（如果全部存入则为 null）。
     */
    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable mode, IActionSource src) {
        final long size = input.getStackSize();
        final IAEItemStack injected = super.injectItems(input, mode, src);

        if (mode == Actionable.MODULATE && (injected == null || injected.getStackSize() != size)) {
            this.notifyListenersOfChange(Collections.singletonList(input.copy().setStackSize(input.getStackSize() - (injected == null ? 0 : injected.getStackSize()))), null);
        }

        return injected;
    }

    /**
     * 从终端网络中提取出物品，如果是实际执行，同样下发通知让终端内显进行扣除刷新。
     *
     * @param request 玩家期望提取的物品请求样例。
     * @param mode    操作模式（预演或执行）。
     * @param src     操作的动作来源，通常是玩家。
     * @return 依据请求，系统中实际上成功取出来的物品栈（不一定等于请求数量，可能更少或为 null）。
     */
    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, IActionSource src) {
        final IAEItemStack extractable = super.extractItems(request, mode, src);

        if (mode == Actionable.MODULATE && extractable != null) {
            this.notifyListenersOfChange(Collections.singletonList(request.copy().setStackSize(-extractable.getStackSize())), null);
        }

        return extractable;
    }

    /**
     * 代理并返回这块显示屏内挂载的指定数据通道缓存器（监视器）。
     *
     * @param channel 数据通道类型（如：物品、流体、气体）。
     * @param <T>     泛型数据类型标志。
     * @return 该界面绑定的该通道的监控处理器；如果是不支持的通道（例如默认情况下在此的流体通道）会返回 null。
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
        if (channel == AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)) {
            return (IMEMonitor<T>) this;
        }
        return null; // 不支持流体/气体通道的直接手持 UI 开启，因为原生 AE2 没有相关的标准 Portable Fluid GuiBridge 
    }

    /**
     * 获取管理玩家各种排序、归类显示偏好的配置器。
     * 保存玩家在这个假便携终端里设置的“排序方式”、“显示模式”、“正/反序”，并存到该手中硬盘物品的 NBT 里。
     *
     * @return 便于同步选项状态的 {@link IConfigManager}。
     */
    @Override
    public IConfigManager getConfigManager() {
        final ConfigManager out = new ConfigManager((manager, settingName, newValue) ->
        {
            final NBTTagCompound data = Platform.openNbtData(InfinityCellViewer.this.target);
            manager.writeToNBT(data);
        });

        out.registerSetting(Settings.SORT_BY, SortOrder.NAME);
        out.registerSetting(Settings.VIEW_MODE, ViewItems.ALL);
        out.registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING);

        out.readFromNBT(Platform.openNbtData(this.target).copy());
        return out;
    }
}
