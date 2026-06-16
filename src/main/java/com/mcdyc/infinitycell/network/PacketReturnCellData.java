package com.mcdyc.infinitycell.network;

import appeng.api.AEApi;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import com.mcdyc.infinitycell.item.AdvancedCellItem;
import com.mcdyc.infinitycell.storage.AdvancedCellData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器返回元件数据的包
 */
public class PacketReturnCellData implements IMessage {

    private static final int MAX_RETURNED_STACKS = 63;

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
     * 在服务端创建响应包并发送。
     *
     * <p>采用"后端优先"策略：由于客户端只在带 disk_uuid 时才发请求，
     * 服务端只需按 UUID 直接读后端 MapStorage，无需在玩家背包里找 ItemStack。
     * 这样无论盘在背包、ME 驱动器、IO 端口还是网络中都能正确回包，杜绝预览永久转圈。
     *
     * <p>核心不变量：带 UUID 的请求<b>永不静默 return null</b>，必发包（哪怕是空盘的空响应）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static IMessage createResponse(ItemStack cellStack, int maxItems, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().player;

        if (cellStack == null || cellStack.isEmpty()
                || !(cellStack.getItem() instanceof AdvancedCellItem)) {
            return null;
        }
        AdvancedCellItem cellItem = (AdvancedCellItem) cellStack.getItem();

        IStorageChannel<?> channel = cellItem.getChannel();
        if (channel == null) {
            // 通道不可用（如缺 mekeng 的气体盘）：发空响应，避免客户端无限转圈
            sendResponse(player, cellStack, new ArrayList<>(), 0L, 0L, 0L, totalBytesForCell(cellItem));
            return null;
        }

        int cappedMaxItems = Math.max(0, Math.min(maxItems, MAX_RETURNED_STACKS));
        long totalBytes = totalBytesForCell(cellItem);

        // ---- 主路径：按 UUID 直接读后端 ----
        String uuid = getDiskUuid(cellStack);
        if (uuid != null) {
            AdvancedCellData data = AdvancedCellData.loadByUuid(uuid);
            if (data != null) {
                AdvancedCellData.ChannelData cd = data.getChannelData((IStorageChannel) channel);
                List<IAEStack<?>> storedStacks = new ArrayList<>();
                for (Object s : cd.collectFirstN(cappedMaxItems)) {
                    if (s instanceof IAEStack) storedStacks.add((IAEStack<?>) s);
                }
                sendResponse(player, cellStack, storedStacks,
                        cd.getDisplayItemCount(), cd.typeCount(), cd.getDisplayBytes(), totalBytes);
            } else {
                // 空盘无文件：发空响应（关键——消除空盘无限转圈）
                sendResponse(player, cellStack, new ArrayList<>(), 0L, 0L, 0L, totalBytes);
            }
            return null;
        }

        // ---- 兜底路径：无 UUID（当前不可达，requestData 已按 UUID 门控；保留以防万一）----
        ItemStack matched = findMatchingPlayerStack(player, cellStack);
        List<IAEStack<?>> storedStacks = new ArrayList<>();
        long storedItemCount = 0L, storedItemTypes = 0L, usedBytes = 0L;
        if (!matched.isEmpty() && matched.getItem() instanceof IStorageCell<?>) {
            appeng.api.storage.ICellHandler handler = AEApi.instance().registries().cell().getHandler(matched);
            if (handler != null) {
                appeng.api.storage.IMEInventoryHandler<?> inventory =
                        handler.getCellInventory(matched, null, (IStorageChannel) channel);
                if (inventory instanceof ICellInventoryHandler) {
                    ICellInventory<?> cellInv = ((ICellInventoryHandler<?>) inventory).getCellInv();
                    if (cellInv != null) {
                        IItemList availableItems = channel.createList();
                        ((ICellInventory) cellInv).getAvailableItems(availableItems);
                        int collected = 0;
                        for (Object s : availableItems) {
                            if (collected >= cappedMaxItems) break;
                            if (s instanceof IAEStack) {
                                storedStacks.add((IAEStack<?>) s);
                                collected++;
                            }
                        }
                        storedItemCount = cellInv.getStoredItemCount();
                        storedItemTypes = cellInv.getStoredItemTypes();
                        usedBytes = cellInv.getUsedBytes();
                        totalBytes = cellInv.getTotalBytes();
                    }
                }
            }
        }
        // 无论是否命中，都发包（避免客户端无限转圈）
        sendResponse(player, cellStack, storedStacks, storedItemCount, storedItemTypes, usedBytes, totalBytes);
        return null;
    }

    /**
     * 组装并发送响应包给指定玩家。
     */
    private static void sendResponse(EntityPlayerMP player, ItemStack cellStack, List<IAEStack<?>> storedStacks,
                                     long storedItemCount, long storedItemTypes, long usedBytes, long totalBytes) {
        if (player == null) return;
        PacketReturnCellData response = new PacketReturnCellData(
                cellStack, storedStacks, storedItemCount, storedItemTypes, usedBytes, totalBytes);
        PacketHandler.INSTANCE.sendTo(response, player);
    }

    /**
     * 从元件物品本身推算总容量字节数，无需实例化 inventory。
     * - 无限盘 (INF)：{@code Long.MAX_VALUE / 2}（对齐 InfiniteCellInventory，客户端据此渲染 "Inf"）。
     * - 有限盘：{@code tier.kb * 1024}（对齐 AdvancedCellInventory，与通道类型无关）。
     */
    private static long totalBytesForCell(AdvancedCellItem cellItem) {
        if (cellItem.tier == AdvancedCellItem.StorageTier.INF) {
            return Long.MAX_VALUE / 2;
        }
        return cellItem.tier.kb * 1024L;
    }

    private static ItemStack findMatchingPlayerStack(EntityPlayerMP player, ItemStack requestedStack) {
        if (player == null || requestedStack == null || requestedStack.isEmpty()
                || !(requestedStack.getItem() instanceof IStorageCell<?>)) {
            return ItemStack.EMPTY;
        }

        String requestedUuid = getDiskUuid(requestedStack);
        for (ItemStack stack : player.inventory.mainInventory) {
            if (matchesRequestedCell(stack, requestedStack, requestedUuid)) {
                return stack;
            }
        }
        for (ItemStack stack : player.inventory.offHandInventory) {
            if (matchesRequestedCell(stack, requestedStack, requestedUuid)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean matchesRequestedCell(ItemStack stack, ItemStack requestedStack, String requestedUuid) {
        if (stack == null || stack.isEmpty() || stack.getItem() != requestedStack.getItem()) {
            return false;
        }

        if (requestedUuid != null) {
            return requestedUuid.equals(getDiskUuid(stack));
        }

        return getDiskUuid(stack) == null;
    }

    private static String getDiskUuid(ItemStack stack) {
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey("disk_uuid")) {
            return stack.getTagCompound().getString("disk_uuid");
        }
        return null;
    }

    public static class Handler implements IMessageHandler<PacketReturnCellData, IMessage> {
        @Override
        public IMessage onMessage(PacketReturnCellData message, MessageContext ctx) {
            FMLCommonHandler.instance().getWorldThread(ctx.netHandler).addScheduledTask(() -> {
                // 将数据存入客户端缓存
                CellDataCache.getInstance().updateCache(message);
            });
            return null;
        }
    }
}
