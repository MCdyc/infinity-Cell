package com.mcdyc.infinitycell;


// FML 核心注册标头，用来宣誓这是一个 Forge 模组

import appeng.api.AEApi;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import com.mcdyc.infinitycell.storage.AdvancedCellHandler;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 模组的唯一主类 —— 在服务器/客户端启动时 FML 首先会抓取和通电激活这里
 */
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, dependencies = "required-after:appliedenergistics2;after:mixinbooter")
// 告诉 Forge：这个主类里含有需要被事件总线 (EventBus) 自动监听的方法
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class InfinityCell
{

    // 建立一个只属于我们自己的控制台日志打印播报员，这样报错的时候能清晰看到锅是出在这个名称前缀上
    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    // 利用工厂在启动期按需孵化所有 1K~INF 以及各气体流体的版本
    public static final List<AdvancedCellItem> ADVANCED_CELLS = AdvancedCellItem.createAllDisks();

    /**
     * Forge Pre-Initialization 阶段事件处理。
     * 这个阶段通常用于读取配置文件、注册方块和物品（在新的 RegistryEvent 出现前）、以及初始化日志。
     *
     * @param event FML 提供的预初始化事件对象，包含模组元数据和配置目录等信息。
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        LOGGER.info("无限 AE 模组: {} !", Tags.MOD_NAME);
    }

    /**
     * Forge Initialization 阶段事件处理。
     * 在这个阶段，大部分基础模组（如 AE2）的核心系统已经加载完毕。
     * 适合在这里进行需要依赖其他模组 API 的操作，例如注册 AE2 的自定义存储拦截器。
     *
     * @param event FML 提供的初始化事件对象。
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
        // 注册高级别泛用容量和气体拦截安检门
        appeng.api.storage.ICellRegistry cellRegistry = AEApi.instance().registries().cell();
        try {
            boolean injected = false;
            for (java.lang.reflect.Field field : cellRegistry.getClass().getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.List<appeng.api.storage.ICellHandler> handlers = (java.util.List<appeng.api.storage.ICellHandler>) field.get(cellRegistry);
                    handlers.add(0, new AdvancedCellHandler());
                    injected = true;
                    break;
                }
            }
            if (!injected) {
                cellRegistry.addCellHandler(new AdvancedCellHandler());
            }
            LOGGER.info("成功挂载了 Advanced 多阶梯硬盘存取拦截安检门 (Injected)！");
        } catch (Exception e) {
            cellRegistry.addCellHandler(new AdvancedCellHandler());
            LOGGER.error("挂载 Advanced 多阶梯硬盘存取拦截安检门 (Injected) 失败，后备方案注册！", e);
        }
    }

    /**
     * Forge 服务器启动阶段事件处理。
     * 在这里可以注册仅在服务端运行的指令（命令）。
     *
     * @param event FML 提供的服务器启动事件对象。
     */
    @Mod.EventHandler
    public void serverStarting(net.minecraftforge.fml.common.event.FMLServerStartingEvent event)
    {
        event.registerServerCommand(new com.mcdyc.infinitycell.command.CommandCleanEmptyCells());
    }

    /**
     * Forge 物品注册事件订阅方法。
     * 在此阶段，模组将其所有的自定义物品注册到游戏内统一的物品注册表中（Registry）。
     * 这里会通过工厂类生成并注册所有等级的存储元件、外壳、组件，以及专用的调试工具。
     *
     * @param event Forge 抛出的专门用于注册 {@link Item} 的事件对象。
     */
    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event)
    {
        // 当门开了的时候，我们把在文件头上用工厂跑出来的所有型号（1k, 4k... inf的物品、流体、甚至是气体）硬盘一起抛到词典里
        for (AdvancedCellItem cell : ADVANCED_CELLS) {
            event.getRegistry().register(cell);
        }
        for (com.mcdyc.infinitycell.item.AdvancedCellHousingItem housing : AdvancedCellItem.CELL_HOUSINGS) {
            event.getRegistry().register(housing);
        }
        for (com.mcdyc.infinitycell.item.InfiniteComponentItem component : AdvancedCellItem.INFINITE_COMPONENTS) {
            event.getRegistry().register(component);
        }
        event.getRegistry().register(new com.mcdyc.infinitycell.item.DebugInjectorItem());
    }

    /**
     * Forge 客户端模型注册事件订阅方法。
     * 将为已注册的物品绑定其对应的贴图、 JSON 模型。这是一个仅在客户端执行的方法。
     * 确保物品在物品栏中有正确的外观显示，而不是缺少材质的紫黑方块。
     *
     * @param event Forge 提供的模型注册事件。
     */
    @SubscribeEvent
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    public static void registerModels(net.minecraftforge.client.event.ModelRegistryEvent event)
    {
        for (AdvancedCellItem cell : ADVANCED_CELLS) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                    cell, 0,
                    new net.minecraft.client.renderer.block.model.ModelResourceLocation(cell.getRegistryName(), "inventory")
            );
        }
        for (com.mcdyc.infinitycell.item.AdvancedCellHousingItem housing : AdvancedCellItem.CELL_HOUSINGS) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                    housing, 0,
                    new net.minecraft.client.renderer.block.model.ModelResourceLocation(housing.getRegistryName(), "inventory")
            );
        }
        for (com.mcdyc.infinitycell.item.InfiniteComponentItem component : AdvancedCellItem.INFINITE_COMPONENTS) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                    component, 0,
                    new net.minecraft.client.renderer.block.model.ModelResourceLocation(component.getRegistryName(), "inventory")
            );
        }
        Item debugItem = net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValue(new net.minecraft.util.ResourceLocation("infinitycell", "debug_injector"));
        if(debugItem != null) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                debugItem, 0,
                new net.minecraft.client.renderer.block.model.ModelResourceLocation("minecraft:stick", "inventory")
            );
        }
    }

    /**
     * Forge 配方注册事件订阅方法。
     * 在此阶段，将为模组中的物品添加合成规则（例如：无序配方），
     * 将高级存储组件与外壳拼装成终极存储物品。
     *
     * @param event Forge 提供的专门用于注册 {@link IRecipe} 的事件对象。
     */
    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event)
    {
        IForgeRegistry<IRecipe> registry = event.getRegistry();
        if (AdvancedCellItem.CELL_HOUSINGS.isEmpty()) return;
        Item housing = AdvancedCellItem.CELL_HOUSINGS.get(0);

        for (AdvancedCellItem cell : ADVANCED_CELLS) {
            net.minecraft.item.ItemStack componentStack = cell.getOriginalComponent();
            if (!componentStack.isEmpty()) {
                // 生成 Shapeless 配方：外壳 + 老式组件 = 我们的高级存储盘
                ResourceLocation recipeName = new ResourceLocation(Tags.MOD_ID, cell.getRegistryName().getPath() + "_recipe");
                net.minecraftforge.oredict.ShapelessOreRecipe recipe = new net.minecraftforge.oredict.ShapelessOreRecipe(
                        recipeName,
                        new net.minecraft.item.ItemStack(cell),
                        net.minecraft.item.crafting.Ingredient.fromItem(housing),
                        net.minecraft.item.crafting.Ingredient.fromStacks(componentStack)
                );
                recipe.setRegistryName(recipeName);
                registry.register(recipe);
            }
        }
    }
}
