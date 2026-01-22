# 🚀 如何启用SimplifiedTrendStrategy新策略

**当前状态**: ❌ 代码已修改，但尚未生效  
**原因**: IntelliJ IDEA需要重新编译并重启应用  
**目标**: ✅ 让新的精简策略（ADX+ATR+EMA）生效  

---

## 📋 快速检查清单

### 检查1: 确认代码已修改 ✅

已修改的文件：
- [x] SimplifiedTrendStrategy.java（新建）
- [x] TradingStrategyService.java（已修改）
- [x] TradingScheduler.java（已修改）

### 检查2: 确认新策略是否生效 ❌

**检查命令：**
```bash
tail -f logs/aug-trade.log | grep "SimplifiedTrend"
```

**预期输出（新策略生效）：**
```
🎯 执行精简趋势策略（仅ADX+ATR+EMA）
📊 精简策略信号: BUY - 精简趋势策略 v2.0
```

**当前输出（旧策略）：**
```
[BalancedAggressive] 综合评分 - 买入: 8, 卖出: 0
[CompositeStrategy] 综合评分 - 做多: 14, 做空: 0
```

---

## 🔧 启用方法（选择一种）

### 方法1：IntelliJ IDEA重启（最简单）⭐⭐⭐⭐⭐

**步骤：**
1. 在IDEA底部找到运行的应用（AugTradeApplication）
2. 点击红色方块 ⬛ 停止应用
3. 等待3-5秒确保完全停止
4. 点击绿色三角 ▶️ 重新运行
5. 等待30秒启动完成
6. 查看控制台日志，应该看到：
   ```
   🎯 执行精简趋势策略（仅ADX+ATR+EMA）
   ```

**如何确认成功：**
```bash
# 在终端执行
tail -20 logs/aug-trade.log | grep "SimplifiedTrend"

# 如果看到"SimplifiedTrend v2.0"就成功了！
```

---

### 方法2：IntelliJ IDEA重新构建

**步骤：**
1. 菜单：Build → Rebuild Project
2. 等待编译完成（底部进度条）
3. 停止当前运行的应用
4. 重新运行应用

---

### 方法3：命令行编译（备用）

**前提：需要先配置maven**
```bash
# 配置maven环境
source ~/.bash_profile

# 查看maven是否可用
which mvn

# 如果找到mvn，继续：
cd /Users/peterwang/IdeaProjects/AugTrade
mvn clean compile
```

---

## 🔍 验证新策略是否生效

### 检查1: 查看启动日志

**执行命令：**
```bash
tail -f logs/aug-trade.log
```

**等待60秒（策略每60秒执行一次），应该看到：**

**✅ 新策略日志（成功）：**
```
========================================
【Bybit黄金交易策略】开始执行 - 交易品种: XAUTUSDT
当前黄金价格: $4870.60
🎯 执行精简趋势策略（仅ADX+ATR+EMA）
📊 核心指标 - ADX: 8.01, ATR: 3.30, 当前价: 4870.60
📊 趋势判断 - EMA20: 4845.60, EMA50: 4832.10, 趋势: 上涨
⛔ ADX=8.01 < 20，震荡市场（无明确趋势），暂停交易
📊 精简策略信号: HOLD - 精简趋势策略 v2.0
⏸️ 保持观望，等待高质量信号
策略执行完成
========================================
```

**❌ 旧策略日志（失败）：**
```
[BalancedAggressive] Williams深度超卖 (-81.48) → +5分
[CompositeStrategy] 综合评分 - 做多: 14, 做空: 0
[RangingMarket] ✅ 盘整市场，启用震荡市均值回归策略
```

### 检查2: 查看策略名称

**执行命令：**
```bash
mysql -uroot -p12345678 -D test -e "
SELECT strategy_name, COUNT(*) as count
FROM t_trade_order
WHERE DATE(create_time) = CURDATE()
GROUP BY strategy_name;
"
```

**预期输出（新策略）：**
```
+-----------------+-------+
| strategy_name   | count |
+-----------------+-------+
| SimplifiedTrend |   5   |
+-----------------+-------+
```

**当前输出（旧策略）：**
```
+-----------------+-------+
| strategy_name   | count |
+-----------------+-------+
| AggressiveML    |   15  |
+-----------------+-------+
```

### 检查3: ADX过滤是否生效

**当前市场：ADX=8.01（极弱震荡）**

**新策略行为：**
```
⛔ ADX=8.01 < 20，震荡市场，暂停交易
→ 不会开仓（避免亏损）
```

**旧策略行为：**
```
⚡ 极弱趋势市场(ADX=8.01)，但信号足够强，允许交易
→ 仍然开仓（已亏损$301）
```

---

## 🎯 预期效果对比

### 当前ADX=8.01的情况

| 策略 | 行为 | 结果 |
|------|------|------|
| **旧策略** | 允许交易 | 今日15笔，亏$301 ❌ |
| **新策略** | 暂停交易 | 0笔，避免亏损 ✅ |

**新策略优势：**
- 在ADX<20时强制HOLD
- 避免震荡市的无效交易
- 等待ADX>25的强趋势再交易

---

## ⚠️ 常见问题

### Q1: 为什么重启了还没生效？

**可能原因：**
1. IntelliJ IDEA使用的是旧的编译结果
2. 需要先"Build → Rebuild Project"
3. 或者直接在IDEA中停止再重新运行

### Q2: 如何快速回滚？

**如果新策略有问题，可以快速回滚：**

```java
// 修改TradingScheduler.java
// 找到这一行：
SimplifiedTrendStrategy.Signal strategySignal = simplifiedTrendStrategy.execute(bybitSymbol);

// 改回：
com.ltp.peter.augtrade.service.core.signal.TradingSignal tradingSignal = 
        strategyOrchestrator.generateSignal(bybitSymbol);
```

### Q3: 新策略会减少交易频率吗？

**是的，这是设计目标：**
- 旧策略：14.8笔/天（过度交易）
- 新策略：5-8笔/天（精准交易）
- 目标：提高信号质量，而非数量

---

## 📊 启用后的监控

### 第一小时（关键）

**每10分钟查看一次日志：**
```bash
tail -50 logs/aug-trade.log | grep -E "(SimplifiedTrend|ADX|策略执行完成)"
```

**关注点：**
- 是否出现"SimplifiedTrend v2.0"
- ADX<20时是否正确HOLD
- ADX>25时是否能正常开仓

### 第一天

**每2小时查看交易统计：**
```bash
mysql -uroot -p12345678 -D test -e "
SELECT 
    COUNT(*) as trades,
    ROUND(SUM(profit_loss), 2) as profit,
    ROUND(AVG(profit_loss), 2) as avg,
    ROUND(AVG(CASE WHEN profit_loss > 0 THEN 1.0 ELSE 0.0 END) * 100, 1) as win_rate,
    strategy_name
FROM t_trade_order 
WHERE DATE(create_time) = CURDATE()
  AND status != 'OPEN'
GROUP BY strategy_name;
"
```

**期望结果：**
- 交易频率：5-8笔
- 胜率：75%+
- 平均盈亏：+$10+

---

## ✅ 成功标志

当看到以下日志时，新策略已成功启用：

```
🎯 执行精简趋势策略（仅ADX+ATR+EMA）
📊 核心指标 - ADX: XX.XX, ATR: X.XX, 当前价: XXXX.XX
📊 趋势判断 - EMA20: XXXX.XX, EMA50: XXXX.XX, 趋势: 上涨/下跌
```

**关键特征：**
- ✅ 出现"SimplifiedTrend v2.0"
- ✅ 只计算ADX、ATR、EMA三个指标
- ✅ ADX<20时强制HOLD
- ✅ 不再出现Williams/RSI/MACD/布林带/ML的日志

---

## 🎯 总结

**当前任务：在IntelliJ IDEA中重新运行应用**

**操作步骤：**
1. 停止应用（红色方块⬛）
2. 重新运行（绿色三角▶️）
3. 等待启动完成（约30秒）
4. 查看日志确认新策略生效

**确认命令：**
```bash
tail -f logs/aug-trade.log | grep "SimplifiedTrend"
```

**如果看到"SimplifiedTrend v2.0"就成功了！** 🎉

---

**文档生成时间**: 2026-01-21 18:57  
**预期生效时间**: 重启应用后立即生效  
**验证时间**: 重启后60秒内（第一次策略执行）
