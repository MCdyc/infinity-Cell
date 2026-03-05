package com.mcdyc.infinitycell.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端发送请求元件数据的包
 */
public class PacketRequestCellData implements IMessage {

    private ItemStack cellStack;
    private int maxItems; // 请求的最大物品数量，用于限制返回数据大小

    public PacketRequestCellData() {
    }

    public PacketRequestCellData(ItemStack cellStack, int maxItems) {
        this.cellStack = cellStack.copy();
        this.maxItems = maxItems;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.cellStack = ByteBufUtils.readItemStack(buf);
        this.maxItems = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeItemStack(buf, this.cellStack);
        buf.writeInt(this.maxItems);
    }

    public ItemStack getCellStack() {
        return cellStack;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public static class Handler implements IMessageHandler<PacketRequestCellData, IMessage> {
        @Override
        public IMessage onMessage(PacketRequestCellData message, MessageContext ctx) {
            FMLCommonHandler.instance().getWorldThread(ctx.netHandler).addScheduledTask(() -> handle(message, ctx));
            return null;
        }

        private void handle(PacketRequestCellData message, MessageContext ctx) {
            // 委托给 PacketReturnCellData 处理（它会自己发送响应）
            PacketReturnCellData.createResponse(message.getCellStack(), message.getMaxItems(), ctx);
        }
    }
}
