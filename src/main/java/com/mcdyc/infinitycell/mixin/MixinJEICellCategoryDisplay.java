package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.ICellInventory;
import appeng.api.storage.IStorageChannel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import co.neeve.nae2.common.integration.jei.JEICellCategory;
import com.mcdyc.infinitycell.storage.AdvancedCellInventory;
import com.mcdyc.infinitycell.storage.InfiniteCellInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复自定义磁盘在 NAE2 JEI Cellview 中的显示问题
 *
 * 存储策略说明：
 * - InfiniteCell: 1 物品 = 1 byte, 1 mB = 1 byte (容量无限)
 * - AdvancedCell: 1 物品 = 1 byte, 1000 mB (1桶) = 1 byte (有限容量)
 *
 * AE2 unitsPerByte:
 * - 物品通道: 8
 * - 流体通道: 8000 mB
 */
@Mixin(value = JEICellCategory.class, remap = false)
public class MixinJEICellCategoryDisplay {

    /**
     * Bug #2 修复: getBytesPerType() 返回 0 导致计算错误
     *
     * NAE2 计算公式: getBytesPerType() + Math.ceil(stackSize / unitsPerByte)
     * - 物品: unitsPerByte = 8
     * - 流体: unitsPerByte = 8000 mB
     *
     * InfiniteCell 存储策略: 1 item = 1 byte
     * 返回 0，让 Math.ceil(stackSize / unitsPerByte) 自动计算正确值
     * (对于显示来说，这个值仅作参考，不影响实际存储)
     */
    @WrapOperation(
            method = "getCallBack",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getBytesPerType()I")
    )
    private int wrapGetBytesPerTypeForTooltip(ICellInventory<?> instance, Operation<Integer> original) {
        // InfiniteCell 和 AdvancedCell 都返回 0，保持原样
        // NAE2 的计算公式会根据 unitsPerByte 自动计算
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
     * Bug #3 修复: InfiniteCell 容量显示为无限
     *
     * NAE2 容量计算公式: (getRemainingItemCount() + storedItemCount) / transferFactor
     *
     * 返回超大值，使容量 ≈ 无限
     * 显示为 "Stored: X / ∞"
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getRemainingItemCount()J")
    )
    private long wrapGetRemainingItemCount(ICellInventory<?> instance, Operation<Long> original) {
        if (instance instanceof InfiniteCellInventory<?>) {
            // 返回超大值，使容量显示接近无限
            return Long.MAX_VALUE / 4;
        }
        return original.call(instance);
    }

    /**
     * 额外修复: InfiniteCell 的总字节容量显示
     * 返回超大值，使总容量显示为接近无限
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getTotalBytes()J")
    )
    private long wrapGetTotalBytes(ICellInventory<?> instance, Operation<Long> original) {
        if (instance instanceof InfiniteCellInventory<?>) {
            // InfiniteCell 显示为无限
            return Long.MAX_VALUE / 4;
        }
        return original.call(instance);
    }
}
