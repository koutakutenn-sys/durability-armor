# Durability Armor（护甲随耐久衰减）

护甲磨损后防护能力会随之下降。每件已装备护甲的防护贡献按剩余耐久缩放：

```
r          = 当前耐久 / 最大耐久
multiplier = 1 - (1 - r)^2

当前护甲值   = 原始护甲值   * multiplier
当前护甲韧性 = 原始护甲韧性 * multiplier
```

| 剩余耐久 | 100% | 75% | 50% | 25% | 0% |
| --- | --- | --- | --- | --- | --- |
| multiplier | 1.00 | 0.9375 | 0.75 | 0.4375 | 0.00 |

击退抗性**不受影响**。

## 实现方式

本模组不修改物品。它只挂到原版把装备属性修饰符写入实体属性表的唯一路径
（`ItemStack#forEachModifier(EquipmentSlot, BiConsumer)`，由 `LivingEntity#collectEquipmentChanges` 调用），
在应用时缩放 `minecraft:armor` 与 `minecraft:armor_toughness` 的数值：**加法类修饰符**
（`ADD_VALUE`、`ADD_MULTIPLIED_BASE`）按倍率缩放，`ADD_MULTIPLIED_TOTAL` 保持不变 ——
这样该件装备**最终**提供的属性值才恰好等于「原始值 × 倍率」（原版计算式为
`(base + Σ 加法) · (1 + Σ 乘法总系数)`）。另外，**不可破坏（unbreakable）物品不享有豁免**：
与普通物品同样按 `r = 当前耐久 / 最大耐久` 计算；它们通常没有 damage，倍率自然为 1。

因此：

* 不永久修改物品的基础属性与数据组件，卸载模组后无需回滚；
* 数值由服务端在原版装备属性管线中计算，并通过原版 `ClientboundUpdateAttributesPacket` 镜像给客户端
  （快照取自 `AttributeInstance.getModifiers()`，包含缩放后的临时装备修饰符；客户端整表替换），
  因此客户端不可能与服务端不一致，模组自身也不需要发送任何网络包；
* 其他模组的护甲只要使用原版标准的属性修饰符组件，就自动生效；
* 不新增额外修饰符，复用原版相同的修饰符 id，因此不会出现属性重复叠加。

伤害减免、HUD 护甲条以及任何读取实体 `Attributes.ARMOR` / `Attributes.ARMOR_TOUGHNESS` 的代码
看到的都是缩放后的数值。

## 兼容性

* Minecraft 26.2、Fabric Loader >= 0.19.5、Java >= 25。
* 不依赖 Fabric API，无需配置文件。
* 客户端与专用服务端均可用（`environment: "*"`）。数值在服务端计算、由原版镜像到客户端，两端显示的防护一致。

### 已知行为

* 原版在护甲耐久变化后的下一个 tick 才重新应用装备属性修饰符，因此“打坏护甲的那一次伤害”仍按
  上一 tick 的数值减免；从下一 tick 起按新数值生效。
* 物品 tooltip 仍显示物品自身的属性数值（基础属性未变）。HUD 护甲条与实际伤害减免使用缩放后的值。
* 其他模组若绕过物品属性组件、直接往 `AttributeInstance` 注入护甲值，则不参与缩放；同样地，属性**基础值
  （base value）**与任何 `ADD_MULTIPLIED_TOTAL` 总系数都不参与缩放（后者是全局乘数，不属于单件装备的贡献）。
* 本模组自身不含客户端专属代码，客户端只镜像服务端算出的结果。

## 构建

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew build
./gradlew runIntegrationTest -PacceptMinecraftEula=true
```

集成测试在专用服务端运行，报告写入 `run-test/test-result.txt`。

## 许可

MIT，见 [LICENSE](LICENSE)。
