package com.mcdyc.infinitycell.item;

import net.minecraft.item.Item;

public class AdvancedCellHousingItem extends Item {
    public AdvancedCellHousingItem() {
        this.setMaxStackSize(64);
        this.setCreativeTab(AdvancedCellItem.CREATIVE_TAB);
        
        String registryName = "advanced_cell_housing";
        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
    }
}
