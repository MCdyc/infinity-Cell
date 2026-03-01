package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.data.IAEStack;
import co.neeve.nae2.common.integration.jei.JEICellCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Comparator;

@Mixin(JEICellCategory.class)
public class MixinJEICellCategory {

    /**
     * 修复 NAE2 的 JEI 元件预览在排序时由于使用 Math.toIntExact(b - a) 
     * 导致超大容量物品相减超过 int 上限甚至 Long.MAX_VALUE 时发生 ArithmeticException 的崩溃问题。
     */
    @Redirect(
            method = "setRecipe(Lmezz/jei/api/gui/IRecipeLayout;Lco/neeve/nae2/common/integration/jei/SingleStackRecipe;Lmezz/jei/api/ingredients/IIngredients;)V",
            remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;sort(Ljava/util/Comparator;)V")
    )
    private void fixIntegerOverflowSort(ArrayList<IAEStack<?>> instance, Comparator<? super IAEStack<?>> originalComparator) {
        // 使用 Long.compare 安全地比较由于我们无限盘带来的可能会有 Long.MAX_VALUE 级别差异的物品尺寸，
        // 而不是使用减法然后向下强转 int。
        instance.sort((a, b) -> Long.compare(b.getStackSize(), a.getStackSize()));
    }
}
