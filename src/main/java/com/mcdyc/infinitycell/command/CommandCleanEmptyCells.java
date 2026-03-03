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

public class CommandCleanEmptyCells extends CommandBase {

    @Override
    public String getName() {
        return "cleanemptycells";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/cleanemptycells";
    }

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
