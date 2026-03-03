package com.mcdyc.infinitycell.mixin;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

/**
 * 基于 MixinBooter 提供的晚期注入装载器。
 * 允许在 Minecraft 和其他大型 Mod (如 Forge 本身及 AE2) 大部分类加载完毕后，再将我们的 Mixin 代码注入进去。
 */
public class MixinLoader implements ILateMixinLoader {

    /**
     * 指定本模组所需的 Mixin 配置文件名。
     * Mixin 框架将根据该 json 内定好的规则实施注入拦截。
     *
     * @return 包含 Mixin 配置文件名字符串的列表。
     */
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.infinitycell.json");
    }
}
