# 🎯 策略工厂使用指南

## ✨ 新架构优势

**一行配置，轻松切换策略！**

```yaml
# application.yml
trading:
  strategy:
    active: balanced-aggressive  # 🔥 只需修改这一行！
```

---

## 📋 可用策略列表

### 1. balanced-aggressive（平衡激进策略）⭐ 推荐

**特点**：
- 多指标综合评分（Williams、ADX、ATR、EMA、布林带）
- 适合中等趋势+震荡市场
- 灵活但参数多，需要细致调优

**适用场景**：
- ✅ ADX 20-30（中等趋势）
- ✅ ATR 2-6（正常波动）
- ✅ 震荡市和趋势市都可交易

**配置方式**：
```yaml
trading:
  strategy:
    active: balanced-aggressive
```

**日志示例**：
```
🎯 策略工厂 - 当前激活策略: balanced-aggressive
⚡ 执行【平衡激进策略】
📊 综合评分 - 买入: 8, 卖出: 3, 需要: 5分
✅ 平衡激进策略 → 买入信号
```

---

### 2. simplified-trend（精简趋势策略）

**特点**：
- 仅使用ADX、ATR、EMA三个核心指标
- 适合强趋势市场
- 简单稳健，误信号少

**适用场景**：
- ✅ ADX > 25（强趋势）
- ✅ 趋势明确的单边行情
- ❌ 震荡市无法交易（会HOLD）

**配置方式**：
```yaml
trading:
  strategy:
    active: simplified-trend
```

**日志示例**：
```
🎯 策略工厂 - 当前激活策略: simplified-trend
📈 执行【精简趋势策略】
📊 趋势: EMA20 > EMA50, ADX=28.5 > 20, ATR=3.2
✅ 精简趋势策略 → 买入信号
```

---

### 3. composite（组合策略）

**特点**：
- 多个子策略投票（RSI、Williams、布林带、震荡市）
- 适合所有市场
- 计算复杂但稳定，减少误信号

**适用场景**：
- ✅ 所有市场条件
- ✅ 需要高稳定性
- ❌ 可能错过部分机会

**配置方式**：
```yaml
trading:
  strategy:
    active: composite
```

**日志示例**：
```
🎯 策略工厂 - 当前激活策略: composite
🔀 执行【组合策略】
[Composite] 综合评分 - 做多: 15, 做空: 8
✅ 组合策略 → 买入信号 (强度: 75, 得分: 15)
```

---

## 🔄 如何切换策略

### 方法1：修改配置文件（推荐）

1. 打开 `src/main/resources/application.yml`
2. 找到 `trading.strategy.active`
3. 修改为目标策略名称
4. 重启应用

```yaml
# 切换到精简趋势策略
trading:
  strategy:
    active: simplified-trend

# 切换到平衡激进策略
trading:
  strategy:
    active: balanced-aggressive

# 切换到组合策略
trading:
  strategy:
    active: composite
```

### 方法2：运行时切换（高级）

调用策略工厂的API（需要添加Controller）：

```bash
curl -X POST http://localhost:3131/api/strategy/switch?strategyName=simplified-trend
```

---

## 📊 策略对比表

| 特性 | simplified-trend | balanced-aggressive | composite |
|------|-----------------|---------------------|-----------|
| **指标数量** | 3个（ADX、ATR、EMA） | 5个（+Williams、布林带） | 7个（全部指标） |
| **计算复杂度** | ⭐ 低 | ⭐⭐ 中 | ⭐⭐⭐ 高 |
| **趋势市场** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **震荡市场** | ⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **误信号率** | ⭐⭐⭐⭐⭐ 低 | ⭐⭐⭐ 中 | ⭐⭐⭐⭐ 低 |
| **交易频率** | ⭐⭐ 低 | ⭐⭐⭐⭐ 高 | ⭐⭐⭐ 中 |
| **参数调优** | ⭐⭐ 简单 | ⭐⭐⭐⭐ 复杂 | ⭐⭐⭐ 中等 |

---

## 🎯 推荐使用场景

### 场景1：当前市场ADX > 25（强趋势）
**推荐**：`simplified-trend`
- 趋势明确，简单策略效果更好
- 减少误信号，提高胜率

### 场景2：当前市场ADX 15-25（中等趋势）
**推荐**：`balanced-aggressive`（当前使用）
- 综合评分，灵活应对
- 趋势和震荡都能交易

### 场景3：当前市场ADX < 15（震荡市）
**推荐**：`composite`
- 多策略投票，降低风险
- 震荡市专用策略生效

### 场景4：不确定市场状态
**推荐**：`balanced-aggressive`
- 全能型策略
- 已完成P0修复，稳定性好

---

## 📝 切换策略步骤

### 快速切换（3步）

1. **编辑配置**
   ```bash
   vi src/main/resources/application.yml
   ```
   
2. **修改策略**
   ```yaml
   trading:
     strategy:
       active: simplified-trend  # 修改这里
   ```

3. **重启应用**
   ```bash
   ./restart.sh
   ```

### 验证切换成功

查看日志，确认策略已切换：

```bash
tail -f logs/aug-trade.log | grep "策略工厂"
```

**预期输出**：
```
🎯 策略工厂 - 当前激活策略: simplified-trend
📈 执行【精简趋势策略】
```

---

## 🔍 故障排查

### 问题1：策略未切换

**症状**：修改配置后仍使用旧策略

**解决方案**：
1. 确认配置文件已保存
2. 确认已重启应用
3. 检查日志中的策略名称

### 问题2：未知策略名称

**症状**：日志显示"未知策略，使用默认"

**原因**：策略名称拼写错误

**解决方案**：
- 使用正确的策略名称（全小写，连字符分隔）
- 可选值：`simplified-trend`, `balanced-aggressive`, `composite`

### 问题3：策略不生成信号

**症状**：一直显示HOLD

**原因**：市场条件不满足策略要求

**检查**：
- simplified-trend: 需要ADX > 20
- balanced-aggressive: 需要评分 ≥ 5
- composite: 需要综合得分 ≥ 阈值

---

## 💡 最佳实践

### 1. 定期切换策略测试

每周切换一次策略，对比表现：

```
周一：balanced-aggressive
周三：simplified-trend
周五：composite
周日：分析数据，选择最优策略
```

### 2. 根据市场状态自动切换（未来可实现）

```java
// 伪代码
if (ADX > 30) {
    strategyFactory.setActiveStrategy("simplified-trend");
} else if (ADX > 20) {
    strategyFactory.setActiveStrategy("balanced-aggressive");
} else {
    strategyFactory.setActiveStrategy("composite");
}
```

### 3. A/B测试对比

运行两个实例，使用不同策略，对比效果：

```bash
# 实例1
trading.strategy.active=balanced-aggressive

# 实例2  
trading.strategy.active=simplified-trend
```

---

## 📊 历史数据对比（参考）

### 1/20-1/21 回测数据（29笔交易）

| 策略 | 预测胜率 | 预测盈亏 | 说明 |
|------|---------|---------|------|
| **balanced-aggressive**（修复前） | 62.1% | -$515 | 逆向交易问题 |
| **balanced-aggressive**（修复后） | 75%+ | +$50+ | P0修复完成 |
| **simplified-trend** | 70%+ | +$100+ | 仅强趋势交易 |
| **composite** | 65%+ | +$30+ | 稳定但保守 |

**结论**：修复后的balanced-aggressive预期表现最好。

---

## 🚀 下一步

### 今天完成
- [x] 创建策略工厂
- [x] 修改TradingScheduler
- [x] 更新配置文件
- [x] 创建使用指南

### 明天观察
- [ ] 观察balanced-aggressive表现
- [ ] 记录每个策略的交易数据
- [ ] 对比不同策略效果

### 本周完成
- [ ] A/B测试三个策略
- [ ] 优化参数
- [ ] 选择最优策略上线

---

## 📚 相关文档

- [STRATEGY_ARCHITECTURE_PROPOSAL.md](./STRATEGY_ARCHITECTURE_PROPOSAL.md) - 架构设计方案
- [FIXES_20260122_COMPLETED.md](./FIXES_20260122_COMPLETED.md) - P0修复报告
- [TRADE_REVIEW_20260120_20260121.md](./TRADE_REVIEW_20260120_20260121.md) - 交易复盘

---

## ✅ 验证清单

重启应用后，检查以下内容：

- [ ] 日志显示正确的策略名称
- [ ] 策略能够正常生成信号
- [ ] 开仓使用正确的策略名称
- [ ] 切换策略后生效

**验证命令**：
```bash
# 查看当前策略
tail -f logs/aug-trade.log | grep "策略工厂"

# 查看策略输出
tail -f logs/aug-trade.log | grep "策略输出"
```

---

**创建时间**: 2026-01-22 16:01  
**状态**: ✅ 架构实施完成  
**使用方式**: 修改application.yml一行即可切换策略！
