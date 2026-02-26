package com.example.modid;

// 这里能引用的这个 Tags 是由于你整个项目跑在 RetroFuturaGradle 上，通过模板打包时自动生成的宏定义类
import com.example.modid.Tags;

// FML 核心注册标头，用来宣誓这是一个 Forge 模组
import net.minecraftforge.fml.common.Mod;
// 预初始化事件——加载主菜前的准备工作（用来读配置文件啥的）
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
// 正式初始化事件——大家都在这个阶段认祖归宗，做各种功能关联
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
// Forge 的事件监听注册注解，让这个类能够自动捕捉总线上发送出来的系统全服消息
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
// Forge 原生游戏注册表的刷新事件（比如把所有“物品”、“方块”、“药水”批量塞进原版的地方）
import net.minecraftforge.event.RegistryEvent;
// Minecraft 的基础物品类型类
import net.minecraft.item.Item;

// 日志库，用来在后台控制台瞎逼逼留记录用的
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// AE2 总入口管理
import appeng.api.AEApi;
// 自己辛辛苦苦重写的真正的出厂模块
import com.example.modid.item.AdvancedCellItem;
import com.example.modid.storage.AdvancedCellHandler;
import java.util.List;

/**
 * 模组的唯一主类 —— 在服务器/客户端启动时 FML 首先会抓取和通电激活这里
 */
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, dependencies = "required-after:appliedenergistics2")
// 告诉 Forge：这个主类里含有需要被事件总线 (EventBus) 自动监听的方法
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class ExampleMod {

    // 建立一个只属于我们自己的控制台日志打印播报员，这样报错的时候能清晰看到锅是出在这个名称前缀上
    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    // 利用工厂在启动期按需孵化所有 1K~INF 以及各气体流体的版本
    public static final List<AdvancedCellItem> ADVANCED_CELLS = AdvancedCellItem.createAllDisks();

    /**
     * Pre-Init 阶段。这时候世界还没创生。多用来做配置文件的数据载入动作。
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("无限 AE 模组: {} !", Tags.MOD_NAME);
    }

    /**
     * Init 阶段。在这个阶段，所有的其他大型模组（比如 AE2）的核心系统已经加载完毕浮出水面。
     * 只有现在，我们才能去敲门塞东西给它。
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 注册高级别泛用容量和气体拦截安检门
        AEApi.instance().registries().cell().addCellHandler(new AdvancedCellHandler());
        LOGGER.info("成功挂载了 Advanced 多阶梯硬盘存取拦截安检门！");
    }

    /**
     * 物品注册表列装阶段！在这个期间，Forge 发着大门禁宣告：“所有新模组的人现在可以把自己的物品加到世界的游戏白名单词典中了”
     */
    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        // 当门开了的时候，我们把在文件头上用工厂跑出来的所有型号（1k, 4k... inf的物品、流体、甚至是气体）硬盘一起抛到词典里
        for (AdvancedCellItem cell : ADVANCED_CELLS) {
            event.getRegistry().register(cell);
        }
    }

    /**
     * 模型注册阶段！在这里把咱们写好的 json 贴图文件强行绑定到每一个方块和物品身上
     */
    @SubscribeEvent
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    public static void registerModels(net.minecraftforge.client.event.ModelRegistryEvent event) {
        for (AdvancedCellItem cell : ADVANCED_CELLS) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                    cell, 0, 
                    new net.minecraft.client.renderer.block.model.ModelResourceLocation(cell.getRegistryName(), "inventory")
            );
        }
    }
}
