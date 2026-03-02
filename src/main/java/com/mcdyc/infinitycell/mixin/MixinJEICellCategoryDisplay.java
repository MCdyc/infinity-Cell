package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.ICellInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import co.neeve.nae2.common.integration.jei.JEICellCategory;
import com.mcdyc.infinitycell.storage.InfiniteCellInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复无限磁盘在 NAE2 JEI Cellview 中的显示问题
 */
@Mixin(value = JEICellCategory.class, remap = false)
public class MixinJEICellCategoryDisplay {

    /**
     * Bug #2 修复: getBytesPerType() 返回 0 导致计算错误
     * 在计算单个物品占用的字节数时，返回一个合理的显示值
     * 注意：这里返回的值不影响实际存储，仅用于 JEI 界面显示
     */
    @WrapOperation(
            method = "getCallBack",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getBytesPerType()I")
    )
    private int wrapGetBytesPerTypeForTooltip(ICellInventory<?> instance, Operation<Integer> original) {
        // 如果是无限磁盘，返回 8（类似普通磁盘的索引占用，用于显示）
        // 这个值仅用于 JEI tooltip 显示，不影响实际存储
        if (instance instanceof InfiniteCellInventory<?>) {
            return 8;
        }
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
        if (instance instanceof InfiniteCellInventory<?>) {
            return 8;
        }
        return original.call(instance);
    }

    /**
     * Bug #3 修复: getRemainingItemCount() 返回固定值导致容量显示不准确
     * 在计算容量时，返回一个基于当前存储的合理值用于显示
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getRemainingItemCount()J")
    )
    private long wrapGetRemainingItemCount(ICellInventory<?> instance, Operation<Long> original) {
        if (instance instanceof InfiniteCellInventory<?>) {
            // 返回当前存储的物品数量作为"剩余容量"用于显示
            // 这样容量 = (remaining + stored) / transferFactor = 2 * stored / transferFactor
            // 显示为"已存储 / 容量"会看起来比较合理
            return instance.getStoredItemCount();
        }
        return original.call(instance);
    }
}
