package com.example.modid.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 无限存储单元（物品类本身）
 * 这个类只负责“它看起像是怎样的一张磁盘”，并且在 UI 层级拼命骗过原版的检查。
 * 它的数据实体不在这里，这是外皮。
 */
public class ItemInfiniteCell extends Item {

    /**
     * 基础构造初始化：配置这块大硅片的注册名和最大堆叠数
     */
    public ItemInfiniteCell() {
        this.setRegistryName("infinite_cell");
        this.setTranslationKey("infinite_cell");
        // 最重要：所有装有数据的或者即将产生 UUID 数据的驱动盘，坚决不能被进行在玩家背包中的无脑叠加，设为 1。
        this.setMaxStackSize(1);
    }
}
