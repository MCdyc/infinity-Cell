package com.mcdyc.infinitycell.mixin;

import appeng.api.implementations.items.IStorageCell;
import co.neeve.nae2.common.integration.jei.NAEJEIPlugin;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import mezz.jei.api.recipe.IFocus;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.List;

/**
 * 拦截 NAE2 (Mekanism-Energistics) 的强制 JEI 元件内容预览注入。
 * 当 NAE2 试图判断我们的高级/无限盘是否为 IStorageCell 时，强制向其返回 false，
 * 从而屏蔽掉这个无法支持（且会引发网络/算法问题的）纯客户端透视功能。
 */
@Mixin(NAEJEIPlugin.class)
public class MixinNAEJEIPlugin {

    @Redirect(
            method = {
                    "getRecipeCategoryUids(Lmezz/jei/api/recipe/IFocus;)Ljava/util/List;",
                    "getRecipeWrappers(Lmezz/jei/api/recipe/IRecipeCategory;Lmezz/jei/api/recipe/IFocus;)Ljava/util/List;"
            },
            remap = false,
            at = @At(value = "TYPE_TEST", target = "Lappeng/api/implementations/items/IStorageCell;")
    )
    private boolean interceptStorageCellCheck(Object item) {
        // 如果这个物品是我们的 Infinity Cell 盘，就对 NAE2 撒谎说“我不是存储盘”
        if (item instanceof AdvancedCellItem) {
            return false;
        }
        // 对于原版盘或其他 Mod 的盘（比如 NAE2 自己的气体盘），放行正常的判断逻辑
        return item instanceof IStorageCell;
    }
}
