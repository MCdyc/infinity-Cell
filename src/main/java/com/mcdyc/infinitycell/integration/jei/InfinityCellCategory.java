package com.mcdyc.infinitycell.integration.jei;

import appeng.api.AEApi;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.client.render.StackSizeRenderer;
import appeng.fluids.client.render.FluidStackSizeRenderer;
import appeng.util.Platform;
import com.mcdyc.infinitycell.InfinityCell;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import com.mcdyc.infinitycell.network.CellDataCache;
import com.mcdyc.infinitycell.utils.Log;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.Internal;
import mezz.jei.api.IJeiHelpers;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IDrawableStatic;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.gui.ITooltipCallback;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.*;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

@SideOnly(Side.CLIENT)
public class InfinityCellCategory implements IRecipeCategory<InfinityCellCategoryRecipe>
{
    public static final String UID = "infinitycell:cell_view";
    public static final int CELL_SIZE = 18;
    public static final int SLOT_SIZE = CELL_SIZE - 2;
    private static final int WIDTH = 18 * 9 + 6;
    private static final int GRID_HEIGHT = 18 * 7;
    private static final int TOTAL_HEIGHT = GRID_HEIGHT + 6 + (int) ((Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 2) * 2 * 0.85);
    private static final int MAX_ITEMS_TO_REQUEST = 63; // 7行 x 9列 = 63 个物品

    // Shared renderers to be used by RecipeWrapper
    public static final StackSizeRenderer stackSizeRenderer = new StackSizeRenderer();
    public static final FluidStackSizeRenderer fluidStackSizeRenderer = new FluidStackSizeRenderer();

    // No-op renderer: JEI won't visually render items, but slots remain interactive for R/U keybinds
    private static final mezz.jei.api.ingredients.IIngredientRenderer<ItemStack> NOOP_ITEM_RENDERER =
        new mezz.jei.api.ingredients.IIngredientRenderer<ItemStack>() {
            @Override
            public void render(Minecraft minecraft, int xPosition, int yPosition, @Nullable ItemStack ingredient) {
                // No-op: we manually render items in drawExtras
            }
            @Override
            public java.util.List<String> getTooltip(Minecraft minecraft, ItemStack ingredient, net.minecraft.client.util.ITooltipFlag tooltipFlag) {
                return ingredient.getTooltip(minecraft.player, tooltipFlag);
            }
        };

    private final IDrawable slotSprite;
    private final IDrawableStatic background;
    private final List<ExtendedStackInfo> currentUiStacks = new ArrayList<>();
    private IDrawable icon = null;
    private CellInfo cellInfo;
    private boolean isLoading = false;
    private ItemStack currentCellStack = null;
    // 缓存的数据
    private CellDataCache.CachedCellData cachedCellData;
    private IRecipeLayout currentRecipeLayout;
    private InfinityCellCategoryRecipe currentRecipeWrapper;

    public InfinityCellCategory(IJeiHelpers helpers)
    {
        mezz.jei.api.IGuiHelper guiHelper = helpers.getGuiHelper();
        this.background = guiHelper.createBlankDrawable(WIDTH, TOTAL_HEIGHT);
        this.slotSprite = guiHelper.drawableBuilder(new ResourceLocation("infinitycell", "textures/gui/slot.png"), 0, 0, 18, 18)
                .setTextureSize(18, 18).build();

        // Find 64K disk as icon
        for (AdvancedCellItem cell : InfinityCell.ADVANCED_CELLS) {
            if (cell.tier == AdvancedCellItem.StorageTier.T_64K && cell.type == AdvancedCellItem.StorageType.ITEM) {
                this.icon = guiHelper.createDrawableIngredient(new ItemStack(cell));
                break;
            }
        }
    }

    @Override
    public void setRecipe(@Nonnull IRecipeLayout recipeLayout, @Nonnull InfinityCellCategoryRecipe recipeWrapper, @Nonnull IIngredients ingredients)
    {
        this.cellInfo = null;
        this.cachedCellData = null;
        this.isLoading = false;
        this.currentUiStacks.clear();
        ItemStack stack = recipeWrapper.getCellStack();
        this.currentCellStack = stack;
        this.currentRecipeLayout = recipeLayout;
        this.currentRecipeWrapper = recipeWrapper;

        mezz.jei.api.gui.IGuiItemStackGroup guiItemStacks = recipeLayout.getItemStacks();
        guiItemStacks.addTooltipCallback((slotIndex, input, ingredient, tooltip) -> {
            if (slotIndex >= 0 && slotIndex < currentUiStacks.size()) {
                ExtendedStackInfo info = currentUiStacks.get(slotIndex);
                if (info.getExtraTooltip() != null) {
                    tooltip.addAll(info.getExtraTooltip());
                }
            }
        });

        // 首先尝试从缓存获取数据
        this.cachedCellData = CellDataCache.getInstance().getCachedData(stack);
        Log.info("InfinityCellCategory setRecipe: cachedCellData=%s", (this.cachedCellData != null));

        if (this.cachedCellData != null) {
            // 使用缓存数据
            setupRecipeFromCache(recipeLayout, recipeWrapper, this.cachedCellData);
        } else {
            // 没有缓存数据，尝试从本地获取（可能是在单人游戏中）
            this.cellInfo = getCellInfo(stack);
            if (this.cellInfo != null) {
                // 检查是否能获取到物品列表
                List<IAEStack<?>> testList = new ArrayList<>();
                for (Object s : getAvailableItems(this.cellInfo)) {
                    if (s instanceof IAEStack) {
                        testList.add((IAEStack) s);
                    }
                }
                if (!testList.isEmpty()) {
                    // 本地有数据，直接使用
                    setupRecipeFromCellInfo(recipeLayout, recipeWrapper, testList);
                } else {
                    // 本地也没有数据，请求网络数据
                    this.isLoading = true;
                    CellDataCache.getInstance().requestData(stack, MAX_ITEMS_TO_REQUEST);
                }
            } else {
                // 无法获取 CellInfo，请求网络数据
                this.isLoading = true;
                CellDataCache.getInstance().requestData(stack, MAX_ITEMS_TO_REQUEST);
            }
        }
    }

    private void updateJeiSlots(List<ExtendedStackInfo> uiStacks) {
        if (this.currentRecipeLayout == null) return;
        mezz.jei.api.gui.IGuiItemStackGroup itemStacks = this.currentRecipeLayout.getItemStacks();
        for (int i = 0; i < uiStacks.size(); i++) {
            ExtendedStackInfo info = uiStacks.get(i);
            IAEStack<?> stack = info.getStack();
            if (!(stack instanceof appeng.api.storage.data.IAEFluidStack)) {
                // Use NOOP_ITEM_RENDERER so JEI won't draw items, but R/U keybinds still work
                itemStacks.init(i, false, NOOP_ITEM_RENDERER, info.getPosX(), info.getPosY(),
                    CELL_SIZE, CELL_SIZE,
                    (18 - SLOT_SIZE) / 2,
                    (18 - SLOT_SIZE) / 2);
                itemStacks.set(i, stack.asItemStackRepresentation());
            }
        }
    }

    /**
     * 使用缓存数据设置配方显示
     */
    private void setupRecipeFromCache(IRecipeLayout recipeLayout, InfinityCellCategoryRecipe recipeWrapper,
                                          CellDataCache.CachedCellData cachedData)
    {
        List<IAEStack<?>> storedStacks = new ArrayList<>(cachedData.getStoredStacks());
        Log.info("InfinityCellCategory setupRecipeFromCache: storedStacks size=%d", storedStacks.size());

        // 按数量排序
        storedStacks.sort((a, b) -> Long.compare(b.getStackSize(), a.getStackSize()));

        int totalTypes = Math.min((int) cachedData.getStoredItemTypes(), MAX_ITEMS_TO_REQUEST);
        int gridWidth = Math.min(9, totalTypes);
        int gridStartY = TOTAL_HEIGHT - GRID_HEIGHT;
        int gridStartX = WIDTH / 2 - gridWidth * 18 / 2;

        Iterator<IAEStack<?>> iter = storedStacks.iterator();
        List<ExtendedStackInfo> uiStacks = new ArrayList<>();

        NumberFormat format = NumberFormat.getInstance();
        int transferFactor = getTransferFactorFromCache(cachedData);
        String unitName = getUnitNameFromCache(cachedData);

        for (int i = 0; i < totalTypes; i++) {
            int posX = gridStartX + (CELL_SIZE * (i % 9));
            int posY = gridStartY + (CELL_SIZE * (i / 9));

            if (iter.hasNext()) {
                IAEStack<?> aeStack = iter.next();
                
                List<String> extraTooltip = new ArrayList<>();
                long stackSize = aeStack.getStackSize();
                extraTooltip.add(I18n.format("infinitycell.jei.cellview.hover.stored", format.format(stackSize / (double) transferFactor), unitName));

                uiStacks.add(new ExtendedStackInfo(aeStack, posX, posY, extraTooltip));
            }
        }
        this.currentUiStacks.clear();
        this.currentUiStacks.addAll(uiStacks);
        Log.info("InfinityCellCategory setupRecipeFromCache: created uiStacks size=%d", this.currentUiStacks.size());

        updateJeiSlots(this.currentUiStacks);
        this.currentRecipeWrapper.setExtendedStacks(this.currentUiStacks);
    }
    /**
     * 从 CellInfo 设置配方显示
     */
    private void setupRecipeFromCellInfo(IRecipeLayout recipeLayout, InfinityCellCategoryRecipe recipeWrapper,
                                            List<IAEStack<?>> storedStacks)
    {
        storedStacks.sort((a, b) -> Long.compare(b.getStackSize(), a.getStackSize()));

        int totalTypes = (int) this.cellInfo.cellInv.getStoredItemTypes();
        int gridWidth = Math.min(9, totalTypes);
        int gridStartY = TOTAL_HEIGHT - GRID_HEIGHT;
        int gridStartX = WIDTH / 2 - gridWidth * 18 / 2;

        NumberFormat format = NumberFormat.getInstance();
        int transferFactor = this.getTransferFactor();
        String unitName = I18n.format("infinitycell.jei.cellview." + getStorageChannelUnits(this.cellInfo.channel));

        Iterator<IAEStack<?>> iter = storedStacks.iterator();
        List<ExtendedStackInfo> uiStacks = new ArrayList<>();

        for (int i = 0; i < totalTypes; i++) {
            int posX = gridStartX + (CELL_SIZE * (i % 9));
            int posY = gridStartY + (CELL_SIZE * (i / 9));

            if (iter.hasNext()) {
                IAEStack<?> aeStack = iter.next();
                List<String> extraTooltip = new ArrayList<>();
                long stackSize = aeStack.getStackSize();
                extraTooltip.add(I18n.format("infinitycell.jei.cellview.hover.stored", format.format(stackSize / (double) transferFactor), unitName));
                extraTooltip.add(I18n.format("infinitycell.jei.cellview.used", format.format(this.cellInfo.cellInv.getBytesPerType() + Math.ceil(stackSize / (double) this.cellInfo.channel.getUnitsPerByte()))));

                uiStacks.add(new ExtendedStackInfo(aeStack, posX, posY, extraTooltip));
            }
        }
        this.currentUiStacks.clear();
        this.currentUiStacks.addAll(uiStacks);
        updateJeiSlots(this.currentUiStacks);
        this.currentRecipeWrapper.setExtendedStacks(this.currentUiStacks);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IItemList<?> getAvailableItems(CellInfo cellInfo)
    {
        ICellInventory cellInv = cellInfo.cellInv;
        IStorageChannel channel = cellInfo.channel;
        return cellInv.getAvailableItems(channel.createList());
    }
    private static String getStorageChannelUnits(IStorageChannel<?> storageChannel)
    {
        if (storageChannel instanceof IItemStorageChannel) {
            return "items";
        } else if (storageChannel instanceof IFluidStorageChannel) {
            return "buckets";
        } else if (Platform.isModLoaded("mekeng") && storageChannel.getClass().getName().contains("IGasStorageChannel")) {
            return "buckets";
        } else {
            return "units";
        }
    }
    @SuppressWarnings("unchecked")
    public static CellInfo getCellInfo(ItemStack is)
    {
        if (!(is.getItem() instanceof IStorageCell<?>)) return null;

        IStorageCell<?> storageCell = (IStorageCell<?>) is.getItem();

        appeng.api.storage.ICellHandler handler = AEApi.instance().registries().cell().getHandler(is);
        if (handler == null) return null;

        IStorageChannel<?> channel = storageCell.getChannel();
        appeng.api.storage.IMEInventoryHandler<?> inventory = handler.getCellInventory(is, null, (IStorageChannel) channel);
        if (inventory == null) return null;

        if (!(inventory instanceof appeng.api.storage.ICellInventoryHandler)) return null;

        ICellInventory<?> cellInv = ((appeng.api.storage.ICellInventoryHandler<?>) inventory).getCellInv();
        if (cellInv == null) return null;

        return new CellInfo(channel, cellInv);
    }
    @Nonnull
    @Override
    public String getUid()
    {
        return UID;
    }
    @Nonnull
    @Override
    public String getTitle()
    {
        return I18n.format("infinitycell.jei.cellview");
    }
    @Nonnull
    @Override
    public String getModName()
    {
        return "infinitycell";
    }
    @Nonnull
    @Override
    public IDrawable getBackground()
    {
        return this.background;
    }
    @Nullable
    @Override
    public IDrawable getIcon()
    {
        return this.icon;
    }
    @Override
    public void drawExtras(@Nonnull Minecraft minecraft)
    {
        if (this.isLoading) {
            // 显示加载中状态
            GlStateManager.pushMatrix();
            GlStateManager.scale(0.85, 0.85, 0.85);
            String loadingText = I18n.format("infinitycell.jei.cellview.loading");
            minecraft.fontRenderer.drawString(loadingText, 3, 3, 0x555555);
            GlStateManager.popMatrix();

            // 尝试重新获取缓存数据
            if (this.currentCellStack != null) {
                CellDataCache.CachedCellData data = CellDataCache.getInstance().getCachedData(this.currentCellStack);
                if (data != null) {
                    Log.info("InfinityCellCategory drawExtras: data arrived! canceling loading");
                    // 数据已到达，取消加载状态
                    this.isLoading = false;
                    this.cachedCellData = data;
                    if (this.currentRecipeLayout != null && this.currentRecipeWrapper != null) {
                        try {
                            setupRecipeFromCache(this.currentRecipeLayout, this.currentRecipeWrapper, data);
                        } catch (Exception e) {
                            com.mcdyc.infinitycell.InfinityCell.LOGGER.error("Failed to setup JEI cell view from cache", e);
                        }
                    }
                }
            }
        } else {
            int offset = 3;
            int fontHeight = minecraft.fontRenderer.FONT_HEIGHT;
            NumberFormat format = NumberFormat.getInstance();

            // 使用缓存数据或 CellInfo 数据
            long storedItemCount;
            int transferFactor;
            String unitName;

            if (this.cachedCellData != null) {
                storedItemCount = this.cachedCellData.getStoredItemCount();
                transferFactor = getTransferFactorFromCache(this.cachedCellData);
                unitName = getUnitNameFromCache(this.cachedCellData);
                // 确保界面槽位列表被主动填充，即使没有经历 isLoading 的状态跃迁
                if (this.currentUiStacks.isEmpty() && this.currentRecipeLayout != null && this.currentRecipeWrapper != null) {
                    Log.warn("InfinityCellCategory drawExtras: currentUiStacks is empty but data isn't! Force updating!");
                    try {
                        setupRecipeFromCache(this.currentRecipeLayout, this.currentRecipeWrapper, this.cachedCellData);
                    } catch (Exception e) {
                        com.mcdyc.infinitycell.InfinityCell.LOGGER.error("Failed to setup JEI cell view on-demand", e);
                    }
                }
            } else if (this.cellInfo != null) {
                storedItemCount = this.cellInfo.cellInv.getStoredItemCount();
                transferFactor = this.getTransferFactor();
                unitName = I18n.format("infinitycell.jei.cellview." + getStorageChannelUnits(this.cellInfo.channel));
            } else {
                return;
            }

            GlStateManager.pushMatrix();
            GlStateManager.scale(0.85, 0.85, 0.85);

            long capacity;
            if (this.cachedCellData != null) {
                capacity = this.cachedCellData.getTotalBytes() > Integer.MAX_VALUE / 2
                    ? Long.MAX_VALUE / transferFactor
                    : (this.cachedCellData.getRemainingItemCount() + storedItemCount) / transferFactor;
            } else {
                capacity = (this.cellInfo.cellInv.getRemainingItemCount() + storedItemCount) / transferFactor;
            }

            String formattedCapacity = format.format(capacity);
            if (this.cachedCellData != null && this.cachedCellData.getTotalBytes() > Integer.MAX_VALUE / 2) {
                formattedCapacity = "Inf";
            } else if (this.cellInfo != null && this.cellInfo.cellInv.getTotalBytes() > Integer.MAX_VALUE / 2) {
                formattedCapacity = "Inf";
            }

            long byteLoss = 0;
            long capacityLoss = 0;
            if (this.cachedCellData != null) {
                byteLoss = 0; // 我们的元件没有每类型字节损耗
            } else if (this.cellInfo != null) {
                byteLoss = this.cellInfo.cellInv.getBytesPerType() * this.cellInfo.cellInv.getStoredItemTypes();
                capacityLoss = byteLoss * this.cellInfo.channel.getUnitsPerByte() / transferFactor;
            }

            minecraft.fontRenderer.drawString(I18n.format("infinitycell.jei.cellview.stored", format.format(storedItemCount / transferFactor), formattedCapacity, unitName), offset, offset, 0x000000);
            minecraft.fontRenderer.drawString(I18n.format("infinitycell.jei.cellview.loss", format.format(capacityLoss), unitName), offset, offset + fontHeight + 2, 0x000000);

            GlStateManager.popMatrix();
            GlStateManager.color(1, 1, 1);

            for (ExtendedStackInfo info : this.currentUiStacks) {
                this.slotSprite.draw(minecraft, info.getPosX(), info.getPosY());
            }

            // Manually render items and AE2 stack size text (JEI won't render items due to NOOP_ITEM_RENDERER)
            net.minecraft.client.renderer.RenderItem itemRender = minecraft.getRenderItem();
            for (ExtendedStackInfo info : this.currentUiStacks) {
                IAEStack<?> stack = info.getStack();
                int sx = info.getPosX() + 1;
                int sy = info.getPosY() + 1;

                if (!(stack instanceof appeng.api.storage.data.IAEFluidStack)) {
                    ItemStack itemStack = stack.asItemStackRepresentation();
                    GlStateManager.pushMatrix();
                    net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
                    itemRender.renderItemAndEffectIntoGUI(itemStack, sx, sy);
                    itemRender.renderItemOverlayIntoGUI(minecraft.fontRenderer, itemStack, sx, sy, "");
                    net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
                    GlStateManager.popMatrix();

                    appeng.util.item.AEItemStack aeStack = appeng.util.item.AEItemStack.fromItemStack(itemStack);
                    if (aeStack != null) {
                        aeStack.setStackSize(stack.getStackSize());
                        stackSizeRenderer.renderStackSize(minecraft.fontRenderer, aeStack, sx, sy);
                    }
                } else {
                    appeng.api.storage.data.IAEFluidStack fluidStack = (appeng.api.storage.data.IAEFluidStack) stack;
                    fluidStackSizeRenderer.renderStackSize(minecraft.fontRenderer, fluidStack, sx, sy);
                }
            }
        }
    }
    private int getTransferFactor()
    {
        if (this.cellInfo == null) return 1;
        int transferFactor;
        if (Platform.isModLoaded("mekeng") && this.cellInfo.channel.getClass().getName().contains("IGasStorageChannel")) {
            transferFactor = 1000;
        } else {
            transferFactor = this.cellInfo.channel.getUnitsPerByte();
        }
        return transferFactor;
    }
    private int getTransferFactorFromCache(CellDataCache.CachedCellData cachedData)
    {
        if (cachedData.getStoredStacks().isEmpty()) return 1;
        IAEStack<?> firstStack = cachedData.getStoredStacks().get(0);
        for (IStorageChannel<?> ch : AEApi.instance().storage().storageChannels()) {
            if (firstStack.isItem() && ch instanceof appeng.api.storage.channels.IItemStorageChannel) {
                return ch.getUnitsPerByte();
            } else if (firstStack.isFluid() && ch instanceof appeng.api.storage.channels.IFluidStorageChannel) {
                return ch.getUnitsPerByte();
            } else if (Platform.isModLoaded("mekeng") && firstStack.getClass().getName().contains("AEGasStack") && ch.getClass().getName().contains("IGasStorageChannel")) {
                return 1000;
            }
        }
        return 1;
    }
    private String getUnitNameFromCache(CellDataCache.CachedCellData cachedData)
    {
        if (cachedData.getStoredStacks().isEmpty()) return I18n.format("infinitycell.jei.cellview.items");
        IAEStack<?> firstStack = cachedData.getStoredStacks().get(0);
        for (IStorageChannel<?> ch : AEApi.instance().storage().storageChannels()) {
            if (firstStack.isItem() && ch instanceof appeng.api.storage.channels.IItemStorageChannel) {
                return I18n.format("infinitycell.jei.cellview.items");
            } else if (firstStack.isFluid() && ch instanceof appeng.api.storage.channels.IFluidStorageChannel) {
                return I18n.format("infinitycell.jei.cellview.buckets");
            } else if (Platform.isModLoaded("mekeng") && firstStack.getClass().getName().contains("AEGasStack") && ch.getClass().getName().contains("IGasStorageChannel")) {
                return I18n.format("infinitycell.jei.cellview.buckets");
            }
        }
        return I18n.format("infinitycell.jei.cellview.items");
    }
    @Nonnull
    @Override
    public List<String> getTooltipStrings(int mouseX, int mouseY)
    {
        if (mouseX > 0 && mouseY > 0 && mouseX < WIDTH && mouseY < TOTAL_HEIGHT - GRID_HEIGHT - 2) {
            long storedItemTypes = 0;
            long bytesPerType = 0;

            if (this.cachedCellData != null) {
                storedItemTypes = this.cachedCellData.getStoredItemTypes();
                bytesPerType = 0; // 我们的元件没有每类型字节损耗
            } else if (this.cellInfo != null) {
                storedItemTypes = this.cellInfo.cellInv.getStoredItemTypes();
                bytesPerType = this.cellInfo.cellInv.getBytesPerType();
            }

            if (bytesPerType > 0 && storedItemTypes > 0) {
                NumberFormat format = NumberFormat.getInstance();
                long byteLoss = bytesPerType * storedItemTypes;

                List<String> tooltip = new ArrayList<>();
                tooltip.add(I18n.format("infinitycell.jei.cellview.hover.1", format.format(storedItemTypes)));
                tooltip.add(I18n.format("infinitycell.jei.cellview.hover.2"));
                tooltip.add("");
                tooltip.add(I18n.format("infinitycell.jei.cellview.hover.3", format.format(bytesPerType), format.format(storedItemTypes), format.format(byteLoss)));
                return tooltip;
            } else if (storedItemTypes > 0) {
                NumberFormat format = NumberFormat.getInstance();
                List<String> tooltip = new ArrayList<>();
                tooltip.add(I18n.format("infinitycell.jei.cellview.hover.1", format.format(storedItemTypes)));
                return tooltip;
            }
        }
        return Collections.emptyList();
    }
    // Callbacks unneeded anymore due to manual rendering
    public static class CellInfo
    {
        public final IStorageChannel<?> channel;
        public final ICellInventory<?> cellInv;

        public CellInfo(IStorageChannel<?> channel, ICellInventory<?> cellInv)
        {
            this.channel = channel;
            this.cellInv = cellInv;
        }
    }
    public static class ExtendedStackInfo
    {
        private final IAEStack<?> stack;
        private final int posX, posY;
        private final List<String> extraTooltip;
        public ExtendedStackInfo(IAEStack<?> stack, int posX, int posY, List<String> extraTooltip)
        {
            this.stack = stack;
            this.posX = posX;
            this.posY = posY;
            this.extraTooltip = extraTooltip;
        }
        public IAEStack<?> getStack()
        {
            return stack;
        }
        public int getPosX()
        {
            return posX;
        }
        public int getPosY()
        {
            return posY;
        }
        public List<String> getExtraTooltip()
        {
            return extraTooltip;
        }
    }
}
