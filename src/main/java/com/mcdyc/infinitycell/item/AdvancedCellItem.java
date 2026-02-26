package com.mcdyc.infinitycell.item;

import appeng.api.AEApi;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.util.Platform;
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
    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs("infinitycell") {
        @Override
        @SideOnly(Side.CLIENT)
        public ItemStack createIcon() {
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
     * 强行白嫖 AE2 原版的 GUI 渲染器 (ApiClientHelper.java)
     * 在玩家背包中鼠标悬停在我们的定制盘上时触发，绘制基础容量、总类目以及按住 Shift 的详细存货清单
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn)
    {
        // 显示盘的 UUID 签名
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey("disk_uuid")) {
            tooltip.add("§8UUID: " + stack.getTagCompound().getString("disk_uuid"));
        } else {
            tooltip.add("§8UUID: §7[未分配]");
        }

        // 先找到负责读取这个盘内容的专用通道
        Class<?> channelClass = IItemStorageChannel.class;
        if (this.type == StorageType.FLUID) {
            channelClass = IFluidStorageChannel.class;
        } else if (this.type == StorageType.GAS) {
            // 利用反射防御性调用，防止没装燃气类附属导致崩溃
            try {
                channelClass = Class.forName("com.mekeng.github.common.me.storage.IGasStorageChannel");
            } catch (ClassNotFoundException e) {
                return; // MekanismEnergistics 未安装，放弃渲染气体 tooltip
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        appeng.api.storage.IStorageChannel<?> channel = AEApi.instance().storage().getStorageChannel((Class) channelClass);

        if (channel != null) {
            // 把我们自己的盘强行塞给 AE2 官方审查系统读取
            IMEInventoryHandler<?> inv = AEApi.instance().registries().cell().getCellInventory(stack, null, channel);

            if (inv instanceof appeng.api.storage.ICellInventoryHandler) {
                appeng.api.storage.ICellInventoryHandler<?> cellInvHandler = (appeng.api.storage.ICellInventoryHandler<?>) inv;

                // AE2 默认的元件基础信息 (已用字节/类型等)
                AEApi.instance().client().addCellInformation(cellInvHandler, tooltip);

                if (this.type == StorageType.GAS) {
                    // === 气体 tooltip 渲染，全部通过反射防御调用 ===
                    try {
                        addGasTooltip(cellInvHandler, tooltip);
                    } catch (NoClassDefFoundError ignored) {
                        // MekanismEnergistics 未安装，跳过气体详情渲染
                    }
                }
            }
        }
    }

    /**
     * 气体 tooltip 详细渲染 (独立方法，隔离 MekanismEnergistics 类引用)
     * 所有对 MekEng 类的引用必须集中在这里，由调用方用 try-catch NoClassDefFoundError 包裹。
     */
    @SideOnly(Side.CLIENT)
    private void addGasTooltip(appeng.api.storage.ICellInventoryHandler<?> cellInvHandler, List<String> tooltip)
    {
        appeng.api.storage.ICellInventory<?> cellInventory = cellInvHandler.getCellInv();

        // 只有在按下 Shift 或开启高级提示框时才显示具体内容
        if (cellInventory != null && (net.minecraft.client.Minecraft.getMinecraft().gameSettings.advancedItemTooltips ||
                org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT) ||
                org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT))) {

            // 创建一个空列表用于接收元件内的数据
            @SuppressWarnings({"unchecked", "rawtypes"})
            appeng.api.storage.data.IItemList itemList = cellInventory.getChannel().createList();
            cellInventory.getAvailableItems(itemList);

            // 遍历获取到的所有物品/气体栈
            for (Object s : itemList) {
                if (s instanceof com.mekeng.github.common.me.data.IAEGasStack) {
                    com.mekeng.github.common.me.data.IAEGasStack gasStack = (com.mekeng.github.common.me.data.IAEGasStack) s;
                    long size = gasStack.getStackSize();

                    // 跳过数量为0或负数的无效数据，防止 Math.log10 异常
                    if (size <= 0) continue;

                    // --- 内部集成的 gasStackSize 容量格式化逻辑 ---
                    String unit = size >= 1000 ? "B" : "mB";
                    int log = (int) Math.floor(Math.log10(size)) / 2;
                    int index = Math.max(0, Math.min(3, log));

                    java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols();
                    symbols.setDecimalSeparator('.');
                    java.text.DecimalFormat format = new java.text.DecimalFormat(new String[]{"#.000", "#.00", "#.0", "#"}[index]);
                    format.setDecimalFormatSymbols(symbols);
                    format.setRoundingMode(java.math.RoundingMode.DOWN);

                    String formattedSize = format.format(size / 1000d) + unit;
                    // ------------------------------------------------

                    // 将气体名称和格式化后的容量添加到 tooltip
                    tooltip.add(gasStack.getGasStack().getGas().getLocalizedName() + ": " + formattedSize);
                }
            }
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
