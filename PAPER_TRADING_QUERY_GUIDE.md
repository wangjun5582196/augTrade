# 📊 模拟交易查询指南

## ✅ 系统已在运行

根据日志，你的系统已经产生了**5笔交易**：
- 盈利：2单 (+$23, +$12)
- 亏损：3单 (-$35, -$13, -$6)
- 当前胜率：40%
- 累计盈亏：约-$19

所有交易都已保存到数据库！

---

## 🌐 通过REST API查询（最简单）

我已经创建了REST API控制器，重启应用后可以直接通过浏览器查询：

### 1. 查看所有交易明细
```bash
open http://localhost:3131/api/paper-trading/details
```

显示最近50笔交易的详细信息（中文格式化）。

### 2. 查看统计数据
```bash
open http://localhost:3131/api/paper-trading/statistics
```

显示：
- 总交易数
- 盈利/亏损次数
- 胜率
- 累计盈亏
- 止盈/止损/信号反转次数

### 3. 查看当前持仓
```bash
open http://localhost:3131/api/paper-trading/current-position
```

如果有持仓，显示实时盈亏。

### 4. 查看已平仓订单
```bash
open http://localhost:3131/api/paper-trading/closed-trades
```

### 5. 查看持仓中订单
```bash
open http://localhost:3131/api/paper-trading/open-trades
```

---

## 💻 通过curl查询

### 查看交易明细（推荐）
```bash
curl http://localhost:3131/api/paper-trading/details | python3 -m json.tool
```

### 查看统计数据
```bash
curl http://localhost:3131/api/paper-trading/statistics | python3 -m json.tool
```

### 查看当前持仓
```bash
curl http://localhost:3131/api/paper-trading/current-position | python3 -m json.tool
```

---

## 📊 通过Java代码查询

如果你想在代码中查询，可以使用TradeOrderMapper：

```java
@Autowired
private TradeOrderMapper tradeOrderMapper;

// 查询所有模拟交易
List<TradeOrder> trades = tradeOrderMapper.selectList(null).stream()
    .filter(order -> "AggressiveML".equals(order.getStrategyName()))
    .collect(Collectors.toList());

// 查询持仓中
List<TradeOrder> openTrades = trades.stream()
    .filter(order -> "OPEN".equals(order.getStatus()))
    .collect(Collectors.toList());

// 查询已平仓
List<TradeOrder> closedTrades = trades.stream()
    .filter(order -> order.getStatus().startsWith("CLOSED"))
    .collect(Collectors.toList());
```

---

## 🗄️ 数据库表说明

### t_trade_order 表（交易订单表）

**字段说明**：
```sql
id                - 主键ID
order_no          - 订单号（PAPER_XXXXXXXX）
symbol            - 交易品种（XAUTUSDT）
order_type        - 订单类型（MARKET）
side              - 交易方向（BUY/SELL）
price             - 入场价格
quantity          - 交易数量
executed_price    - 成交价格
executed_quantity - 成交数量
status            - 状态：
                    * OPEN - 持仓中
                    * CLOSED_TAKE_PROFIT - 止盈平仓
                    * CLOSED_STOP_LOSS - 止损平仓
                    * CLOSED_SIGNAL_REVERSAL - 信号反转平仓
strategy_name     - 策略名称（AggressiveML）
take_profit_price - 止盈价格
stop_loss_price   - 止损价格
profit_loss       - 盈亏金额（$）
fee               - 手续费
remark            - 备注
create_time       - 开仓时间
update_time       - 平仓时间
executed_time     - 执行时间
```

---

## 📈 当前交易数据（从日志）

根据你的日志：

| 订单号 | 时间 | 方向 | 结果 | 盈亏 |
|--------|------|------|------|------|
| 第1单 | 15:27 | 做多 | 信号反转 | +$23.00 ✅ |
| 第2单 | 15:28 | 做多 | 信号反转 | -$35.00 ❌ |
| 第3单 | 15:42 | 做多 | 信号反转 | -$13.00 ❌ |
| 第4单 | 16:05 | 做多 | 信号反转 | -$6.00 ❌ |
| 第5单 | 16:10 | 做多 | 信号反转 | +$12.00 ✅ |
| 第6单 | 16:28 | 做空 | 信号反转 | (平仓中) |
| 第7单 | 16:31 | 做多 | 信号反转 | (平仓中) |
| 第8单 | 16:47 | 做多 | 持仓中 | +$1.00未实现 |

**统计**：
- 总交易：5单（已平仓）
- 盈利：2单
- 亏损：3单
- 胜率：40%
- 累计盈亏：约-$19

---

## 🎯 快速查看方法

### 方法1：REST API（推荐）⭐⭐⭐⭐⭐

```bash
# 重启应用后，在浏览器打开：
open http://localhost:3131/api/paper-trading/details
```

**优点**：
- ✅ 最简单，直接浏览器查看
- ✅ JSON格式，数据完整
- ✅ 自动格式化

### 方法2：使用curl命令

```bash
# 查看交易明细
curl -s http://localhost:3131/api/paper-trading/details | python3 -m json.tool

# 查看统计
curl -s http://localhost:3131/api/paper-trading/statistics | python3 -m json.tool
```

### 方法3：查看日志（实时监控）

```bash
# 查看所有交易记录
grep "模拟开仓\|模拟平仓" logs/aug-trade.log

# 查看盈亏统计
grep "累计统计" logs/aug-trade.log | tail -5
```

---

## 📱 示例API响应

### /details - 交易明细
```json
{
  "total": 5,
  "statistics": "总交易: 5单 | 盈利: 2单 | 亏损: 3单 | 胜率: 40.0% | 累计盈亏: $-19.00",
  "trades": [
    {
      "订单号": "PAPER_AEDDB0C3",
      "方向": "做多",
      "入场价": 4463.00,
      "止损价": 4453.00,
      "止盈价": 4483.00,
      "盈亏": 1.00,
      "状态": "持仓中",
      "备注": "模拟开仓 - 持仓中",
      "开仓时间": "2026-01-07T16:47:01",
      "平仓时间": "2026-01-07T16:47:01"
    },
    {
      "订单号": "PAPER_5D94E48D",
      "方向": "做多",
      "入场价": 4451.00,
      "止损价": 4441.00,
      "止盈价": 4471.00,
      "盈亏": 12.00,
      "状态": "信号反转平仓",
      "备注": "模拟平仓 - 信号反转 - 盈亏: $12.00",
      "开仓时间": "2026-01-07T16:31:01",
      "平仓时间": "2026-01-07T16:45:00"
    }
  ]
}
```

### /statistics - 统计数据
```json
{
  "summary": "总交易: 5单 | 盈利: 2单 | 亏损: 3单 | 胜率: 40.0% | 累计盈亏: $-19.00",
  "totalTrades": 5,
  "winTrades": 2,
  "lossTrades": 3,
  "totalProfit": -19.0,
  "winRate": 40.0,
  "takeProfitCount": 0,
  "stopLossCount": 0,
  "signalReversalCount": 5,
  "totalPnlFromDb": -19.0
}
```

---

## 🔍 观察发现

从你的数据可以看出：

### ✅ 系统运行正常：
- 开仓、持仓、平仓流程完整
- 数据库记录正常
- 信号反转功能工作正常

### ⚠️ 策略需要优化：
1. **全部是信号反转平仓**
   - 说明策略信号变化太快
   - 没有一笔走到止盈或止损
   - 建议：延长冷却期或调整策略

2. **胜率40%偏低**
   - 目标应该>50%
   - 建议：使用更稳健的策略

3. **亏损单金额大**
   - 一单亏$35，但止损设置只有$10
   - 说明信号反转时亏损更大
   - 建议：优化入场时机

---

## 💡 优化建议

### 1. 延长冷却期
```java
// TradingScheduler.java 第93行
private static final int REVERSAL_COOLDOWN_SECONDS = 300; // 从120改为300秒
```

### 2. 使用更稳健的策略
```java
// TradingScheduler.java 第163行
// 从简化ML改为综合简化版
AggressiveScalpingStrategy.Signal signal = aggressiveStrategy.balancedAggressiveStrategy(bybitSymbol);
```

### 3. 提高ML阈值
```java
// AggressiveScalpingStrategy.java 第337-350行
// 从0.55/0.45改为0.60/0.40
if (mlPrediction > 0.60 && confidence > 0.6) {  // 更严格
    return Signal.BUY;
}
if (mlPrediction < 0.40 && confidence > 0.6) {
    return Signal.SELL;
}
```

---

## 🚀 现在查看你的交易记录

重启应用后，在浏览器打开：

```bash
open http://localhost:3131/api/paper-trading/details
```

**你会看到完整的交易明细表！** 📊

---

## 📞 快速命令汇总

```bash
# 1. 查看交易明细（浏览器）
open http://localhost:3131/api/paper-trading/details

# 2. 查看统计数据
open http://localhost:3131/api/paper-trading/statistics

# 3. 查看当前持仓
open http://localhost:3131/api/paper-trading/current-position

# 4. 查看日志中的交易
grep "模拟开仓\|模拟平仓" logs/aug-trade.log | tail -20

# 5. 查看累计盈亏
grep "累计统计" logs/aug-trade.log | tail -5
```

---

## 🎉 完成！

**你的交易明细已经在数据库中，可以通过REST API随时查看！** 📈

告诉我你想看哪种查询方式，或者是否需要优化策略！
