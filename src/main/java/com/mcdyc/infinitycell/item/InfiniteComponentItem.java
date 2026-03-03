package com.mcdyc.infinitycell.item;

import net.minecraft.item.Item;

/**
 * 无限存储组件核心物品类。
 * 用于取代原版存储组件，作为组装“INF无限存储盘”时的核心部件。
 */
public class InfiniteComponentItem extends Item {

    /**
     * 枚举：指示该组件属于哪种类型的通道（物品/流体/气体）。
     */
    public enum ComponentType {
        ITEM, FLUID, GAS;
    }

    public final ComponentType type;

    /**
     * 构造一个新的无限组件核心物品。
     *
     * @param type 该组件对应的物理特性方向（物品、流体或气体）。
     */
    public InfiniteComponentItem(ComponentType type) {
        this.type = type;
        this.setMaxStackSize(64);
        this.setCreativeTab(AdvancedCellItem.CREATIVE_TAB);

        String registryName = "infinite_component_" + type.name().toLowerCase();
        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
    }
}
