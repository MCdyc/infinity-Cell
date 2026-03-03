package com.mcdyc.infinitycell.item;

import appeng.api.AEApi;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.util.Platform;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 核心存储元件物品类。
 * 代表了玩家库存和网络中的所有等级、所有类型（物品/流体/气体）存储磁盘实体。
 * 实现了 AE2 的存储设备以及便携式 GUI 接口。
 */
public class AdvancedCellItem extends Item implements appeng.api.implementations.items.IStorageCell
{
    /**
     * 自定义创造模式物品栏标签，图标使用 64k 物品盘
     */
    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs("infinitycell")
    {
        @Override
        @SideOnly(Side.CLIENT)
        public ItemStack createIcon()
        {
            // 从主类中已注册的磁盘列表中找到 64k 物品盘作为图标
            for (AdvancedCellItem cell : com.mcdyc.infinitycell.InfinityCell.ADVANCED_CELLS) {
                if (cell.tier == StorageTier.T_64K && cell.type == StorageType.ITEM) {
                    return new ItemStack(cell);
                }
            }
            return ItemStack.EMPTY;
        }
    };

    public enum StorageTier
    {
        T_1K(1),
        T_4K(4),
        T_16K(16),
        T_64K(64),
        T_256K(256),
        T_1024K(1024),
        T_4096K(4096),
        T_16384K(16384),
        INF(-1);

        public final int kb;

        StorageTier(int kb)
        {
            this.kb = kb;
        }
    }

    public enum StorageType
    {
        ITEM, FLUID, GAS;
    }

    public final StorageTier tier;
    public final StorageType type;

    public AdvancedCellItem(StorageTier tier, StorageType type)
    {
        this.tier = tier;
        this.type = type;

        this.setMaxStackSize(1);
        this.setCreativeTab(CREATIVE_TAB);

        // 命名规则例如: item_cell_1k, fluid_cell_16384k, gas_cell_inf
        String capName = tier == StorageTier.INF ? "inf" : tier.kb + "k";
        String registryName = type.name().toLowerCase() + "_cell_" + capName;

        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
    }

    /**
     * 每 tick 检查：当物品在玩家背包中时，懒分配 UUID
     * 这样创造模式物品栏里的物品不会提前获得 UUID
     */
    @Override
    public void onUpdate(ItemStack stack, World worldIn, Entity entityIn, int itemSlot, boolean isSelected)
    {
        if (!worldIn.isRemote && stack.getCount() > 0) {
            NBTTagCompound nbt = stack.getTagCompound();
            if (nbt == null || !nbt.hasKey("disk_uuid")) {
                if (nbt == null) {
                    nbt = new NBTTagCompound();
                    stack.setTagCompound(nbt);
                }
                nbt.setString("disk_uuid", UUID.randomUUID().toString());
            }
        }
    }


    /**
     * 玩家右键交互事件重写。
     * 如果处于潜行并右击空气，则尝试执行“拆解磁盘”的逻辑：
     * 判断内部为空后，销毁后端 UUID 数据文件，退还组件核心和外壳并销毁此物品本身；
     * 如果未潜行且为物品型存储磁盘，则当作便携式存储终端主动打开面板。
     *
     * @param worldIn  当前执行交互的世界。
     * @param playerIn 执行交互动作的玩家实体。
     * @param handIn   玩家当下执行动作的手部（主/副手）。
     * @return 该交互产生的结果与所影响的物品状态。
     */
    @Override
    public net.minecraft.util.ActionResult<ItemStack> onItemRightClick(World worldIn, net.minecraft.entity.player.EntityPlayer playerIn, net.minecraft.util.EnumHand handIn)
    {
        ItemStack stack = playerIn.getHeldItem(handIn);

        // 如果在潜行并且是在空气中右键，执行分离逻辑
        if (playerIn.isSneaking() && !worldIn.isRemote) {

            // 检查硬盘是否为空
            Class<?> channelClass = IItemStorageChannel.class;
            if (this.type == StorageType.FLUID) {
                channelClass = IFluidStorageChannel.class;
            } else if (this.type == StorageType.GAS) {
                try {
                    channelClass = Class.forName("com.mekeng.github.common.me.storage.IGasStorageChannel");
                } catch (ClassNotFoundException e) {
                    return new net.minecraft.util.ActionResult<>(net.minecraft.util.EnumActionResult.PASS, stack);
                }
            }

            @SuppressWarnings({"rawtypes", "unchecked"})
            appeng.api.storage.IStorageChannel<?> channel = AEApi.instance().storage().getStorageChannel((Class) channelClass);
            if (channel != null) {
                IMEInventoryHandler<?> inv = AEApi.instance().registries().cell().getCellInventory(stack, null, channel);
                if (inv instanceof appeng.api.storage.ICellInventoryHandler) {
                    appeng.api.storage.ICellInventory<?> cellInv = ((appeng.api.storage.ICellInventoryHandler<?>) inv).getCellInv();
                    if (cellInv != null) {
                        if (cellInv.getUsedBytes() == 0 && cellInv.getStoredItemTypes() == 0) {
                            // 硬盘为空，执行分离

                            // 1. 获取要给予的组件
                            ItemStack targetComponent = getOriginalComponent();

                            if (!CELL_HOUSINGS.isEmpty()) {
                                // 2. 从玩家手上完全移除当前硬盘（兼容创造模式的拆分逻辑）
                                playerIn.setHeldItem(handIn, ItemStack.EMPTY);

                                // 3. 给予外壳和组件
                                appeng.util.InventoryAdaptor ia = appeng.util.InventoryAdaptor.getAdaptor(playerIn);
                                if (ia != null) {
                                    ItemStack housingLeft = ia.addItems(new ItemStack(CELL_HOUSINGS.get(0)));
                                    if (!housingLeft.isEmpty()) {
                                        playerIn.dropItem(housingLeft, false);
                                    }

                                    if (!targetComponent.isEmpty()) {
                                        ItemStack componentLeft = ia.addItems(targetComponent);
                                        if (!componentLeft.isEmpty()) {
                                            playerIn.dropItem(componentLeft, false);
                                        }
                                    }
                                }

                                // 4. 删除对应的 UUID 数据文件并清除内存对象
                                if (stack.hasTagCompound() && stack.getTagCompound().hasKey("disk_uuid")) {
                                    String uuid = stack.getTagCompound().getString("disk_uuid");
                                    String dataKey = "infinite/" + uuid;

                                    // 清除内存中的数据对象的 dirty 标记，防止世界保存时重新创建文件
                                    com.mcdyc.infinitycell.storage.AdvancedCellData memoryData =
                                            (com.mcdyc.infinitycell.storage.AdvancedCellData) worldIn.getMapStorage()
                                                    .getOrLoadData(com.mcdyc.infinitycell.storage.AdvancedCellData.class, dataKey);
                                    if (memoryData != null) {
                                        memoryData.clearDirty();
                                    }

                                    // 删除物理文件
                                    java.io.File infiniteDir = new java.io.File(worldIn.getSaveHandler().getWorldDirectory(), "data/infinite");
                                    java.io.File dataFile = new java.io.File(infiniteDir, uuid + ".dat");
                                    if (dataFile.exists()) {
                                        boolean deleted = dataFile.delete();
                                        if (deleted) {
                                            com.mcdyc.infinitycell.InfinityCell.LOGGER.info("Deleted UUID data file for separated cell: {}", uuid);
                                        }
                                    }
                                }

                                // 发送背包更新
                                if (playerIn.inventoryContainer != null) {
                                    playerIn.inventoryContainer.detectAndSendChanges();
                                }

                                return new net.minecraft.util.ActionResult<>(net.minecraft.util.EnumActionResult.SUCCESS, stack);
                            }
                        }
                    }
                }
            }
        }

        return new net.minecraft.util.ActionResult<>(net.minecraft.util.EnumActionResult.PASS, stack);
    }

    /**
     * Tooltip：只显示 UUID 与字节占用，所有颜色与文本均由 lang 文件控制。
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn)
    {
        // UUID 签名行
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey("disk_uuid")) {
            tooltip.add(I18n.format("infinitycell.tooltip.uuid", stack.getTagCompound().getString("disk_uuid")));
        } else {
            tooltip.add(I18n.format("infinitycell.tooltip.uuid_unassigned"));
        }

        // 获取对应通道
        Class<?> channelClass = IItemStorageChannel.class;
        if (this.type == StorageType.FLUID) {
            channelClass = IFluidStorageChannel.class;
        } else if (this.type == StorageType.GAS) {
            try {
                channelClass = Class.forName("com.mekeng.github.common.me.storage.IGasStorageChannel");
            } catch (ClassNotFoundException e) {
                return;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        appeng.api.storage.IStorageChannel<?> channel = AEApi.instance().storage().getStorageChannel((Class) channelClass);
        if (channel == null) return;

        IMEInventoryHandler<?> inv = AEApi.instance().registries().cell().getCellInventory(stack, null, channel);
        if (!(inv instanceof appeng.api.storage.ICellInventoryHandler)) return;

        appeng.api.storage.ICellInventoryHandler<?> handler = (appeng.api.storage.ICellInventoryHandler<?>) inv;
        appeng.api.storage.ICellInventory<?> cellInv = handler.getCellInv();
        if (cellInv == null) return;

        long usedBytes = cellInv.getUsedBytes();

        if (this.tier == StorageTier.INF) {
            // 无限盘：种类数 + 容量标签行，格式/颜色全在 lang
            tooltip.add(I18n.format("infinitycell.tooltip.capacity_infinite", usedBytes));
        } else {
            // 有限盘：已用/总容量行，格式/颜色全在 lang
            tooltip.add(I18n.format("infinitycell.tooltip.used_total_bytes", usedBytes, cellInv.getTotalBytes()));
        }
    }

    public static final List<AdvancedCellHousingItem> CELL_HOUSINGS = new ArrayList<>();
    public static final List<InfiniteComponentItem> INFINITE_COMPONENTS = new ArrayList<>();

    /**
     * 工厂方法：动态生成所有支持的磁盘物品列表
     */
    public static List<AdvancedCellItem> createAllDisks()
    {
        List<AdvancedCellItem> disks = new ArrayList<>();

        // 我们只生成一个通用的外壳
        CELL_HOUSINGS.add(new AdvancedCellHousingItem());

        boolean hasGas = Platform.isModLoaded("mekeng");
        boolean hasNAE2 = Platform.isModLoaded("nae2");

        // 创建无限组件（物品、流体、气体三种）
        INFINITE_COMPONENTS.add(new InfiniteComponentItem(InfiniteComponentItem.ComponentType.ITEM));
        INFINITE_COMPONENTS.add(new InfiniteComponentItem(InfiniteComponentItem.ComponentType.FLUID));
        if (hasGas) {
            INFINITE_COMPONENTS.add(new InfiniteComponentItem(InfiniteComponentItem.ComponentType.GAS));
        }

        for (StorageTier tier : StorageTier.values()) {
            boolean shouldRegister = true;
            if (tier.kb > 64 && tier != StorageTier.INF) {
                // 256k, 1024k, 4096k, 16384k
                shouldRegister = hasNAE2;
            }

            if (shouldRegister) {
                disks.add(new AdvancedCellItem(tier, StorageType.ITEM));
                disks.add(new AdvancedCellItem(tier, StorageType.FLUID));
                if (hasGas) {
                    disks.add(new AdvancedCellItem(tier, StorageType.GAS));
                }
            }
        }
        return disks;
    }

    /**
     * 内部解析方法，根据自身 Tier 以及通道类型，反向推理其制作此磁盘时“理应包含”的对应旧式存储组件类型，以便在分离磁盘时予以归还。
     *
     * @return 此磁盘对应的原版/模组附加原生存储组件的物品栈（例如：AE2原版 64k组件、NAE2的 4096k流体组件 等）。若未能推导则返回空栈。
     */
    public ItemStack getOriginalComponent() {
        // 对于无限磁盘，返回对应的无限组件
        if (this.tier == StorageTier.INF) {
            InfiniteComponentItem.ComponentType componentType;
            if (this.type == StorageType.FLUID) {
                componentType = InfiniteComponentItem.ComponentType.FLUID;
            } else if (this.type == StorageType.GAS) {
                componentType = InfiniteComponentItem.ComponentType.GAS;
            } else {
                componentType = InfiniteComponentItem.ComponentType.ITEM;
            }

            for (InfiniteComponentItem component : INFINITE_COMPONENTS) {
                if (component.type == componentType) {
                    return new ItemStack(component);
                }
            }
            return ItemStack.EMPTY;
        }

        String modid = "";
        String itemName = "material";
        int meta = 0;

        if (this.tier.kb <= 64) {
            if (this.type == StorageType.GAS) {
                modid = "mekeng";
                itemName = "gas_core_" + this.tier.kb + "k";
                meta = 0;
            } else {
                modid = "appliedenergistics2";
                if (this.type == StorageType.ITEM) {
                    if (this.tier == StorageTier.T_1K) meta = 35;
                    else if (this.tier == StorageTier.T_4K) meta = 36;
                    else if (this.tier == StorageTier.T_16K) meta = 37;
                    else if (this.tier == StorageTier.T_64K) meta = 38;
                } else if (this.type == StorageType.FLUID) {
                    if (this.tier == StorageTier.T_1K) meta = 54;
                    else if (this.tier == StorageTier.T_4K) meta = 55;
                    else if (this.tier == StorageTier.T_16K) meta = 56;
                    else if (this.tier == StorageTier.T_64K) meta = 57;
                }
            }
        } else {
            modid = "nae2";
            if (this.type == StorageType.ITEM) {
                if (this.tier == StorageTier.T_256K) meta = 1;
                else if (this.tier == StorageTier.T_1024K) meta = 2;
                else if (this.tier == StorageTier.T_4096K) meta = 3;
                else if (this.tier == StorageTier.T_16384K) meta = 4;
            } else if (this.type == StorageType.FLUID) {
                if (this.tier == StorageTier.T_256K) meta = 5;
                else if (this.tier == StorageTier.T_1024K) meta = 6;
                else if (this.tier == StorageTier.T_4096K) meta = 7;
                else if (this.tier == StorageTier.T_16384K) meta = 8;
            } else if (this.type == StorageType.GAS) {
                if (this.tier == StorageTier.T_256K) meta = 9;
                else if (this.tier == StorageTier.T_1024K) meta = 10;
                else if (this.tier == StorageTier.T_4096K) meta = 11;
                else if (this.tier == StorageTier.T_16384K) meta = 12;
            }
        }

        Item materialItem = Item.getByNameOrId(modid + ":" + itemName);
        if (materialItem != null) {
            return new ItemStack(materialItem, 1, meta);
        }

        return ItemStack.EMPTY;
    }

    /**
     * 获取当前磁盘所映射绑定的 AE2 通道处理器引用。
     *
     * @return 物品通道、流体通道或来自外置模组的气体通道对象。如果获取失败则返回 null。
     */
    @Override
    public appeng.api.storage.IStorageChannel getChannel() {
        if (this.type == StorageType.FLUID) {
            return appeng.api.AEApi.instance().storage().getStorageChannel(appeng.api.storage.channels.IFluidStorageChannel.class);
        } else if (this.type == StorageType.GAS) {
            try {
                return appeng.api.AEApi.instance().storage().getStorageChannel((Class) Class.forName("com.mekeng.github.common.me.storage.IGasStorageChannel"));
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return appeng.api.AEApi.instance().storage().getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
    }

    // --- IStorageCell methods ---
    @Override
    public int getBytes(ItemStack cellItem) {
        return this.tier == StorageTier.INF ? Integer.MAX_VALUE : this.tier.kb * 1024;
    }

    @Override
    public int getBytesPerType(ItemStack cellItem) {
        return 8;
    }

    @Override
    public int getTotalTypes(ItemStack cellItem) {
        return 63;
    }

    @Override
    public boolean isBlackListed(ItemStack cellItem, appeng.api.storage.data.IAEStack requestedAddition) {
        return false;
    }

    @Override
    public boolean storableInStorageCell() {
        return false;
    }

    @Override
    public boolean isStorageCell(ItemStack cellItem) {
        return true;
    }

    @Override
    public double getIdleDrain() {
        return 0.0;
    }

    // --- ICellWorkbenchItem methods ---
    @Override
    public boolean isEditable(ItemStack is) {
        return true;
    }

    @Override
    public net.minecraftforge.items.IItemHandler getUpgradesInventory(ItemStack is) {
        return null;
    }

    @Override
    public net.minecraftforge.items.IItemHandler getConfigInventory(ItemStack is) {
        return null;
    }

    @Override
    public appeng.api.config.FuzzyMode getFuzzyMode(ItemStack is) {
        return appeng.api.config.FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack is, appeng.api.config.FuzzyMode fzMode) {
    }
}
