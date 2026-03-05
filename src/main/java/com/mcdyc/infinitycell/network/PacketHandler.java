package com.mcdyc.infinitycell.network;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class PacketHandler {
    public static SimpleNetworkWrapper INSTANCE;
    private static int ID = 0;

    public static int nextID() {
        return ID++;
    }

    public static void registerMessages(String channelName) {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(channelName);

        // Server side - 处理客户端请求
        INSTANCE.registerMessage(PacketRequestCellData.Handler.class, PacketRequestCellData.class, nextID(), Side.SERVER);

        // Client side - 处理服务器响应
        INSTANCE.registerMessage(PacketReturnCellData.Handler.class, PacketReturnCellData.class, nextID(), Side.CLIENT);
    }
}
