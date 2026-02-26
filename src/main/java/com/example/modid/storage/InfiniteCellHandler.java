package com.example.modid.storage;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;
import com.example.modid.item.ItemInfiniteCell;

/**
 * 磁盘系统接驳注册器 (Cell Handler)
 * 这里是 ME 驱动器网元大楼的“大一安检门”。
 * 它被挂入 AE 的底层系统中。每当有一个 ME 驱动器试图扫描自己几个格子里的物品是否能存东西时，
 * AE 系统就会把里面的物品扔出给全服所有的 CellHandler 说：“有人认领这玩意儿当亲儿子吗？”
 */
public class InfiniteCellHandler implements ICellHandler {

    /**
     * 安检询问 1：这个盘是不是你的组件？
     */
    @Override
    public boolean isCell(ItemStack is) {
        // 如果这玩意不为空，而且它底层的材质是我们写的属于无限盘 ItemInfiniteCell 的实例，那就大喊：它是我的。
        return is != null && is.getItem() instanceof ItemInfiniteCell;
    }

    /**
     * 配备服务：当 AE 系统确认这东西确实是个可以存资料的盘之后，请你给系统派发一个操作遥控器。
     * （这个遥控器就是我们刚才写的包含极危暴力的无限存取的 InfiniteCellInventory 对象）
     */
    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack is, ISaveProvider host,
            IStorageChannel<T> channel) {
        // 实例化我们自己写的，不讲武德不校验容量的代理者，并扔回给系统。
        // 从这一秒开始，以后 AE 任何写入操作都会打到我们写的逻辑里。

        // 确保被请求的是物品频道，否则强制退回（防御机制）
        if (channel == appeng.api.AEApi.instance().storage()
                .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class)) {
            // 类型强转，Java 泛型擦除所必须
            return (ICellInventoryHandler<T>) new InfiniteCellInventory(is, host);
        }
        return null;
    }
}
