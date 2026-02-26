# AE2 无限存储与性能优化技术分析报告

在对 `AE2OmniCells` (1.21.1)、`BeyondDimensions` (1.21.1) 以及 `AE2Things` (1.7.10 GTNH 向下移植版) 进行源码分析后，本报告总结了它们在 Applied Energistics 2 (AE2) 架构下实现“无限制种类/容量存储”的具体机制，并重点剖析了各自保证服务端性能（特别是在海量类型存储场景下）的设计思路。

---

## 1. AE2OmniCells (1.21.1)

`AE2OmniCells` 原生提供了一种突破传统 AE2 类型的“通用盘”。

### 1.1 无限存储实现机制

* **核心类**: [AEUniversalCellInventory](file:///d:/Codefield/JAVA/IDEA/test/AE2OmniCells/src/main/java/com/wintercogs/ae2omnicells/common/me/AEUniversalCellInventory.java#46-728) 实现了 [StorageCell](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#192-209)。
* **类型限制突破**: 通过 `IAEUniversalCell.getTotalTypes()` 返回 `< 0` 的值，在 Inventory 内部会将其静态映射为 `Long.MAX_VALUE`。这样在查验 [canHoldNewItemGeneric](file:///d:/Codefield/JAVA/IDEA/test/AE2OmniCells/src/main/java/com/wintercogs/ae2omnicells/common/me/AEUniversalCellInventory.java#427-436) 时，总能继续放入新类型。
* **数据结构**: 放弃了传统的 `IItemList`，转而使用 `it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey>` 高效处理资源键值对映射，这极大降低了键值遍历和哈希碰撞的开销。

### 1.2 性能保障策略

* **O(1) 增量字节计算**: AE2 传统的字节占用计算需要在存取时遍历该类型所有物品数量除以单字节承载量 (`amountPerByte`)。OmniCells 采用了基于 `bucketSums` (`Long2LongOpenHashMap`) 的增量计算缓存法：
  在 `insert/extract` 且 `Mode == MODULATE` 时：

  ```java
  final long deltaValueBytes = safeSub(ceilDiv(newBucket, amountPerByte), ceilDiv(oldBucket, amountPerByte));
  usedBytesCached = safeAdd(usedBytesCached, deltaValueBytes);
  ```

  通过维护“已用桶总量”，在每次物品吞吐时仅进行数理增量加减，彻底淘汰了通过全量遍历计算元件总体积的高昂 O(N) 性能开销。
* **虚空卡及独立条件优化**: [handleOverflowVoidOnInsert](file:///d:/Codefield/JAVA/IDEA/test/AE2OmniCells/src/main/java/com/wintercogs/ae2omnicells/common/me/AEUniversalCellInventory.java#527-549) 等操作分离自底层逻辑，在无法新开类型但配置了虚空卡的情况下快速返回全额插入，跳过了复杂的冗余计算。

---

## 2. BeyondDimensions (1.21.1)

`BeyondDimensions` 并非纯粹作为 AE2 插件存在，而是自带了独立的“维度网络（DimensionsNet）”存储核心，然后通过“网络接入器（NetStorageCell）”向 AE2 开放了代理接口。

### 2.1 无限存储实现机制

* **核心存储基类**: [AbstractUnorderedStackHandler](file:///d:/Codefield/JAVA/IDEA/test/BeyondDimensions/src/main/java/com/wintercogs/beyonddimensions/Api/DataBase/Handler/AbstractUnorderedStackHandler.java#31-962) （包含 [UnifiedStorage](file:///d:/Codefield/JAVA/IDEA/test/BeyondDimensions/src/main/java/com/wintercogs/beyonddimensions/Api/DataBase/Storage/UnifiedStorage.java#20-97)）。
* **无上限映射**: `slotCapacity` 默认可设为 `Long.MAX_VALUE`，`slotMaxSize` 可设为 `Integer.MAX_VALUE`。底层使用 `HashMap<IStackKey<?>, Long> storage` 直接维护无序海量物品堆，完全脱离传统格子概念。

### 2.2 性能保障策略

* **弱引用增量订阅（Weak Subscriber + Delta Updates）**: [NetStorageCell](file:///d:/Codefield/JAVA/IDEA/test/BeyondDimensions/src/main/java/com/wintercogs/beyonddimensions/Integration/AE/NetStorageCell.java#16-167) 作为 AE2 的存储切面，不再做实时的数据拉取。它在初始化时通过 [fullRebuildSnapshot()](file:///d:/Codefield/JAVA/IDEA/test/BeyondDimensions/src/main/java/com/wintercogs/beyonddimensions/Integration/AE/NetStorageCell.java#129-143) 获取一次数据字典建立 `KeyCounter snapshot`，随后：

  ```java
  this.deltaSub = storage.subscribeDeltaWeak(this, (self, type, size, insert) -> {
      self.applyDelta(type, size, insert);
  });
  ```

  利用弱订阅响应底层的增量改变。此 O(1) 事件直接对快照中的局部计数进行加减。在 AE 网络试图遍历或查询有效物品 [getAvailableStacks(KeyCounter out)](file:///d:/Codefield/JAVA/IDEA/test/AE2OmniCells/src/main/java/com/wintercogs/ae2omnicells/common/me/AEUniversalCellInventory.java#386-407) 时，它直接 `out.addAll(snapshot)` 交付本地缓存，把 O(N) IO消耗抹平时。
* **Zero 剥离策略**: 基于 `ZeroPolicy.REMOVE_ON_ZERO`，物品数量归 0 会自索引实时剔除，控制驻留内存及遍历开销。

---

## 3. AE2Things (1.7.10 GTNH)

在 1.7.10 这个低版本，突破类型的阻力比高版本更大。GTNH 环境下最大的问题在于 NBT 容量限制（Minecraft 对 ItemStack 的 NBT 尺寸存在几 MB 的序列化硬顶，这常常导致服务器坏档或区块崩溃）。

### 3.1 无限存储实现机制

* **底层解耦外挂**: [InfinityItemStorageCellInventory](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#37-375) 实现了 `ITCellInventory`。元件实体 [ItemStack](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#101-105) 内彻底剥离物品数据，仅通过 `Constants.DISKUUID` 字段保留一条 [UUID](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#93-100) 字符串索引。
* **真实存储落地**: 所有实际数据存储于全局单例 [StorageManager](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/StorageManager.java#29-192) 中的 [DataStorage](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/DataStorage.java#18-188)。[StorageManager](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/StorageManager.java#29-192) 继承自 `WorldSavedData`，依托世界级别的外盘存档（存放于服务端顶级 dat 文件）无限拓展。

### 3.2 性能保障策略

* **彻底脱离 NBT 流转同步**: 由于所有逻辑走全局管理中心 `AE2ThingAPI.instance().getStorageManager()`，当玩家转移 AE2 硬盘到网络或从物理机器弹射时，网络协议发包仅交换几个字节的 ID。避免了将千兆字节的海量标签信息反序列化进 [ItemStack](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#101-105) NBT。
* **复用 AE2 内部原生 `IItemList`**: UUID 所对应的 [DataStorage](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/DataStorage.java#18-188) 实际上在内部维护着原生的 `IItemList<IAEItemStack>`。AE2 对于自身的 `IItemList` 查询（其内多为并查集和 HashList 变体）有高度优化的内部树缓存，能快速实现注入和剔除。不重复造轮子也是保证老版本性能强劲的一部分。
* **网格改动直连广播**: 在 [saveChanges](file:///d:/Codefield/JAVA/IDEA/test/AE2Things/src/main/java/com/asdflj/ae2thing/common/storage/infinityCell/InfinityItemStorageCellInventory.java#338-350) 中直接调取所属的并归属网格节点 `IGrid`。通过 `postAlterationOfStoredItems` 对外广播改变。不再让宿主 ME Driver 大周期扫描格子变更。

---

## 总结

尽管三者的终极目的类似，但应对不同版本和侧重而展现出的技术特征迥异：

1. **缓存更新换算** (`AE2OmniCells`): 用高阶数学代数增减计算（增量桶算法）替代 AE2 每 Tick O(N) 的体积统计；
2. **读写分离与弱引用代理同步** (`BeyondDimensions`): 将“非 AE 核心数据”包裹进独立引擎，暴露给 AE 的只有随时通过回调自动维护的极简 O(1) 镜像缓存，确保 AE 提取阶段零计算压力；
3. **数据脱壳与顶级外挂存储** (`AE2Things`): 解决 1.7 硬件与原版框架的架构落后，强行斩断 NBT 依附束缚，引入 WorldSaveData 级别的 UUID 全局寻址机制规避大数据包。

以上策略综合了数据结构置换、底层逻辑接管以及更新频率降级，是从根本上维持大后期海量储存中 TPS 稳定的主流“万能盘”解决方案。
