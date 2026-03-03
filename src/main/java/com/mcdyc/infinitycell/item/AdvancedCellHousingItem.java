package com.mcdyc.infinitycell.item;

import net.minecraft.item.Item;

/**
 * 高级存储外壳物品。
 * 作为组装各阶层高级存储元件和无限存储元件的基础外壳配件。
 */
public class AdvancedCellHousingItem extends Item {

    /**
     * 构造一个新的高级存储外壳物品实例，并设置其基本属性（最大堆叠数、创造模式物品栏、注册名）。
     */
    public AdvancedCellHousingItem() {
        this.setMaxStackSize(64);
        this.setCreativeTab(AdvancedCellItem.CREATIVE_TAB);
        
        String registryName = "advanced_cell_housing";
        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
    }
}
