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
// 你自己辛辛苦苦刚写好的两个核心盘组件封装，分别对应物品壳和系统的遥感器
import com.example.modid.item.ItemInfiniteCell;
import com.example.modid.storage.InfiniteCellHandler;

/**
 * 模组的唯一主类 —— 在服务器/客户端启动时 FML 首先会抓取和通电激活这里
 */
@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, dependencies = "required-after:appliedenergistics2")
// 告诉 Forge：这个主类里含有需要被事件总线 (EventBus) 自动监听的方法
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class ExampleMod {

    // 建立一个只属于我们自己的控制台日志打印播报员，这样报错的时候能清晰看到锅是出在这个名称前缀上
    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    // 我们在这个类的最头上，提前将我们的那块没有任何功能的物品盘造出来，准备以后塞进注册表里
    public static final Item ITEM_INFINITE_CELL = new ItemInfiniteCell();

    /**
     * Pre-Init 阶段。这时候世界还没创生。多用来做配置文件的数据载入动作。
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("你好，来自牛逼挂机无限版 AE 模组: {} !", Tags.MOD_NAME);
    }

    /**
     * Init 阶段。在这个阶段，所有的其他大型模组（比如 AE2）的核心系统已经加载完毕浮出水面。
     * 只有现在，我们才能去敲门塞东西给它。
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 致残打击开始：我们将自己制作的非法磁盘遥感器 (CellHandler)，作为光荣的叛徒直接注册并且插进 AE2 的原生判断大楼中。
        // 从这行代码生效起，以后任何盘在插进主世界的一瞬间，AE2 都会被迫先向我们的代码请求这到底是不是我们的盘。
        AEApi.instance().registries().cell().addCellHandler(new InfiniteCellHandler());
        LOGGER.info("成功强行挂载了无限大背包拦截安检门！");
    }

    /**
     * 物品注册表列装阶段！在这个期间，Forge 发着大门禁宣告：“所有新模组的人现在可以把自己的物品加到世界的游戏白名单词典中了”
     */
    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        // 当门开了的时候，我们把在文件头上准备好的这个 "infinite_cell" 扔给锻造局（Forge Registry）。
        // 自此之后，游戏里用指令 /give @p XXX 或者在 JEI 里面都可以堂堂正正搜出这块盘的名字材质啦！
        event.getRegistry().register(ITEM_INFINITE_CELL);
    }
}
