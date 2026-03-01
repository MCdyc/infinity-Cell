package com.mcdyc.infinitycell.item;

import appeng.api.AEApi;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

public class DebugInjectorItem extends Item {
    public DebugInjectorItem() {
        this.setRegistryName("debug_injector");
        this.setTranslationKey("debug_injector");
        this.setCreativeTab(CreativeTabs.AdvancedCellItem);
        this.setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        if (worldIn.isRemote) {
            return new ActionResult<>(EnumActionResult.SUCCESS, playerIn.getHeldItem(handIn));
        }

        // 尝试从副手物品寻找无限盘
        ItemStack offhandStack = playerIn.getHeldItemOffhand();
        if (offhandStack.isEmpty() || !(offhandStack.getItem() instanceof AdvancedCellItem)) {
            playerIn.sendMessage(new TextComponentString("请将无限元件放在副手！"));
            return new ActionResult<>(EnumActionResult.PASS, playerIn.getHeldItem(handIn));
        }

        AdvancedCellItem cellItem = (AdvancedCellItem) offhandStack.getItem();
        if (cellItem.tier != AdvancedCellItem.StorageTier.INF) {
            playerIn.sendMessage(new TextComponentString("副手的元件不是无限级(INF)，请使用无限级元件进行极限测试！"));
            return new ActionResult<>(EnumActionResult.PASS, playerIn.getHeldItem(handIn));
        }

        IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IMEInventoryHandler<IAEItemStack> inv = AEApi.instance().registries().cell().getCellInventory(offhandStack, null, itemChannel);

        if (inv == null) {
            playerIn.sendMessage(new TextComponentString("无法获取该盘的内部存储！"));
            return new ActionResult<>(EnumActionResult.PASS, playerIn.getHeldItem(handIn));
        }

        playerIn.sendMessage(new TextComponentString("开始极限压力测试..."));

        try {
            // 测试1：塞入巨量石头 (Long.MAX_VALUE / 4)
            long massiveAmount = Long.MAX_VALUE / 4;
            IAEItemStack massiveStone = itemChannel.createStack(new ItemStack(Blocks.STONE));
            massiveStone.setStackSize(massiveAmount);

            IAEItemStack reject1 = inv.injectItems(massiveStone, appeng.api.config.Actionable.MODULATE, new appeng.me.helpers.PlayerSource(playerIn, null));
            if (reject1 == null) {
                playerIn.sendMessage(new TextComponentString("1. 成功注入了 " + massiveAmount + " 个石头。"));
            } else {
                playerIn.sendMessage(new TextComponentString("1. 石头注入被拒！被退回：" + reject1.getStackSize() + " 个。"));
            }

            // 测试2：塞入巨量泥土 (Long.MAX_VALUE / 4)
            long massiveAmount2 = Long.MAX_VALUE / 4;
            IAEItemStack massiveDirt = itemChannel.createStack(new ItemStack(Blocks.DIRT));
            massiveDirt.setStackSize(massiveAmount2);
            IAEItemStack reject2 = inv.injectItems(massiveDirt, appeng.api.config.Actionable.MODULATE, new appeng.me.helpers.PlayerSource(playerIn, null));
             if (reject2 == null) {
                playerIn.sendMessage(new TextComponentString("2. 成功注入了 " + massiveAmount2 + " 个泥土。"));
            } else {
                playerIn.sendMessage(new TextComponentString("2. 泥土注入被拒！被退回：" + reject2.getStackSize() + " 个。"));
            }


            // 测试3：塞入 100,000 种拥有随机或递增 NBT 的不同物品
            playerIn.sendMessage(new TextComponentString("正在生成并塞入 10 万种不同 NBT 的苹果... 这可能会卡顿几秒钟。"));
            long timeStart = System.currentTimeMillis();
            
            int injectedTypes = 0;
            for (int i = 0; i < 100000; i++) {
                ItemStack apple = new ItemStack(Items.APPLE);
                NBTTagCompound nbt = new NBTTagCompound();
                nbt.setInteger("DebugID", i);
                apple.setTagCompound(nbt);

                IAEItemStack aeApple = itemChannel.createStack(apple);
                aeApple.setStackSize(1);
                
                IAEItemStack rej = inv.injectItems(aeApple, appeng.api.config.Actionable.MODULATE, new appeng.me.helpers.PlayerSource(playerIn, null));
                if (rej == null) {
                    injectedTypes++;
                }
            }
            long timeEnd = System.currentTimeMillis();

            playerIn.sendMessage(new TextComponentString("3. 成功注入了 " + injectedTypes + " 种不同的苹果！耗时: " + (timeEnd - timeStart) + " ms"));

            playerIn.sendMessage(new TextComponentString("=== 极限压力测试完毕 ==="));

        } catch (Exception e) {
            e.printStackTrace();
            playerIn.sendMessage(new TextComponentString("测试中途发生异常: " + e.getMessage()));
        }

        return new ActionResult<>(EnumActionResult.SUCCESS, playerIn.getHeldItem(handIn));
    }
}
