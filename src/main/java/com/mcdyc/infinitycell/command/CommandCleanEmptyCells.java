package com.mcdyc.infinitycell.command;

import com.mcdyc.infinitycell.storage.AdvancedCellData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import java.io.File;

/**
 * 自定义控制台/聊天框指令：清理无限存储盘生成的空白数据文件。
 * 当玩家创建存储元件但未实际存放物品并遗弃时，可能产生带有 UUID 的空文件，
 * 这个指令可以遍历全局存储并删掉这些空文件，以节省磁盘空间和减少加载负担。
 */
public class CommandCleanEmptyCells extends CommandBase {

    /**
     * 获取指令的首选项名称，例如输入 /cleanemptycells 时触发。
     *
     * @return 触发该指令的字符串名称。
     */
    @Override
    public String getName() {
        return "cleanemptycells";
    }

    /**
     * 获取指令的用法提示说明。
     * 当玩家输入 /help cleanemptycells 或格式错误时显示的文本。
     *
     * @param sender 能够发送该指令的实体或后台控制台。
     * @return 描述如何使用该指令的字符串。
     */
    @Override
    public String getUsage(ICommandSender sender) {
        return "/cleanemptycells";
    }

    /**
     * 指令被调用时执行的具体逻辑。
     * 遍历服务器所有的 "data/infinite" 目录文件，删除那些判定为内容物为空的 .dat 后端数据。
     *
     * @param server 当前运行此命令的 Minecraft 服务器实例。
     * @param sender 发起该命令的发送者（玩家、命令方块等）。
     * @param args   附带在指令主名称后的空格分隔参数列表。
     * @throws CommandException 当指令执行遇到预期外的错误条件时抛出异常。
     */
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        World overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            sender.sendMessage(new TextComponentString("Error: Overworld not loaded."));
            return;
        }

        File infiniteDir = new File(overworld.getSaveHandler().getWorldDirectory(), "data/infinite");
        if (!infiniteDir.exists() || !infiniteDir.isDirectory()) {
            sender.sendMessage(new TextComponentString("No infinite cell data found."));
            return;
        }

        File[] files = infiniteDir.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            sender.sendMessage(new TextComponentString("No infinite cell data files found."));
            return;
        }

        int deletedCount = 0;
        int totalCount = files.length;

        for (File file : files) {
            if (!file.isFile()) continue;

            String fileName = file.getName();
            String uuid = fileName.substring(0, fileName.length() - 4); // Remove .dat
            String dataKey = "infinite/" + uuid;

            AdvancedCellData storageData = (AdvancedCellData) overworld.getMapStorage()
                    .getOrLoadData(AdvancedCellData.class, dataKey);

            if (storageData != null && storageData.isEmpty()) {
                if (file.delete()) {
                    deletedCount++;
                }
            }
        }

        sender.sendMessage(new TextComponentString("Cleaned up " + deletedCount + " out of " + totalCount + " cell data files."));
    }
}
