package com.mcdyc.infinitycell.network;

import appeng.api.AEApi;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 服务器返回元件数据的包
 */
public class PacketReturnCellData implements IMessage {

    private ItemStack cellStack;
    private List<IAEStack<?>> storedStacks;
    private long storedItemCount;
    private long storedItemTypes;
    private long usedBytes;
    private long totalBytes;

    public PacketReturnCellData() {
    }

    public PacketReturnCellData(ItemStack cellStack, List<IAEStack<?>> storedStacks,
                                 long storedItemCount, long storedItemTypes,
                                 long usedBytes, long totalBytes) {
        this.cellStack = cellStack.copy();
        this.storedStacks = storedStacks;
        this.storedItemCount = storedItemCount;
        this.storedItemTypes = storedItemTypes;
        this.usedBytes = usedBytes;
        this.totalBytes = totalBytes;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.cellStack = ByteBufUtils.readItemStack(buf);
        this.storedItemCount = buf.readLong();
        this.storedItemTypes = buf.readLong();
        this.usedBytes = buf.readLong();
        this.totalBytes = buf.readLong();

        // 读取物品列表
        this.storedStacks = new ArrayList<>();
        NBTTagCompound tag = ByteBufUtils.readTag(buf);
        if (tag != null) {
            NBTTagList itemList = tag.getTagList("Items", 10);
            String channelClassName = tag.getString("ChannelClass");

            // 获取存储通道
            IStorageChannel<?> channel = null;
            for (IStorageChannel<?> ch : AEApi.instance().storage().storageChannels()) {
                if (ch.getClass().getName().equals(channelClassName)) {
                    channel = ch;
                    break;
                }
            }

            if (channel != null) {
                for (int i = 0; i < itemList.tagCount(); i++) {
                    NBTTagCompound itemTag = itemList.getCompoundTagAt(i);
                    IAEStack<?> stack = channel.createFromNBT(itemTag);
                    if (stack != null) {
                        storedStacks.add(stack);
                    }
                }
            } else {
                // null channel for this name, skip
            }
        } else {
            // tag is null, skip
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeItemStack(buf, this.cellStack);
        buf.writeLong(this.storedItemCount);
        buf.writeLong(this.storedItemTypes);
        buf.writeLong(this.usedBytes);
        buf.writeLong(this.totalBytes);

        // 写入物品列表
        NBTTagCompound tag = new NBTTagCompound();
        if (!storedStacks.isEmpty()) {
            // 获取通道类名
            IStorageChannel<?> channel = getChannelFromStack(storedStacks.get(0));
            if (channel != null) {
                tag.setString("ChannelClass", channel.getClass().getName());
            }

            NBTTagList itemList = new NBTTagList();
            for (IAEStack<?> stack : storedStacks) {
                NBTTagCompound itemTag = new NBTTagCompound();
                stack.writeToNBT(itemTag);
                itemList.appendTag(itemTag);
            }
            tag.setTag("Items", itemList);
        }
        ByteBufUtils.writeTag(buf, tag);
    }

    @SuppressWarnings("unchecked")
    private IStorageChannel<?> getChannelFromStack(IAEStack<?> stack) {
        if (stack == null) return null;
        for (IStorageChannel<?> ch : AEApi.instance().storage().storageChannels()) {
            if (stack.isItem() && ch instanceof appeng.api.storage.channels.IItemStorageChannel) {
                return ch;
            } else if (stack.isFluid() && ch instanceof appeng.api.storage.channels.IFluidStorageChannel) {
                return ch;
            } else if (!stack.isItem() && !stack.isFluid()
                    && !(ch instanceof appeng.api.storage.channels.IItemStorageChannel)
                    && !(ch instanceof appeng.api.storage.channels.IFluidStorageChannel)) {
                // 气体或其他自定义类型：通过排除法匹配
                return ch;
            }
        }
        return null;
    }

    public ItemStack getCellStack() {
        return cellStack;
    }

    public List<IAEStack<?>> getStoredStacks() {
        return storedStacks;
    }

    public long getStoredItemCount() {
        return storedItemCount;
    }

    public long getStoredItemTypes() {
        return storedItemTypes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * 在服务端创建响应包并发送
     */
    public static IMessage createResponse(ItemStack cellStack, int maxItems, MessageContext ctx) {
        if (!(cellStack.getItem() instanceof IStorageCell<?>)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        IStorageCell<?> storageCell = (IStorageCell<?>) cellStack.getItem();

        appeng.api.storage.ICellHandler handler = AEApi.instance().registries().cell().getHandler(cellStack);
        if (handler == null) return null;

        IStorageChannel<?> channel = storageCell.getChannel();
        appeng.api.storage.IMEInventoryHandler<?> inventory = handler.getCellInventory(cellStack, null, (IStorageChannel) channel);
        if (inventory == null) return null;

        if (!(inventory instanceof ICellInventoryHandler)) return null;

        ICellInventory<?> cellInv = ((ICellInventoryHandler<?>) inventory).getCellInv();
        if (cellInv == null) return null;

        // 获取物品列表
        IItemList availableItems = channel.createList();
        ((ICellInventory) cellInv).getAvailableItems(availableItems);

        List<IAEStack<?>> storedStacks = new ArrayList<>();
        for (Object s : availableItems) {
            if (s instanceof IAEStack) {
                storedStacks.add((IAEStack<?>) s);
            }
        }

        // 按数量排序
        storedStacks.sort((a, b) -> Long.compare(b.getStackSize(), a.getStackSize()));

        // 限制数量
        if (maxItems > 0 && storedStacks.size() > maxItems) {
            storedStacks = new ArrayList<>(storedStacks.subList(0, maxItems));
        }

        PacketReturnCellData response = new PacketReturnCellData(
                cellStack,
                storedStacks,
                cellInv.getStoredItemCount(),
                cellInv.getStoredItemTypes(),
                cellInv.getUsedBytes(),
                cellInv.getTotalBytes()
        );

        PacketHandler.INSTANCE.sendTo(response, ctx.getServerHandler().player);
        return null;
    }

    public static class Handler implements IMessageHandler<PacketReturnCellData, IMessage> {
        @Override
        public IMessage onMessage(PacketReturnCellData message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                // 将数据存入客户端缓存
                CellDataCache.getInstance().updateCache(message);
            });
            return null;
        }
    }
}
