package com.mcdyc.infinitycell.integration.jei;

import com.mcdyc.infinitycell.InfinityCell;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import mezz.jei.api.IJeiHelpers;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

@JEIPlugin
public class InfinityCellJEIPlugin implements IModPlugin {
    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        IJeiHelpers helpers = registry.getJeiHelpers();
        registry.addRecipeCategories(new InfinityCellCategory(helpers));
    }

    @Override
    public void register(IModRegistry registry) {
        registry.addRecipeRegistryPlugin(new InfinityCellJEIRegistryPlugin());
        
        List<InfinityCellCategoryRecipe> recipes = new ArrayList<>();
        for (AdvancedCellItem cell : InfinityCell.ADVANCED_CELLS) {
            ItemStack stack = new ItemStack(cell);
            registry.addRecipeCatalyst(stack, InfinityCellCategory.UID);
            recipes.add(new InfinityCellCategoryRecipe(stack));
        }

        registry.addRecipes(recipes, InfinityCellCategory.UID);
    }
}
