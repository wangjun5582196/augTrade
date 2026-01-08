# 🚀 新架构使用指南

> AugTrade交易系统 - 基于策略模式的可扩展交易架构

---

## 📖 快速开始

### 1. 使用组合策略（推荐）

```java
@Service
public class MyTradingService {
    
    @Autowired
    private StrategyOrchestrator orchestrator;
    
    public void trade(String symbol) {
        // 生成综合交易信号
        TradingSignal signal = orchestrator.generateSignal(symbol);
        
        if (signal.isBuy() && signal.getStrength() >= 70) {
            log.info("🚀 买入信号: {}", signal.getReason());
            log.info("   强度: {}, 得分: {}", signal.getStrength(), signal.getScore());
            
            // 执行交易
            executeBuy(symbol, signal.getSuggestedStopLoss(), signal.getSuggestedTakeProfit());
        }
    }
}
```

---

### 2. 使用单个策略

```java
@Autowired
private StrategyOrchestrator orchestrator;

@Autowired
private BalancedAggressiveStrategy aggressiveStrategy;

public void aggressiveTrade(String symbol) {
    // 使用激进策略
    TradingSignal signal = orchestrator.generateSignalWithStrategy(
        symbol,
        aggressiveStrategy
    );
    
    if (signal.isValid()) {
        executeTradeWithSignal(signal);
    }
}
```

---

### 3. 对比多个策略

```java
public void compareStrategies(String symbol) {
    // 获取市场上下文
    MarketContext context = orchestrator.getMarketContext(symbol, 100);
    
    // 测试不同策略
    TradingSignal rsiSignal = rsiStrategy.generateSignal(context);
    TradingSignal williamsSignal = williamsStrategy.generateSignal(context);
    TradingSignal compositeSignal = compositeStrategy.generateSignal(context);
    
    // 对比结果
    log.info("RSI策略: {} (强度:{})", rsiSignal.getType(), rsiSignal.getStrength());
    log.info("Williams策略: {} (强度:{})", williamsSignal.getType(), williamsSignal.getStrength());
    log.info("组合策略: {} (强度:{}, 得分:{})", 
        compositeSignal.getType(), 
        compositeSignal.getStrength(), 
        compositeSignal.getScore());
}
```

---

## 🔧 创建自定义策略

### 步骤1：实现Strategy接口

```java
package com.ltp.peter.augtrade.service.core.strategy;

import org.springframework.stereotype.Service;

@Service
public class MyCustomStrategy implements Strategy {
    
    @Autowired
    private RSICalculator rsiCalculator;
    
    @Autowired
    private MACDCalculator macdCalculator;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        // 1. 计算指标
        Double rsi = rsiCalculator.calculate(context.getKlines());
        MACDResult macd = macdCalculator.calculate(context.getKlines());
        
        // 2. 实现交易逻辑
        if (rsi != null && rsi < 25 && macd != null && macd.isBullish()) {
            return TradingSignal.builder()
                .type(TradingSignal.SignalType.BUY)
                .strength(85)
                .strategyName(getName())
                .reason("RSI深度超卖且MACD多头")
                .symbol(context.getSymbol())
                .currentPrice(context.getCurrentPrice())
                .build();
        }
        
        // 3. 默认观望
        return TradingSignal.builder()
            .type(TradingSignal.SignalType.HOLD)
            .strategyName(getName())
            .build();
    }
    
    @Override
    public String getName() {
        return "MyCustom";
    }
    
    @Override
    public int getWeight() {
        return 7;  // 权重1-10
    }
    
    @Override
    public String getDescription() {
        return "我的自定义策略 - RSI+MACD组合";
    }
}
```

### 步骤2：Spring自动发现

策略会被自动注入到CompositeStrategy中！

```java
// 无需手动注册，Spring自动装配
@Autowired
private List<Strategy> strategies;  // 包含你的MyCustomStrategy
```

---

## 📊 可用组件

### 指标计算器（5个）

```java
@Autowired private RSICalculator rsiCalculator;
@Autowired private WilliamsRCalculator williamsCalculator;
@Autowired private ADXCalculator adxCalculator;
@Autowired private MACDCalculator macdCalculator;
@Autowired private BollingerBandsCalculator bollingerCalculator;

// 使用示例
Double rsi = rsiCalculator.calculate(klines);
Double williams = williamsCalculator.calculate(klines);
Double adx = adxCalculator.calculate(klines);
MACDResult macd = macdCalculator.calculate(klines);
BollingerBands bb = bollingerCalculator.calculate(klines);
```

---

### 现成策略（5个）

| 策略 | 权重 | 特点 | 适用场景 |
|------|------|------|----------|
| RSIStrategy | 9 | 最可靠 | 震荡市场 |
| WilliamsStrategy | 8 | 快速响应 | 短线交易 |
| BalancedAggressiveStrategy | 7 | 评分系统 | 激进交易 |
| BollingerBreakoutStrategy | 6 | 波动率 | 突破行情 |
| CompositeStrategy | 10 | 组合所有 | 稳健交易 |

```java
@Autowired private RSIStrategy rsiStrategy;
@Autowired private WilliamsStrategy williamsStrategy;
@Autowired private BalancedAggressiveStrategy aggressiveStrategy;
@Autowired private BollingerBreakoutStrategy bollingerStrategy;
@Autowired private CompositeStrategy compositeStrategy;
```

---

## 💡 最佳实践

### 1. 稳健交易者

```java
// 使用组合策略 + 高阈值
TradingSignal signal = orchestrator.generateSignal("BTCUSDT");

if (signal.isValid() && 
    signal.getStrength() >= 80 &&  // 高信号强度
    signal.getScore() >= 20) {     // 高综合得分
    
    executeTrade(signal);
}
```

**特点：** 高胜率，低频率

---

### 2. 激进交易者

```java
// 使用激进策略 + 低阈值
TradingSignal signal = orchestrator.generateSignalWithStrategy(
    "BTCUSDT",
    balancedAggressiveStrategy
);

if (signal.isValid() && signal.getStrength() >= 60) {
    executeTrade(signal);
}
```

**特点：** 中胜率，高频率

---

### 3. 策略组合

```java
// 创建自定义策略组合
public TradingSignal generateCustomSignal(String symbol) {
    MarketContext context = orchestrator.getMarketContext(symbol, 100);
    
    // 获取多个策略信号
    TradingSignal rsi = rsiStrategy.generateSignal(context);
    TradingSignal williams = williamsStrategy.generateSignal(context);
    
    // 自定义组合逻辑：两个都看涨才买入
    if (rsi.isBuy() && williams.isBuy()) {
        return TradingSignal.builder()
            .type(SignalType.BUY)
            .strength((rsi.getStrength() + williams.getStrength()) / 2)
            .reason("RSI和Williams双重确认")
            .build();
    }
    
    return TradingSignal.builder().type(SignalType.HOLD).build();
}
```

---

## 🧪 测试示例

### 单元测试

```java
@Test
public void testRSICalculator() {
    // 准备测试数据
    List<Kline> testKlines = createTestKlines();
    
    // 测试计算
    RSICalculator calculator = new RSICalculator();
    Double rsi = calculator.calculate(testKlines);
    
    // 验证结果
    assertNotNull(rsi);
    assertTrue(rsi >= 0 && rsi <= 100);
}

@Test
public void testRSIStrategy() {
    // Mock依赖
    when(rsiCalculator.calculate(any())).thenReturn(25.0);
    
    // 执行策略
    TradingSignal signal = rsiStrategy.generateSignal(context);
    
    // 验证结果
    assertEquals(SignalType.BUY, signal.getType());
    assertTrue(signal.getStrength() > 0);
}
```

---

### 集成测试

```java
@SpringBootTest
public class StrategyIntegrationTest {
    
    @Autowired
    private StrategyOrchestrator orchestrator;
    
    @Test
    public void testGenerateSignal() {
        TradingSignal signal = orchestrator.generateSignal("BTCUSDT");
        
        assertNotNull(signal);
        assertNotNull(signal.getType());
        assertTrue(signal.getStrength() >= 0);
    }
}
```

---

## 📋 检查清单

### 使用新架构前的准备

- [ ] 阅读本文档
- [ ] 了解可用的指标和策略
- [ ] 确定交易风格（稳健/激进）
- [ ] 选择合适的策略
- [ ] 设置信号阈值
- [ ] 测试验证

---

## ⚙️ 配置选项

### application.yml

```yaml
trading:
  strategy:
    # 启用新架构
    use-new-architecture: true
    
    # 信号阈值
    min-signal-strength: 70
    min-composite-score: 15
    
    # 组合策略配置
    composite:
      enabled-strategies:
        - RSIStrategy
        - WilliamsStrategy
        - BalancedAggressiveStrategy
        - BollingerBreakoutStrategy
```

---

## 🔗 相关链接

- [完整重构报告](REFACTORING_COMPLETE.md)
- [架构总结](REFACTORING_SUMMARY.md)
- [阶段1报告](REFACTORING_PHASE1_COMPLETE.md)
- [阶段2报告](REFACTORING_PHASE2_COMPLETE.md)
- [阶段3报告](REFACTORING_PHASE3_COMPLETE.md)

---

## 📞 获取帮助

1. 📖 查看JavaDoc注释
2. 📚 阅读相关文档
3. 🔍 查看代码示例
4. 💬 联系开发团队

---

## 🎉 开始使用！

```java
@Autowired
private StrategyOrchestrator orchestrator;

// 就这么简单！
TradingSignal signal = orchestrator.generateSignal("BTCUSDT");
```

**祝交易顺利！** 🚀📈💰

---

*最后更新：2026-01-08 11:26 AM*
