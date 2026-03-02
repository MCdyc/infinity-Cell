package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.ICellInventory;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import co.neeve.nae2.common.integration.jei.JEICellCategory;
import com.mcdyc.infinitycell.storage.InfiniteCellInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复自定义磁盘在 NAE2 JEI Cellview 中的显示问题
 *
 * 存储策略说明：
 * - InfiniteCell: 1 物品 = 1 byte, 1 mB = 1 byte (容量无限)
 *
 * AE2 unitsPerByte:
 * - 物品通道: 8
 * - 流体通道: 8000 mB
 */
@Mixin(value = JEICellCategory.class, remap = false)
public class MixinJEICellCategoryDisplay {

    /**
     * Bug #2 修复: getBytesPerType() 返回 0 导致计算错误
     * 保持原样，让 NAE2 根据通道类型自动计算
     */
    @WrapOperation(
            method = "getCallBack",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getBytesPerType()I")
    )
    private int wrapGetBytesPerTypeForTooltip(ICellInventory<?> instance, Operation<Integer> original) {
        return original.call(instance);
    }

    /**
     * Bug #2 修复: getBytesPerType() 在 drawExtras 中的调用
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getBytesPerType()I")
    )
    private int wrapGetBytesPerTypeForDraw(ICellInventory<?> instance, Operation<Integer> original) {
        return original.call(instance);
    }

    /**
     * Bug #3 修复: InfiniteCell 容量显示为无限且固定
     *
     * 问题：NAE2 容量计算: capacity = (getRemainingItemCount() + storedItemCount) / transferFactor
     * 如果 getRemainingItemCount() 返回固定值，则容量会随着 storedItemCount 增加而增加
     *
     * 解决方案：修改 getRemainingItemCount() 返回值，使其减去存储的数量
     * 这样 (getRemainingItemCount() + storedItemCount) 保持恒定
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getRemainingItemCount()J")
    )
    private long wrapGetRemainingItemCount(ICellInventory<?> instance, Operation<Long> original) {
        if (instance instanceof InfiniteCellInventory<?>) {
            // 返回超大值减去已存储的数量，使得总和保持恒定
            // capacity = ((Long.MAX_VALUE / 2 - stored) + stored) / transferFactor = Long.MAX_VALUE / 2 / transferFactor
            long stored = instance.getStoredItemCount();
            // 防止溢出
            long remaining = (Long.MAX_VALUE / 2) - stored;
            return remaining > 0 ? remaining : 0;
        }
        return original.call(instance);
    }

    /**
     * 额外修复: 确保无限磁盘的总字节容量显示为无限
     */
    @ModifyExpressionValue(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getTotalBytes()J")
    )
    private long modifyGetTotalBytes(long original, ICellInventory<?> cellInv) {
        if (cellInv instanceof InfiniteCellInventory<?>) {
            return Long.MAX_VALUE / 2;
        }
        return original;
    }
}
