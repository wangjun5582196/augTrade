# 🎯 策略架构优化方案

## 📋 当前问题

目前策略分散在多个地方，切换策略需要修改多处代码：
- `TradingScheduler` - 调用策略
- `SimplifiedTrendStrategy` - 趋势策略
- `AggressiveScalpingStrategy` - 激进策略  
- `CompositeStrategy` - 组合策略
- `BalancedAggressiveStrategy` - 平衡策略

**痛点**：想换策略需要改很多地方，容易出错。

---

## ✨ 理想架构

### 方案1：单一策略配置文件（推荐）

创建一个**策略工厂类**，通过配置文件切换：

```
application.yml
├── trading.strategy.active: "balanced-aggressive"  # 只改这里！
└── 自动加载对应策略
```

**优点**：
- ✅ 只需修改配置文件一行
- ✅ 不需要重新编译代码
- ✅ 支持运行时热切换（可选）
- ✅ 易于回滚和A/B测试

### 方案2：统一策略接口

所有策略实现同一个接口，集中管理：

```java
TradingStrategyFactory
├── getStrategy("balanced-aggressive") → BalancedAggressiveStrategy
├── getStrategy("simplified-trend") → SimplifiedTrendStrategy
└── getStrategy("composite") → CompositeStrategy
```

---

## 🔧 具体实现方案

### 1. 创建策略工厂类

```java
@Service
public class TradingStrategyFactory {
    
    @Value("${trading.strategy.active:balanced-aggressive}")
    private String activeStrategy;
    
    @Autowired
    private SimplifiedTrendStrategy simplifiedTrendStrategy;
    
    @Autowired
    private AggressiveScalpingStrategy aggressiveScalpingStrategy;
    
    @Autowired
    private CompositeStrategy compositeStrategy;
    
    /**
     * 获取当前激活的策略
     */
    public TradingStrategy getActiveStrategy() {
        switch (activeStrategy) {
            case "simplified-trend":
                return simplifiedTrendStrategy;
            
            case "balanced-aggressive":
                return aggressiveScalpingStrategy;
            
            case "composite":
                return compositeStrategy;
            
            default:
                log.warn("未知策略: {}, 使用默认balanced-aggressive", activeStrategy);
                return aggressiveScalpingStrategy;
        }
    }
    
    /**
     * 统一接口：生成交易信号
     */
    public Signal generateSignal(String symbol) {
        TradingStrategy strategy = getActiveStrategy();
        log.info("📊 使用策略: {}", activeStrategy);
        return strategy.execute(symbol);
    }
}
```

### 2. 修改TradingScheduler

```java
@Autowired
private TradingStrategyFactory strategyFactory;  // 只需这一个！

private void executeBybitStrategy() {
    // ... 获取价格等前置逻辑 ...
    
    // 🔥 关键：只需调用工厂，自动使用配置的策略
    Signal signal = strategyFactory.generateSignal(bybitSymbol);
    
    log.info("📊 策略信号: {}", signal);
    
    // ... 后续交易逻辑 ...
}
```

### 3. application.yml配置

```yaml
trading:
  strategy:
    # 🎯 切换策略只需修改这一行！
    active: balanced-aggressive
    
    # 可选策略：
    # - simplified-trend       (精简趋势策略，仅ADX+ATR+EMA)
    # - balanced-aggressive    (平衡激进策略，多指标综合)
    # - composite              (组合策略，包含多个子策略)
    
    enabled: true
    interval: 60000
```

---

## 📊 策略对比

| 策略名称 | 适用场景 | 优势 | 劣势 |
|---------|---------|------|------|
| **simplified-trend** | 强趋势市场 | 简单稳健，误信号少 | 震荡市无法交易 |
| **balanced-aggressive** | 中等趋势+震荡 | 综合评分，灵活 | 参数多，需调优 |
| **composite** | 所有市场 | 多策略投票，稳定 | 计算复杂，可能错过机会 |

---

## 🎯 推荐实施步骤

### 第一步：创建策略工厂（今天完成）
- 创建 `TradingStrategyFactory.java`
- 统一所有策略的接口（`execute(symbol)` 返回 `Signal`）

### 第二步：修改TradingScheduler（今天完成）
- 注入 `TradingStrategyFactory`
- 替换现有策略调用为 `strategyFactory.generateSignal()`

### 第三步：配置文件支持（今天完成）
- 在 `application.yml` 添加 `trading.strategy.active`
- 测试切换不同策略

### 第四步：优化策略（明天）
- 根据今天的交易数据，调整参数
- A/B测试不同策略表现

---

## 💡 高级特性（可选）

### 1. 运行时热切换
```java
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {
    
    @PostMapping("/switch")
    public String switchStrategy(@RequestParam String strategyName) {
        strategyFactory.setActiveStrategy(strategyName);
        return "策略已切换至: " + strategyName;
    }
}
```

### 2. 策略回测对比
```java
public Map<String, BacktestResult> compareStrategies() {
    Map<String, BacktestResult> results = new HashMap<>();
    
    for (String strategy : Arrays.asList("simplified-trend", "balanced-aggressive", "composite")) {
        strategyFactory.setActiveStrategy(strategy);
        results.put(strategy, runBacktest());
    }
    
    return results;
}
```

### 3. 自动策略选择
```java
public String selectBestStrategy(MarketContext context) {
    double adx = context.getIndicatorAsDouble("ADX");
    
    if (adx > 30) {
        return "simplified-trend";  // 强趋势用简单策略
    } else if (adx >= 20) {
        return "balanced-aggressive";  // 中等趋势用综合策略
    } else {
        return "composite";  // 震荡市用组合策略
    }
}
```

---

## 📝 代码清单

需要创建/修改的文件：

1. ✅ **新建** `TradingStrategyFactory.java` - 策略工厂
2. ✅ **修改** `TradingScheduler.java` - 使用工厂
3. ✅ **修改** `application.yml` - 添加策略配置
4. ✅ **统一** 各策略接口（确保都有`execute(symbol)`方法）

---

## 🚀 立即行动

想要我现在帮您实现这个架构吗？

**选项**：
1. **立即实施** - 我现在就创建策略工厂并重构代码
2. **先看代码** - 我展示完整代码，您确认后再实施
3. **分步实施** - 先创建工厂，逐步迁移

请告诉我您的选择！

---

**优势总结**：
- 🎯 **一处配置** - 只需修改 `application.yml` 一行
- 🔄 **易于切换** - 不需要重新编译
- 📊 **便于对比** - 可以轻松A/B测试
- 🛡️ **降低风险** - 随时回滚到之前的策略
