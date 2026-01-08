# 🐛 Bug修复报告：Symbol硬编码问题

## 问题描述
配置文件中设置交易BTC（BTCUSDT），但实际订单和统计中显示的是XAUUSD（黄金）

## 根本原因
在 `RiskManagementService.java` 中发现**2处硬编码**的 `"XAUUSD"`：

### Bug位置1：checkDailyLoss() 方法（第102行）
```java
List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
        .eq(TradeOrder::getSymbol, "XAUUSD")  // ❌ 硬编码！
        .ge(TradeOrder::getCreateTime, startOfDay)
        .le(TradeOrder::getCreateTime, endOfDay)
);
```

**问题影响**：
- 风控检查日亏损时，只查询XAUUSD的订单
- 导致BTC订单的亏损不计入日亏损统计
- 风控失效！

### Bug位置2：getRiskStatistics() 方法（第195行）
```java
List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
        .eq(TradeOrder::getSymbol, "XAUUSD")  // ❌ 硬编码！
        .ge(TradeOrder::getCreateTime, startOfDay)
        .le(TradeOrder::getCreateTime, endOfDay)
);
```

**问题影响**：
- 风控统计只显示XAUUSD的今日盈亏
- BTC交易的盈亏不显示

## 修复方案

### 方案1：传入symbol参数（推荐✅）

修改 `checkDailyLoss()` 和 `getRiskStatistics()` 方法签名，传入实际交易的symbol：

```java
/**
 * 检查当日亏损（修复版）
 */
private boolean checkDailyLoss(String symbol) {  // ✅ 新增参数
    LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
    LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
    
    List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
            .eq(TradeOrder::getSymbol, symbol)  // ✅ 使用传入的symbol
            .ge(TradeOrder::getCreateTime, startOfDay)
            .le(TradeOrder::getCreateTime, endOfDay)
    );
    
    // ... 其余代码不变
}

/**
 * 获取风控统计信息（修复版）
 */
public String getRiskStatistics(String symbol) {  // ✅ 新增参数
    // ... 前面代码不变
    
    List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
            .eq(TradeOrder::getSymbol, symbol)  // ✅ 使用传入的symbol
            .ge(TradeOrder::getCreateTime, startOfDay)
            .le(TradeOrder::getCreateTime, endOfDay)
    );
    
    // ... 其余代码不变
}
```

然后修改调用处：

```java
// checkRiskBeforeTrade() 方法中
if (!checkDailyLoss(symbol)) {  // ✅ 传入symbol
    log.warn("当日亏损超限");
    return false;
}
```

```java
// TradingScheduler.java 中
String statistics = riskManagementService.getRiskStatistics(binanceSymbol);  // ✅ 传入symbol
```

### 方案2：查询所有交易对（次选）

如果希望风控统计所有交易对的数据：

```java
// 移除 .eq(TradeOrder::getSymbol, "XAUUSD") 这一行
List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
        // ✅ 不限制symbol，统计所有交易对
        .ge(TradeOrder::getCreateTime, startOfDay)
        .le(TradeOrder::getCreateTime, endOfDay)
);
```

## 推荐修复：方案1

方案1更合理，因为：
1. ✅ 每个交易对独立风控
2. ✅ 避免BTC和黄金的风控混在一起
3. ✅ 更灵活，支持多交易对

## 修复优先级：🔴 高（Critical）

这个bug会导致：
- ❌ 风控失效（日亏损统计错误）
- ❌ 统计数据错误
- ❌ 可能导致超出风控限制的交易

## 其他已修复问题

### Bug 3：价格获取硬编码 🐛

**文件**: `RealMarketDataService.java` + `MarketDataService.java`

**问题**:
- `RealMarketDataService.getGoldPriceFromBinance()` 硬编码获取 PAXGUSDT 价格
- `MarketDataService.getCurrentPrice()` 调用时没有传递正确的symbol
- 导致：配置BTC，但获取的是黄金(PAXG)价格！

**修复**:
1. ✅ 新增 `getPriceFromBinance(String symbol)` 方法，支持任意交易对
2. ✅ 更新 `MarketDataService.getCurrentPrice()` 调用新方法
3. ✅ 保留旧方法 `getGoldPriceFromBinance()` 标记为 @Deprecated

## 建议

修复后建议：
1. 删除所有历史错误数据（symbol为XAUUSD但实际是BTC的订单）
2. 重新测试确认symbol正确
3. 添加单元测试防止回归

---

## ✅ 所有修复已完成！

### 已修复的文件清单

1. ✅ `RiskManagementService.java` - 风控统计symbol硬编码
2. ✅ `TradingScheduler.java` - 调用风控统计传参
3. ✅ `RealMarketDataService.java` - 价格获取支持任意交易对
4. ✅ `MarketDataService.java` - 传递正确symbol获取价格

### 修复摘要

| Bug | 位置 | 影响 | 状态 |
|-----|------|------|------|
| Symbol硬编码 | RiskManagementService | 风控失效 | ✅ 已修复 |
| 统计symbol错误 | TradingScheduler | 数据错误 | ✅ 已修复 |
| 价格获取硬编码 | RealMarketDataService | 获取错误标的价格 | ✅ 已修复 |
| 价格传递错误 | MarketDataService | 价格数据错误 | ✅ 已修复 |

现在系统应该能正确：
- ✅ 交易BTC（而不是黄金）
- ✅ 获取BTC价格（而不是PAXG价格）
- ✅ 风控统计BTC订单（而不是黄金订单）
- ✅ 所有数据使用正确的symbol
