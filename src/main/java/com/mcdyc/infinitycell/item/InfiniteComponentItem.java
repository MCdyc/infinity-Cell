package com.mcdyc.infinitycell.item;

import net.minecraft.item.Item;

/**
 * 无限组件 - 用于合成无限存储元件
 */
public class InfiniteComponentItem extends Item {
    public enum ComponentType {
        ITEM, FLUID, GAS;
    }

    public final ComponentType type;

    public InfiniteComponentItem(ComponentType type) {
        this.type = type;
        this.setMaxStackSize(64);
        this.setCreativeTab(AdvancedCellItem.CREATIVE_TAB);

        String registryName = "infinite_component_" + type.name().toLowerCase();
        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
    }
}
