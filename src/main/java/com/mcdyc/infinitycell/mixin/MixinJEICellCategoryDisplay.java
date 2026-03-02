package com.mcdyc.infinitycell.mixin;

import appeng.api.storage.ICellInventory;
import appeng.api.storage.data.IAEStack;
import co.neeve.nae2.common.integration.jei.JEICellCategory;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcdyc.infinitycell.storage.AbstractAdvancedCellInventory;
import com.mcdyc.infinitycell.storage.InfiniteCellInventory;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mezz.jei.api.gui.ITooltipCallback;
import net.minecraft.client.resources.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.text.NumberFormat;
import java.util.List;

/**
 * 修复自定义磁盘在 NAE2 JEI Cellview 中的显示问题。
 *
 * 存储策略：InfiniteCell 使用 1 item = 1 byte 的内部计数。
 * NAE2 JEI tooltip 公式：getBytesPerType() + ceil(stackSize / unitsPerByte)
 * 对于物品通道 unitsPerByte=8，显示字节数偏小 8 倍。
 *
 * 修复方案：@Inject 拦截 getCallBack 的返回值，包装原始 ITooltipCallback，
 * 移除错误的 "used bytes" 行并替换为正确值（stackSize * 1）。
 * 不目标 lambda 合成方法，避免编号不确定问题。
 */
@Mixin(value = JEICellCategory.class, remap = false)
public abstract class MixinJEICellCategoryDisplay {

    /**
     * Shadow extendedStacks 以在包装 callback 中读取 stackSize。
     * 原始类型为 Int2ObjectOpenHashMap<ExtendedStackInfo<? extends IAEStack<?>>>，
     * 使用原始类型（无泛型）以绕过私有内部 record ExtendedStackInfo 的不可访问性。
     */
    @SuppressWarnings("rawtypes")
    @Shadow
    private Int2ObjectOpenHashMap extendedStacks;

    /**
     * 拦截 getCallBack 的返回值并对无限盘进行包装。
     *
     * CellInfo 是私有内部 record，无法直接 import，使用 Object + 反射获取 cellInv。
     * ExtendedStackInfo 同理，通过反射调用 stack() 方法获取 IAEStack。
     */
    @Inject(method = "getCallBack", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void wrapCallbackForInfiniteCell(
            @Coerce Object cellInfoRaw,
            CallbackInfoReturnable<ITooltipCallback<T>> cir
    ) {
        try {
            // CellInfo 是私有 record；反射调用 cellInv() 访问器
            Object cellInvRaw = cellInfoRaw.getClass().getMethod("cellInv").invoke(cellInfoRaw);
            if (!(cellInvRaw instanceof AbstractAdvancedCellInventory<?>)) return;

            final ICellInventory<?> cellInv = (ICellInventory<?>) cellInvRaw;
            final ITooltipCallback<T> original = cir.getReturnValue();

            cir.setReturnValue((int slotIndex, boolean input, T ingredient, List<String> tooltip) -> {
                int sizeBefore = tooltip.size();
                // 调用原始 callback，添加 "hover.stored" 和错误的 "used" 两行
                original.onTooltip(slotIndex, input, ingredient, tooltip);

                // 如果原始 callback 确实追加了内容，移除最后一行（错误的 used bytes）
                if (tooltip.size() > sizeBefore) {
                    tooltip.remove(tooltip.size() - 1);

                    // ExtendedStackInfo 是私有 record：反射调用 stack() 获取 IAEStack
                    Object info = extendedStacks.get(slotIndex);
                    if (info != null) {
                        try {
                            IAEStack<?> stack = (IAEStack<?>) info.getClass().getMethod("stack").invoke(info);
                            long stackSize = stack.getStackSize();
                            NumberFormat format = NumberFormat.getInstance();
                            // 1 item = 1 byte，stackSize 即字节数，无需除以 unitsPerByte
                            tooltip.add(I18n.format("nae2.jei.cellview.used",
                                    format.format(cellInv.getBytesPerType() + stackSize)));
                        } catch (Exception ignored) {
                            // 反射失败时不补充这一行，保持 hover.stored 行存在
                        }
                    }
                }
            });
        } catch (Exception e) {
            // cellInfo 反射失败，保持原始 callback 不变
        }
    }

    /**
     * 修复 drawExtras 中的容量条显示。
     * NAE2 计算 capacity = (getRemainingItemCount() + storedItemCount) / transferFactor。
     * 对无限盘让 remaining = DISPLAY_BYTES - stored，保持总和恒定。
     */
    @WrapOperation(
            method = "drawExtras",
            at = @At(value = "INVOKE", target = "Lappeng/api/storage/ICellInventory;getRemainingItemCount()J")
    )
    private long wrapGetRemainingItemCount(ICellInventory<?> instance, Operation<Long> original) {
        if (instance instanceof InfiniteCellInventory<?>) {
            long stored = instance.getStoredItemCount();
            long remaining = (Long.MAX_VALUE / 2) - stored;
            return remaining > 0 ? remaining : 0;
        }
        return original.call(instance);
    }

    /**
     * 确保无限磁盘的总字节容量在 drawExtras 中显示为 DISPLAY_BYTES。
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
