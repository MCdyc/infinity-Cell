package com.mcdyc.infinitycell.mixin;

import co.neeve.nae2.common.integration.jei.NAEJEIPlugin;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * 针对 NAE2 (NotEnoughEnergistics) 中 JEI 预览插件的 Mixin 拦截类。
 * 由于 NAE2 提供的原生盘内视图预览在处理极其庞大的长整型（Long）数字时存在由于向下兼容整型引起的强制转换异常崩溃问题（Integer Overflow），
 * 这里通过 Mixin 直接硬取消并抹除所有关于无限元件的 JEI 预览配方来避免崩溃发生。
 */
@Mixin(NAEJEIPlugin.class)
public class MixinNAEJEIPlugin {

    /**
     * 拦截 NAE2 向 JEI 注册配方类别 UID 时的方法。
     * 如果当前玩家光标聚焦的是无限核心发行的元件（AdvancedCellItem），直接取消该向上的视图注册。
     *
     * @param focus JEI 当前追踪聚焦的物品、流体或气体。
     * @param cir Mixin 的方法返回值回调，用于提前返回我们设定好的空列表。
     */
    @Inject(method = "getRecipeCategoryUids", at = @At("HEAD"), cancellable = true, remap = false)
    private void interceptCategoryUids(IFocus<?> focus, CallbackInfoReturnable<List<String>> cir) {
        if (focus != null && focus.getValue() instanceof ItemStack) {
            ItemStack stack = (ItemStack) focus.getValue();
            if (stack.getItem() instanceof AdvancedCellItem) {
                cir.setReturnValue(Collections.emptyList());
            }
        }
    }

    /**
     * 拦截 NAE2 向 JEI 请求生成具体盘内物品配方层的方法。
     * 同样，只要这块肉是由于我们的自定义磁盘激起的，直接退订返回空列表。
     *
     * @param recipeCategory 关联的 JEI 配方种类。
     * @param focus          JEI 当下的焦点。
     * @param cir            用于短路执行的返回值回拨钩子。
     */
    @Inject(method = "getRecipeWrappers(Lmezz/jei/api/recipe/IRecipeCategory;Lmezz/jei/api/recipe/IFocus;)Ljava/util/List;", at = @At("HEAD"), cancellable = true, remap = false)
    private void interceptRecipeWrappers(IRecipeCategory<?> recipeCategory, IFocus<?> focus, CallbackInfoReturnable<List<?>> cir) {
        if (focus != null && focus.getValue() instanceof ItemStack) {
            ItemStack stack = (ItemStack) focus.getValue();
            if (stack.getItem() instanceof AdvancedCellItem) {
                cir.setReturnValue(Collections.emptyList());
            }
        }
    }
}
