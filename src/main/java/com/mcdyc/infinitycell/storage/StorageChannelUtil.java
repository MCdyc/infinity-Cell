package com.mcdyc.infinitycell.storage;

import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import com.mcdyc.infinitycell.item.AdvancedCellItem;

/**
 * Shared channel math and type checks for custom cells.
 */
public final class StorageChannelUtil
{
    public static final String GAS_CHANNEL_CLASS = "com.mekeng.github.common.me.storage.IGasStorageChannel";

    private StorageChannelUtil()
    {
    }

    public static long unitsPerByte(IStorageChannel<?> channel)
    {
        if (channel == null) {
            return 1L;
        }
        return Math.max(1L, channel.getUnitsPerByte());
    }

    public static long bytesForAmount(IStorageChannel<?> channel, long amount)
    {
        if (amount <= 0) {
            return 0L;
        }
        long unitsPerByte = unitsPerByte(channel);
        long bytes = amount / unitsPerByte;
        return amount % unitsPerByte == 0L ? bytes : bytes + 1L;
    }

    public static long safeAdd(long left, long right)
    {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0 && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    public static long safeMultiply(long left, long right)
    {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    public static long safePositiveSubtract(long left, long right)
    {
        if (left <= 0) {
            return 0L;
        }
        if (right <= 0) {
            return left;
        }
        if (left <= right) {
            return 0L;
        }
        return left - right;
    }

    public static boolean isItemChannel(IStorageChannel<?> channel)
    {
        return channel instanceof IItemStorageChannel;
    }

    public static boolean isFluidChannel(IStorageChannel<?> channel)
    {
        return channel instanceof IFluidStorageChannel;
    }

    public static boolean isGasChannel(IStorageChannel<?> channel)
    {
        if (channel == null) {
            return false;
        }
        return implementsClassName(channel.getClass(), GAS_CHANNEL_CLASS);
    }

    public static boolean matchesCellType(AdvancedCellItem.StorageType type, IStorageChannel<?> channel)
    {
        if (type == AdvancedCellItem.StorageType.ITEM) {
            return isItemChannel(channel);
        }
        if (type == AdvancedCellItem.StorageType.FLUID) {
            return isFluidChannel(channel);
        }
        return isGasChannel(channel);
    }

    private static boolean implementsClassName(Class<?> type, String className)
    {
        if (type == null) {
            return false;
        }
        if (className.equals(type.getName())) {
            return true;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (implementsClassName(iface, className)) {
                return true;
            }
        }
        return implementsClassName(type.getSuperclass(), className);
    }
}
