# Infinity Cell

**Minecraft 1.12.2** 的 Applied Energistics 2 附属模组，为 AE2 网络系统提供从 **1K 到无限容量** 的高性能多阶梯存储磁盘。

## ✨ 功能特性

- **多阶梯容量**：1K / 4K / 16K / 64K / 256K / 1024K / 4096K / 16384K / ∞ (无限)
- **三种存储类型**：物品、流体、气体（需安装 [MekanismEnergistics](https://www.curseforge.com/minecraft/mc-mods/applied-mekanistics)）
- **高性能后端**：采用 FastUtil `Object2LongMap` 实现 O(1) 存取，增量 NBT 序列化避免大规模重建
- **UUID 分离存储**：每块磁盘拥有独立 UUID，数据持久化到主世界 `data/infinite/` 目录
- **完整 AE2 集成**：驱动器 LED 状态指示、容量/类型 tooltip、IO Port 兼容

## 🛠️ 开发环境

| 项目 | 版本 |
|------|------|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847 |
| 构建工具 | Gradle 9.2.1 + [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 2.0.2 |
| Java | 25 |

## 📦 快速开始

1. 克隆本仓库
2. 确保 IDEA 使用 Java 25 作为 Gradle JVM（`Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM`）
3. 在 IDEA 中打开项目，加载 Gradle 工程
4. 运行 `runClient` 或使用预配置的 `1. Run Client` 启动

## 📄 项目结构

```
src/main/java/com/mcdyc/infinitycell/
├── ExampleMod.java              # 模组主类，注册物品与 CellHandler
├── item/
│   └── AdvancedCellItem.java    # 磁盘物品定义，工厂方法 + Tooltip 渲染
└── storage/
    ├── AdvancedCellData.java     # 后端数据模型 (WorldSavedData + FastUtil)
    ├── AdvancedCellHandler.java  # AE2 ICellHandler 适配器
    └── AdvancedCellInventory.java # 存取代理，容量校验与字节计算
```

## 👤 作者

**MCdyc**

谢谢小老师给的材质

## 🤖 代码声明

本项目代码由 **Google Gemini** 与 **Anthropic Claude Code** 协助编写。

## 📜 许可证

MIT License
