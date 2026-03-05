package com.mcdyc.infinitycell.integration.jei;

import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;

public class InfinityCellCategoryRecipe implements IRecipeWrapper {
    private final ItemStack cellStack;
    private List<InfinityCellCategory.ExtendedStackInfo> extendedStacks = Collections.emptyList();

    public InfinityCellCategoryRecipe(ItemStack cellStack) {
        this.cellStack = cellStack;
    }

    public ItemStack getCellStack() {
        return cellStack;
    }

    public void setExtendedStacks(List<InfinityCellCategory.ExtendedStackInfo> extendedStacks) {
        this.extendedStacks = extendedStacks;
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        ingredients.setInput(ItemStack.class, this.cellStack);
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        // Items and AE2 stack size text are rendered manually in InfinityCellCategory.drawExtras
        // using NOOP_ITEM_RENDERER to prevent JEI from overlapping our rendering.
    }

    @Override
    public List<String> getTooltipStrings(int mouseX, int mouseY) {
        // Tooltips are handled by InfinityCellCategory.getTooltipStrings directly.
        return Collections.emptyList();
    }

    @Override
    public boolean handleClick(Minecraft minecraft, int mouseX, int mouseY, int mouseButton) {
        return false;
    }
}
