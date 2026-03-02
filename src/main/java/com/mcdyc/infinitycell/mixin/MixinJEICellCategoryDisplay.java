package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.ICellInventory;
import com.llamalad7.mixinextras.injector.ModifyVariable;
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
     * 解决方案：直接修改 capacity 变量，使其始终返回固定无限值
     */
    @ModifyVariable(
            method = "drawExtras",
            at = @At(value = "STORE", ordinal = 0)
    )
    private long modifyCapacity(long capacity) {
        // 通过反射检查 cellInfo 是否为无限磁盘
        // 注意：这里需要访问 JEICellCategory 的私有字段 cellInfo
        if (this instanceof JEICellCategory) {
            try {
                java.lang.reflect.Field cellInfoField = JEICellCategory.class.getDeclaredField("cellInfo");
                cellInfoField.setAccessible(true);
                Object cellInfo = cellInfoField.get(this);
                if (cellInfo != null) {
                    java.lang.reflect.Field cellInvField = cellInfo.getClass().getDeclaredField("cellInv");
                    cellInvField.setAccessible(true);
                    ICellInventory<?> cellInv = (ICellInventory<?>) cellInvField.get(cellInfo);
                    if (cellInv instanceof InfiniteCellInventory<?>) {
                        // 返回固定无限容量，不随存储物品增加而变化
                        return Long.MAX_VALUE / 2;
                    }
                }
            } catch (Exception e) {
                // 忽略异常，返回原始值
            }
        }
        return capacity;
    }
}
