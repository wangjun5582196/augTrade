# 📝 TradingScheduler 更新待办事项

## 任务概述
修改TradingScheduler中所有调用`paperTradingService.openPosition()`的地方,传递signal和context参数。

---

## 需要修改的方法

在`TradingScheduler.java`中搜索所有调用`openPosition`的地方,修改为:

### 修改前:
```java
paperTradingService.openPosition(
    symbol, 
    "LONG",  // or "SHORT"
    entryPrice, 
    quantity, 
    stopLoss, 
    takeProfit, 
    "StrategyName"
);
```

### 修改后:
```java
// 🔥 步骤1: 生成信号时保存signal和context
TradingSignal signal = strategyOrchestrator.generateSignal(symbol);
MarketContext context = strategyOrchestrator.getMarketContext(symbol, 100);

// 🔥 步骤2: 开仓时传递signal和context
paperTradingService.openPosition(
    symbol, 
    "LONG",  // or "SHORT"
    entryPrice, 
    quantity, 
    stopLoss, 
    takeProfit, 
    "StrategyName",
    signal,   // 🔥 新增参数
    context   // 🔥 新增参数
);
```

---

## 具体步骤

### 1. 查找所有openPosition调用

```bash
grep -n "openPosition(" src/main/java/com/ltp/peter/augtrade/task/TradingScheduler.java
```

### 2. 添加必要的import

```java
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import com.ltp.peter.augtrade.service.core.strategy.MarketContext;
```

### 3. 修改每个调用点

典型调用位置可能在:
- `executeTrading()` - 主交易执行方法
- `handleBuySignal()` - 买入信号处理
- `handleSellSignal()` - 卖出信号处理
- 其他自定义交易方法

### 4. 处理signal和context为null的情况

如果在某些情况下无法获取signal或context,可以传递null:

```java
paperTradingService.openPosition(
    symbol, side, entryPrice, quantity, 
    stopLoss, takeProfit, strategyName,
    null,  // signal可以为null
    null   // context可以为null
);
```

代码会安全处理null值,不会报错,只是不会保存指标数据。

---

## 完整示例

```java
@Scheduled(fixedDelay = 60000)  // 每60秒执行一次
public void executeTrading() {
    try {
        String symbol = "BTCUSDT";
        
        // 1. 生成交易信号并保存上下文
        TradingSignal signal = strategyOrchestrator.generateSignal(symbol);
        MarketContext context = strategyOrchestrator.getMarketContext(symbol, 100);
        
        if (signal == null || signal.getType() == TradingSignal.SignalType.HOLD) {
            return;  // 观望
        }
        
        // 2. 检查是否已有持仓
        if (paperTradingService.hasOpenPosition()) {
            return;
        }
        
        // 3. 获取当前价格
        BigDecimal currentPrice = getCurrentPrice(symbol);
        
        // 4. 计算止损止盈
        BigDecimal stopLoss = calculateStopLoss(currentPrice, signal.getType());
        BigDecimal takeProfit = calculateTakeProfit(currentPrice, signal.getType());
        
        // 5. 开仓 - 🔥 传递signal和context
        String side = signal.getType() == TradingSignal.SignalType.BUY ? "LONG" : "SHORT";
        paperTradingService.openPosition(
            symbol,
            side,
            currentPrice,
            BigDecimal.valueOf(0.001),  // quantity
            stopLoss,
            takeProfit,
            signal.getStrategyName(),
            signal,   // 🔥 传递信号
            context   // 🔥 传递上下文
        );
        
        log.info("✅ 开仓成功: {} {} @ ${}", symbol, side, currentPrice);
        
    } catch (Exception e) {
        log.error("❌ 执行交易失败", e);
    }
}
```

---

## 测试验证

修改完成后:

1. **编译检查**
```bash
mvn clean compile
```

2. **查看日志确认指标保存**
启动应用后,开仓时应该看到类似日志:
```
✅ 技术指标已保存到订单: Williams=-65.5, ADX=28.3, 信号强度=85, 市场状态=WEAK_TREND
```

3. **查询数据库验证**
```sql
SELECT 
    order_no, symbol, side, 
    williams_r, adx, signal_strength, market_regime
FROM t_trade_order
WHERE create_time > NOW() - INTERVAL 1 HOUR
ORDER BY create_time DESC
LIMIT 5;
```

应该看到指标字段有数据。

---

## 注意事项

1. **向后兼容**: 旧代码如果还在使用7参数版本的openPosition,会编译错误。必须全部更新。

2. **性能影响**: 每次开仓会多调用一次`getMarketContext()`,影响很小(<10ms)。

3. **数据完整性**: 即使signal/context为null,开仓也会成功,只是不会保存指标数据。

4. **测试环境**: 建议先在测试环境验证,确认无误后再部署到生产环境。

---

## 完成标记

- [ ] 添加必要的import语句
- [ ] 修改所有openPosition调用
- [ ] 编译通过
- [ ] 执行数据库迁移(add_indicator_fields.sql)
- [ ] 重启应用
- [ ] 验证日志输出
- [ ] 查询数据库确认数据

---

**更新时间**: 2026-01-13 19:03  
**优先级**: P1 (高优先级)  
**预计时间**: 15-30分钟
