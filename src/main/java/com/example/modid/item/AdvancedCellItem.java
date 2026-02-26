package com.example.modid.item;

import appeng.api.AEApi;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.util.Platform;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class AdvancedCellItem extends Item
{

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

        // 命名规则例如: item_cell_1k, fluid_cell_16384k, gas_cell_inf
        String capName = tier == StorageTier.INF ? "inf" : tier.kb + "k";
        String registryName = type.name().toLowerCase() + "_cell_" + capName;

        this.setRegistryName(registryName);
        this.setTranslationKey(registryName);
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
                // 注意：MekanismEnergistics 气体通道在这个包路径！
                channelClass = Class.forName("com.mekeng.github.common.me.storage.IGasStorageChannel");
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        appeng.api.storage.IStorageChannel<?> channel = AEApi.instance().storage().getStorageChannel((Class) channelClass);

        if (channel != null) {
            // 把我们自己的盘强行塞给 AE2 官方审查系统读取
            IMEInventoryHandler<?> inv = AEApi.instance().registries().cell().getCellInventory(stack, null, channel);
            if (inv instanceof appeng.api.storage.ICellInventoryHandler) {
                appeng.api.storage.ICellInventoryHandler<?> cellInvHandler = (appeng.api.storage.ICellInventoryHandler<?>) inv;

                // 统一调用 AE2 系统渲染器，它会自动帮你格式化输出总容量、占用字节、分区、粘性等所有的基础提示条！
                // 即使是第三方气体通道，由于实现了 ICellInventoryHandler 接口，容量部分也能正常被 AE2 算出来并高亮。
                AEApi.instance().client().addCellInformation(cellInvHandler, tooltip);

                // 如果是气体通道，由于 AE2 系统的 addCellInformation 迭代列表时不认识气体栈而会跳过显示，因此我们专门补画
                if (this.type == StorageType.GAS) {
                    if (net.minecraft.client.Minecraft.getMinecraft().gameSettings.advancedItemTooltips || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT) || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT)) {
                        appeng.api.storage.ICellInventory<?> cellInv = cellInvHandler.getCellInv();
                        if (cellInv != null) {
                            appeng.api.storage.data.IItemList<?> itemList = cellInvHandler.getChannel().createList();
                            cellInv.getAvailableItems((appeng.api.storage.data.IItemList) itemList);
                            for (appeng.api.storage.data.IAEStack<?> s : itemList) {
                                long size = s.getStackSize();
                                String unit = size >= 1000 ? "B" : "mB";
                                int log = (int) Math.floor(Math.log10(size)) / 2;
                                int index = Math.max(0, Math.min(3, log));
                                java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols();
                                symbols.setDecimalSeparator('.');
                                java.text.DecimalFormat format = new java.text.DecimalFormat(new String[]{"#.000", "#.00", "#.0", "#"}[index]);
                                format.setDecimalFormatSymbols(symbols);
                                format.setRoundingMode(java.math.RoundingMode.DOWN);
                                String formattedSize = format.format(size / 1000d).concat(unit);

                                // 参考 AE2 提示的默认格式与颜色（\u00A77 为灰色，这是工具提示上物品名称的默认辅助信息颜色）
                                try {
                                    Object gasStackObj = s.getClass().getMethod("getGasStack").invoke(s);
                                    if (gasStackObj != null) {
                                        Object gasObj = gasStackObj.getClass().getMethod("getGas").invoke(gasStackObj);
                                        if (gasObj != null) {
                                            String gasName = (String) gasObj.getClass().getMethod("getLocalizedName").invoke(gasObj);
                                            tooltip.add("\u00A77" + gasName + ": " + formattedSize);
                                        }
                                    }
                                } catch (Exception e) {
                                    tooltip.add("\u00A77" + "Gas" + ": " + formattedSize);
                                }
                            }
                        }
                    }
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
