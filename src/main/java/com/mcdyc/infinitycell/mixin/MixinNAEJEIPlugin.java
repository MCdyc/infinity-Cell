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

@Mixin(NAEJEIPlugin.class)
public class MixinNAEJEIPlugin {

    @Inject(method = "getRecipeCategoryUids", at = @At("HEAD"), cancellable = true, remap = false)
    private void interceptCategoryUids(IFocus<?> focus, CallbackInfoReturnable<List<String>> cir) {
        if (focus != null && focus.getValue() instanceof ItemStack) {
            ItemStack stack = (ItemStack) focus.getValue();
            if (stack.getItem() instanceof AdvancedCellItem) {
                cir.setReturnValue(Collections.emptyList());
            }
        }
    }

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
