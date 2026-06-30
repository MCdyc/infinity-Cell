package com.mcdyc.infinitycell.command;

import appeng.api.storage.IStorageChannel;
import com.mcdyc.infinitycell.InfinityCell;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import com.mcdyc.infinitycell.storage.AdvancedCellData;
import com.mcdyc.infinitycell.storage.StorageChannelUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * 管理指令：{@code /infinitycell <list|recover <uuid>|clean>}。
 *
 * <p>无限盘的真实内容存在世界存档 {@code data/infinite/<uuid>.dat}，与元件实体解耦。
 * 元件被销毁（岩浆 / 虚空 / /kill 等）后其 UUID 随之丢失，但后端文件仍在——
 * 这里把这些孤儿当作<b>可恢复资产</b>而非垃圾（仿 AE2Things 的 {@code /recover}）：
 * <ul>
 *   <li>{@code list}：列出所有后端文件及其内容摘要；</li>
 *   <li>{@code recover <uuid>}：把指定 UUID 的数据召回为一块无限盘交给玩家；</li>
 *   <li>{@code clean}：仅删除内容为空的孤儿文件（保守清理，绝不碰有内容的文件）。</li>
 * </ul>
 */
public class CommandInfinityCell extends CommandBase {

    @Override
    public String getName() {
        return "infinitycell";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/infinitycell <list|recover <uuid>|clean>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        switch (args[0].toLowerCase()) {
            case "list":
                doList(sender);
                break;
            case "recover":
                if (args.length < 2) {
                    throw new WrongUsageException("/infinitycell recover <uuid>");
                }
                doRecover(sender, args[1]);
                break;
            case "clean":
                doClean(sender);
                break;
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "list", "recover", "clean");
        }
        return Collections.emptyList();
    }

    @Nullable
    private static File infiniteDir() {
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            return null;
        }
        return new File(overworld.getSaveHandler().getWorldDirectory(), "data/infinite");
    }

    private void doList(ICommandSender sender) {
        File dir = infiniteDir();
        if (dir == null || !dir.isDirectory()) {
            sender.sendMessage(new TextComponentString("No infinite cell data found."));
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            sender.sendMessage(new TextComponentString("No infinite cell data files found."));
            return;
        }
        sender.sendMessage(new TextComponentString("Infinite cell backends: " + files.length));
        for (File f : files) {
            String uuid = f.getName().substring(0, f.getName().length() - 4);
            AdvancedCellData data = AdvancedCellData.loadByUuid(uuid);
            String desc = (data == null || data.isEmpty()) ? "(empty)" : data.summary();
            sender.sendMessage(new TextComponentString(" - " + uuid + "  " + desc));
        }
    }

    private void doRecover(ICommandSender sender, String uuid) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);

        File dir = infiniteDir();
        File dat = dir == null ? null : new File(dir, uuid + ".dat");
        if (dat == null || !dat.isFile()) {
            sender.sendMessage(new TextComponentString("No backend file for UUID " + uuid));
            return;
        }

        AdvancedCellData data = AdvancedCellData.loadByUuid(uuid);
        if (data == null || data.isEmpty()) {
            sender.sendMessage(new TextComponentString("Backend for " + uuid + " exists but is empty; nothing to recover."));
            return;
        }

        // 从已存内容推断资源类型，给一块对应类型的无限盘（不知原 tier，INF 能容纳一切是最安全的恢复选择）
        AdvancedCellItem.StorageType type = AdvancedCellItem.StorageType.ITEM;
        IStorageChannel<?> ch = data.firstNonEmptyChannel();
        if (ch != null) {
            if (StorageChannelUtil.isFluidChannel(ch)) {
                type = AdvancedCellItem.StorageType.FLUID;
            } else if (StorageChannelUtil.isGasChannel(ch)) {
                type = AdvancedCellItem.StorageType.GAS;
            }
        }

        AdvancedCellItem cellItem = null;
        for (AdvancedCellItem c : InfinityCell.ADVANCED_CELLS) {
            if (c.tier == AdvancedCellItem.StorageTier.INF && c.type == type) {
                cellItem = c;
                break;
            }
        }
        if (cellItem == null) {
            sender.sendMessage(new TextComponentString("No INF cell registered for type " + type + "."));
            return;
        }

        ItemStack stack = new ItemStack(cellItem);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("disk_uuid", uuid);
        stack.setTagCompound(nbt);

        if (!player.inventory.addItemStackToInventory(stack)) {
            player.dropItem(stack, false);
        }
        if (player.inventoryContainer != null) {
            player.inventoryContainer.detectAndSendChanges();
        }
        sender.sendMessage(new TextComponentString(
                "Recovered " + type + " cell for UUID " + uuid + " (" + data.summary() + ")."));
    }

    private void doClean(ICommandSender sender) {
        File dir = infiniteDir();
        if (dir == null || !dir.isDirectory()) {
            sender.sendMessage(new TextComponentString("No infinite cell data found."));
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            sender.sendMessage(new TextComponentString("No infinite cell data files found."));
            return;
        }
        int deleted = 0;
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String uuid = f.getName().substring(0, f.getName().length() - 4);
            AdvancedCellData data = AdvancedCellData.loadByUuid(uuid);
            if (data != null && data.isEmpty()) {
                data.clearDirty();
                if (f.delete()) {
                    deleted++;
                }
            }
        }
        sender.sendMessage(new TextComponentString(
                "Cleaned " + deleted + " empty backend file(s) out of " + files.length + "."));
    }
}
