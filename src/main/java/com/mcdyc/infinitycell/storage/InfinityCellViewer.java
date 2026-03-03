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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public InfinityCellViewer(final ItemStack is, final int slot) {
        super((appeng.api.storage.IMEInventoryHandler) AEApi.instance().registries().cell().getCellInventory(is, null, AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)));
        this.target = is;
        this.inventorySlot = slot;
    }

    @Override
    public int getInventorySlot() {
        return this.inventorySlot;
    }

    @Override
    public ItemStack getItemStack() {
        return this.target;
    }

    /**
     * 因为我们无限盘不耗电（不需要电池），所以不管它抽多少，我们假装一直都有足够的电，并返回原本的能量扣除需求。
     */
    @Override
    public double extractAEPower(double amt, final Actionable mode, final PowerMultiplier usePowerMultiplier) {
        return amt; 
    }

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable mode, IActionSource src) {
        final long size = input.getStackSize();
        final IAEItemStack injected = super.injectItems(input, mode, src);

        if (mode == Actionable.MODULATE && (injected == null || injected.getStackSize() != size)) {
            this.notifyListenersOfChange(Collections.singletonList(input.copy().setStackSize(input.getStackSize() - (injected == null ? 0 : injected.getStackSize()))), null);
        }

        return injected;
    }

    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, IActionSource src) {
        final IAEItemStack extractable = super.extractItems(request, mode, src);

        if (mode == Actionable.MODULATE && extractable != null) {
            this.notifyListenersOfChange(Collections.singletonList(request.copy().setStackSize(-extractable.getStackSize())), null);
        }

        return extractable;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IAEStack<T>> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
        if (channel == AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)) {
            return (IMEMonitor<T>) this;
        }
        return null; // 不支持流体/气体通道的直接手持 UI 开启，因为原生 AE2 没有相关的标准 Portable Fluid GuiBridge 
    }

    /**
     * 保存玩家在这个假便携终端里设置的“排序方式”、“显示模式”、“正/反序”，存到硬盘物品的 NBT 里。
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
