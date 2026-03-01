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

public class AdvancedCellItem extends Item
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
            // 无限盘：已用字节行 + 容量标签行，格式/颜色全在 lang
            tooltip.add(I18n.format("infinitycell.tooltip.capacity_infinite"));
        } else {
            // 有限盘：已用/总容量行，格式/颜色全在 lang
            tooltip.add(I18n.format("infinitycell.tooltip.used_total_bytes", usedBytes, cellInv.getTotalBytes()));
        }
    }

    /**
     * 工厂方法：动态生成所有支持的磁盘物品列表
     */
    public static List<AdvancedCellItem> createAllDisks()
    {
        List<AdvancedCellItem> disks = new ArrayList<>();

        boolean hasGas = false;
        // 通过尝试加载某些可能引入气体的 AE2 附属类来判断是否存在气体存储 (如 MekanismEnergistics/ExtraCells 等)
        // 这里用模糊探索机制：如果 appeng.api.storage.channels.IGasStorageChannel 存在则开启气体盘注册
        if (Platform.isModLoaded("mekeng")) {
            hasGas = true;
        }


        for (StorageTier tier : StorageTier.values()) {
            disks.add(new AdvancedCellItem(tier, StorageType.ITEM));
            disks.add(new AdvancedCellItem(tier, StorageType.FLUID));
            if (hasGas) {
                disks.add(new AdvancedCellItem(tier, StorageType.GAS));
            }
        }
        return disks;
    }
}
