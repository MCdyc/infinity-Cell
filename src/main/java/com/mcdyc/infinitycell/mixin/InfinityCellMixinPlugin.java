package com.mcdyc.infinitycell.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mixin 配置插件：根据可选依赖是否存在来决定是否加载对应的 Mixin 类。
 * 避免在 NAE2 未安装时因类找不到而崩溃。
 */
public class InfinityCellMixinPlugin implements IMixinConfigPlugin {

    private static boolean isClassPresent(String className) {
        // 使用 getResource 检查类文件是否存在，不会触发类加载
        String classPath = className.replace('.', '/') + ".class";
        return InfinityCellMixinPlugin.class.getClassLoader().getResource(classPath) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // 仅当 NAE2 存在时才加载 NAE2 相关的 Mixin（不能用 Class.forName，会导致目标类被提前加载）
        if (isClassPresent("co.neeve.nae2.common.integration.jei.NAEJEIPlugin")) {
            mixins.add("MixinNAEJEIPlugin");
        }
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
