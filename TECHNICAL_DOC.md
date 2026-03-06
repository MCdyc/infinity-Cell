# Infinity Cell 模组技术文档

## 一、项目概述

**Infinity Cell** 是一个 Minecraft 1.12.2 的 AE2 (Applied Energistics 2) 扩展模组，提供**高性能、多阶梯容量的存储元件**，支持物品、流体、气体三种存储类型。

### 核心特性
- **9 种容量阶梯**：1K → 4K → 16K → 64K → 256K → 1024K → 4096K → 16384K → INFINITE
- **3 种存储类型**：物品(Item)、流体(Fluid)、气体(Gas) - 依赖 mekeng 模组
- **高性能存储后端**：使用 FastUtil 替代 AE2 原生存储
- **零耗电设计**：所有元件 idleDrain = 0
- **可分离/重组**：Shift 右键空盘可拆分为外壳+组件

---

## 二、项目结构

```
src/main/java/com/mcdyc/infinitycell/
├── InfinityCell.java              # 主类：模组入口、注册中心
├── item/
│   ├── AdvancedCellItem.java      # 存储元件物品（核心）
│   ├── AdvancedCellHousingItem.java   # 高级外壳
│   ├── InfiniteComponentItem.java     # 无限组件（用于合成无限盘）
│   └── DebugInjectorItem.java     # 调试工具（压力测试）
├── storage/
│   ├── AdvancedCellHandler.java   # AE2 CellHandler 拦截器
│   ├── AdvancedCellData.java      # 数据持久化层（WorldSavedData）
│   ├── AbstractAdvancedCellInventory.java  # 存储抽象基类
│   ├── AdvancedCellInventory.java # 有限容量盘实现
│   └── InfiniteCellInventory.java # 无限容量盘实现
└── mixin/
    ├── MixinLoader.java           # Mixin 加载器
    ├── MixinJEICellCategory.java  # JEI 排序溢出修复
    └── MixinJEICellCategoryDisplay.java  # JEI 显示修复
```

---

## 三、核心实现详解

### 3.1 主类 `InfinityCell.java`

**职责**：模组生命周期管理、物品/模型/配方注册

```java
@Mod(modid = Tags.MOD_ID, dependencies = "required-after:appliedenergistics2;after:mixinbooter")
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class InfinityCell {
    // 静态初始化所有磁盘物品
    public static final List<AdvancedCellItem> ADVANCED_CELLS = AdvancedCellItem.createAllDisks();
}
```

**关键流程**：

| 阶段     | 方法                | 功能                                                      |
| -------- | ------------------- | --------------------------------------------------------- |
| Pre-Init | `preInit()`         | 日志输出                                                  |
| Init     | `init()`            | **反射注入** AdvancedCellHandler 到 AE2 CellRegistry 首位 |
| Register | `registerItems()`   | 注册所有磁盘、外壳、无限组件                              |
| Register | `registerModels()`  | 绑定物品模型                                              |
| Register | `registerRecipes()` | 注册无序合成配方（外壳 + 原版组件 = 高级盘）              |

**反射注入技巧**：
```java
// 将 Handler 插入列表首位，确保优先于 AE2 原生 Handler
for (Field field : cellRegistry.getClass().getDeclaredFields()) {
    if (List.class.isAssignableFrom(field.getType())) {
        field.setAccessible(true);
        List<ICellHandler> handlers = (List<ICellHandler>) field.get(cellRegistry);
        handlers.add(0, new AdvancedCellHandler()); // 插入首位！
        break;
    }
}
```

---

### 3.2 存储元件物品 `AdvancedCellItem.java`

**职责**：定义存储元件物品，实现 `IStorageCell` 接口

#### 容量阶梯枚举
```java
public enum StorageTier {
    T_1K(1), T_4K(4), T_16K(16), T_64K(64),
    T_256K(256), T_1024K(1024), T_4096K(4096), T_16384K(16384),
    INF(-1);  // -1 表示无限
}
```

#### 懒分配 UUID 机制
```java
@Override
public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
    if (!world.isRemote && stack.getTagCompound() == null) {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("disk_uuid", UUID.randomUUID().toString());
        stack.setTagCompound(nbt);
    }
}
```
**目的**：创造模式物品栏的物品不会提前分配 UUID，只有进入玩家背包才分配。

#### Shift 右键分离逻辑
```java
if (playerIn.isSneaking() && cellInv.getUsedBytes() == 0) {
    // 1. 移除硬盘
    playerIn.setHeldItem(handIn, ItemStack.EMPTY);
    // 2. 返还外壳和组件
    playerIn.inventory.addItemStackToInventory(new ItemStack(housing));
    playerIn.inventory.addItemStackToInventory(getOriginalComponent());
    // 3. 删除 UUID 数据文件
    new File("data/infinite/" + uuid + ".dat").delete();
}
```

#### 组件映射表
| 阶梯        | 物品组件                  | 流体组件                  | 气体组件                  |
| ----------- | ------------------------- | ------------------------- | ------------------------- |
| 1K-64K      | AE2 material (meta 35-38) | AE2 material (meta 54-57) | mekeng gas_core_Xk        |
| 256K-16384K | NAE2 material (meta 1-4)  | NAE2 material (meta 5-8)  | NAE2 material (meta 9-12) |
| INF         | infinite_component_item   | infinite_component_fluid  | infinite_component_gas    |

---

### 3.3 存储系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    AdvancedCellHandler                       │
│  (AE2 ICellHandler 实现，拦截并路由到正确的 Inventory)        │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────┴───────────────┐
           │                               │
           ▼                               ▼
┌─────────────────────┐         ┌─────────────────────┐
│ AdvancedCellInventory│         │ InfiniteCellInventory│
│   (有限容量盘)        │         │   (无限容量盘)        │
│                     │         │                     │
│ • 容量上限检查       │         │ • 无容量检查          │
│ • 字节换算          │         │ • 1 item = 1 byte    │
│ • 状态灯(绿/橙/红)   │         │ • 状态灯(蓝/绿)       │
└──────────┬──────────┘         └──────────┬──────────┘
           │                               │
           └───────────────┬───────────────┘
                           │
                           ▼
           ┌───────────────────────────────┐
           │  AbstractAdvancedCellInventory │
           │      (公共逻辑基类)             │
           │                               │
           │ • getOrCreateData() UUID加载  │
           │ • extractItems() 提取逻辑     │
           │ • getAvailableItems() 列举   │
           │ • ICellInventory 样板方法     │
           └───────────────┬───────────────┘
                           │
                           ▼
           ┌───────────────────────────────┐
           │     AdvancedCellData          │
           │    (WorldSavedData 持久化)    │
           │                               │
           │ • Object2LongMap<T> counts    │
           │ • 增量 NBT 缓存               │
           │ • 多频道支持                  │
           └───────────────────────────────┘
```

### 3.4 数据持久化 `AdvancedCellData.java`

**存储位置**：`世界存档/data/infinite/{UUID}.dat`

**核心数据结构**：
```java
public class ChannelData<T extends IAEStack<T>> {
    // FastUtil 高性能 Map：O(1) 存取
    Object2LongMap<T> counts = new Object2LongOpenHashMap<>();

    // 增量缓存：避免每次保存都序列化几十万物品
    Object2ObjectMap<T, NBTTagCompound> nbtCache;
    Set<T> dirtyItems;  // 只有序列化变动的物品
    boolean isFullDirty; // 首次加载需要全量重建
}
```

**增量保存优化**：
```java
public NBTTagList getOrUpdateNbtList() {
    if (isFullDirty) {
        // 全量序列化（首次加载）
        for (Entry<T> entry : counts.entrySet()) {
            nbtCache.put(entry.getKey(), serialize(entry));
        }
    } else {
        // 增量序列化（只处理变动的物品）
        for (T dirtyItem : dirtyItems) {
            nbtCache.put(dirtyItem, serialize(dirtyItem));
        }
    }
}
```

---

### 3.5 有限容量盘 `AdvancedCellInventory.java`

**注入逻辑（带容量检查）**：
```java
public T injectItems(T input, Actionable type, IActionSource src) {
    long currentCount = chanData.counts.getLong(input);
    long count = input.getStackSize();
    long bytesDelta = calculateBytesDelta(currentCount, count);
    long freeBytes = maxBytes - chanData.totalBytes;

    // 容量不足时，计算可接受数量
    if (bytesDelta > freeBytes) {
        long countWeCanAdd = calculatePartialAccept(freeBytes);
        // 部分接受，返回被拒数量
        return reject(count - countWeCanAdd);
    }
    // 全部接受
    chanData.modify(input, count, bytesDelta, isNewType ? 1 : 0);
}
```

**状态灯逻辑**：
| 返回值 | 颜色 | 条件                   |
| ------ | ---- | ---------------------- |
| 4      | 蓝色 | 空盘 (totalBytes == 0) |
| 1      | 绿色 | 正常 (< 75%)           |
| 2      | 橙色 | 临界 (≥ 75%)           |
| 3      | 红色 | 已满 (== 100%)         |

---

### 3.6 无限容量盘 `InfiniteCellInventory.java`

**设计原则**：
1. **无容量上限检查** - 直接接受所有输入
2. **防溢出保护** - 单种类上限 `Long.MAX_VALUE / 2`
3. **简化计数** - 1 item = 1 byte（不参与 unitsPerByte 换算）

```java
private static final long PER_TYPE_MAX = Long.MAX_VALUE / 2;
private static final long DISPLAY_BYTES = Long.MAX_VALUE / 2;

@Override
public T injectItems(T input, Actionable type, IActionSource src) {
    long currentCount = chanData.counts.getLong(input);

    // 单种类上限保护
    if (currentCount >= PER_TYPE_MAX) {
        return input; // 拒绝
    }

    long canAdd = Math.min(input.getStackSize(), PER_TYPE_MAX - currentCount);
    chanData.modify(input, canAdd, canAdd, isNewType ? 1 : 0);
}
```

**容量方法返回值**：
```java
public long getRemainingItemCount() {
    return DISPLAY_BYTES; // 永远返回定值，确保"无底洞"效果
}

public boolean canHoldNewItem() {
    return true; // 永远可以存入新种类
}
```

---

### 3.7 Mixin 修复

#### MixinJEICellCategory - 排序溢出修复
**问题**：NAE2 使用 `Math.toIntExact(b - a)` 排序，超大数量相减溢出

```java
@Redirect(method = "setRecipe", at = @At(value = "INVOKE", target = "ArrayList.sort"))
private void fixIntegerOverflowSort(ArrayList<IAEStack<?>> instance, Comparator<?> original) {
    // 使用 Long.compare 替代 int 减法
    instance.sort((a, b) -> Long.compare(b.getStackSize(), a.getStackSize()));
}
```

#### MixinJEICellCategoryDisplay - 显示修复
**问题1**：NAE2 JEI tooltip 显示错误的字节数

```java
@Inject(method = "getCallBack", at = @At("RETURN"))
private void wrapCallbackForInfiniteCell(...) {
    // 移除错误的 "used" 行
    tooltip.remove(tooltip.size() - 1);
    // 添加正确的字节数 (stackSize * 1)
    tooltip.add(format("used", stackSize));
}
```

**问题2**：容量条显示错误

```java
@WrapOperation(method = "drawExtras", at = @At(value = "INVOKE", target = "getRemainingItemCount"))
private long wrapGetRemainingItemCount(ICellInventory<?> instance) {
    if (instance instanceof InfiniteCellInventory<?>) {
        return DISPLAY_BYTES - instance.getStoredItemCount();
    }
}
```

---

## 四、依赖关系

| 模组                  | 依赖类型 | 用途                 |
| --------------------- | -------- | -------------------- |
| Applied Energistics 2 | **必需** | 核心 API、存储系统   |
| MixinBooter           | **必需** | Late Mixin 加载      |
| NAE2                  | 可选     | 256K+ 组件、JEI 兼容 |
| mekeng                | 可选     | 气体存储支持         |

---

## 五、合成系统

### 配方格式
```
无序合成：
  [外壳] + [组件] = [存储元件]
```

### 组件来源
| 阶梯        | 来源模组 | 物品ID                            |
| ----------- | -------- | --------------------------------- |
| 1K-64K      | AE2      | appliedenergistics2:material      |
| 256K-16384K | NAE2     | nae2:material                     |
| INF         | 本模组   | infinitycell:infinite_component_* |

---

## 六、调试工具

`DebugInjectorItem` - 压力测试工具

**使用方法**：
1. 主手持有调试棒
2. 副手持有无限物品盘
3. 右键触发

**测试内容**：
1. 注入 `Long.MAX_VALUE / 4` 个石头
2. 注入 `Long.MAX_VALUE / 4` 个泥土
3. 注入 100,000 种不同 NBT 的苹果

---

## 七、性能优化总结

1. **FastUtil Object2LongMap** - O(1) 存取，替代 AE2 链表
2. **增量 NBT 缓存** - 只序列化变动的物品
3. **懒分配 UUID** - 创造模式物品栏不预分配
4. **反射注入 Handler** - 确保优先处理自定义盘
5. **防溢出设计** - 所有大数运算使用 `Long.MAX_VALUE / 2`

---

## 八、文件清单

### Java 源文件
| 文件                               | 行数 | 职责         |
| ---------------------------------- | ---- | ------------ |
| InfinityCell.java                  | ~163 | 模组入口     |
| AdvancedCellItem.java              | ~424 | 存储元件物品 |
| AdvancedCellHousingItem.java       | ~14  | 外壳物品     |
| InfiniteComponentItem.java         | ~24  | 无限组件物品 |
| DebugInjectorItem.java             | ~114 | 调试工具     |
| AdvancedCellHandler.java           | ~63  | Handler 拦截 |
| AdvancedCellData.java              | ~170 | 数据持久化   |
| AbstractAdvancedCellInventory.java | ~293 | 抽象基类     |
| AdvancedCellInventory.java         | ~176 | 有限盘实现   |
| InfiniteCellInventory.java         | ~130 | 无限盘实现   |
| MixinLoader.java                   | ~13  | Mixin 加载   |
| MixinJEICellCategory.java          | ~27  | JEI 排序修复 |
| MixinJEICellCategoryDisplay.java   | ~128 | JEI 显示修复 |

### 资源文件
- `mcmod.info` - 模组元数据
- `mixins.infinitycell.json` - Mixin 配置
- `assets/infinitycell/lang/*.lang` - 中英语言文件
- `assets/infinitycell/models/item/*.json` - 物品模型
- `assets/infinitycell/textures/items/*.png` - 物品贴图
