package com.mcdyc.infinitycell.integration.jei;

import com.mcdyc.infinitycell.item.AdvancedCellItem;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeRegistryPlugin;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.item.ItemStack;

import java.util.Collections;
import java.util.List;

public class InfinityCellJEIRegistryPlugin implements IRecipeRegistryPlugin {

    @Override
    public <V> List<String> getRecipeCategoryUids(IFocus<V> focus) {
        if (focus != null && focus.getValue() instanceof ItemStack) {
            ItemStack stack = (ItemStack) focus.getValue();
            if (stack.getItem() instanceof AdvancedCellItem) {
                return Collections.singletonList(InfinityCellCategory.UID);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public <T extends IRecipeWrapper, V> List<T> getRecipeWrappers(IRecipeCategory<T> recipeCategory, IFocus<V> focus) {
        if (recipeCategory instanceof InfinityCellCategory && focus != null && focus.getValue() instanceof ItemStack) {
            ItemStack stack = (ItemStack) focus.getValue();
            if (stack.getItem() instanceof AdvancedCellItem) {
                return (List<T>) Collections.singletonList(new InfinityCellCategoryRecipe(stack));
            }
        }
        return Collections.emptyList();
    }

    @Override
    public <T extends IRecipeWrapper> List<T> getRecipeWrappers(IRecipeCategory<T> recipeCategory) {
        return Collections.emptyList();
    }
}
