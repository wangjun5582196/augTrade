# 🧹 代码清理计划（2026-01-22）

## 📋 清理目标

1. 删除未使用的策略类和方法
2. 移除冗余配置
3. 整理文档，删除过时内容
4. 简化代码结构

---

## 🔍 分析结果

### 当前使用的策略（保留）

✅ **TradingStrategyFactory** - 策略工厂（新架构核心）
- executeSimplifiedTrend()
- executeBalancedAggressive()
- executeComposite()

✅ **SimplifiedTrendStrategy** - 精简趋势策略
- execute()

✅ **AggressiveScalpingStrategy.balancedAggressiveStrategy()** - 平衡激进策略
- 唯一被使用的方法

✅ **StrategyOrchestrator + CompositeStrategy** - 组合策略

---

## ❌ 需要删除的代码

### 1. AggressiveScalpingStrategy - 未使用的方法（8个）

**删除原因**：只有`balancedAggressiveStrategy()`被使用，其他都是历史遗留

```java
❌ momentumBreakoutStrategy()        // 动量突破策略
❌ rsiReversalStrategy()             // RSI反转策略
❌ relaxedWilliamsStrategy()         // 宽松Williams策略
❌ channelBreakoutStrategy()         // 通道突破策略
❌ macdCrossStrategy()               // MACD交叉策略
❌ superAggressiveStrategy()         // 超激进策略
❌ bollingerBreakoutStrategy()       // 布林带突破策略
❌ simplifiedMLStrategy()            // 简化ML策略
```

**保留**：
- ✅ balancedAggressiveStrategy() - 当前使用
- ✅ analyzeCandlePattern() - 被balancedAggressiveStrategy调用

---

### 2. AdvancedTradingStrategyService - 整个类删除

**删除原因**：所有方法都未被使用，已被新架构替代

```java
❌ adxStochasticStrategy()           // ADX+随机指标
❌ cciVwapStrategy()                 // CCI+VWAP
❌ ichimokuStrategy()                // 一目均衡表
❌ williamsAtrStrategy()             // Williams+ATR
❌ multiIndicatorScoring()           // 多指标评分
❌ mlEnhancedWilliamsStrategy()      // ML增强Williams
❌ calculateDynamicStopLoss()        // 动态止损计算
```

**整个文件删除**：`AdvancedTradingStrategyService.java`

---

### 3. TradingStrategyService - 部分方法删除

**删除原因**：已被策略工厂替代

```java
❌ executeShortTermStrategy()        // 短线策略（已被工厂替代）
❌ executeBreakoutStrategy()         // 突破策略（未使用）
```

**保留**：
- ✅ calculateStopLevels() - 可能被其他地方调用

**建议**：标记为@Deprecated，未来完全移除

---

### 4. BrokerTradeService - 整个类删除

**删除原因**：用于对接多个券商，当前只用Bybit

```java
❌ placeBinanceOrder()               // Binance下单
❌ placeIBOrder()                    // 盈透下单
❌ placeCtpOrder()                   // CTP下单
❌ queryBinanceOrder()               // 查询订单
❌ cancelBinanceOrder()              // 取消订单
```

**整个文件删除**：`BrokerTradeService.java`

---

### 5. RealMarketDataService - 部分方法删除

**删除原因**：未使用的数据源

```java
❌ getGoldPriceFromAlphaVantage()    // AlphaVantage数据源
❌ getGoldPriceFromJin10()           // 金十数据（已过时）
❌ getGoldDetailFromJin10()          // 金十详情
❌ getPriceFromBinance()             // Binance价格（未使用）
❌ getGoldPriceFromBinance()         // Binance黄金价格
```

**保留**：
- ✅ getKlineFromBinance() - 用于获取K线
- ✅ getCurrentPrice() - 统一价格接口

---

### 6. IndicatorService - 未使用的指标（4个）

**删除原因**：这些指标未被任何策略使用

```java
❌ calculateCCI()                    // CCI指标
❌ calculateVWAP()                   // VWAP指标
❌ calculateIchimoku()               // 一目均衡表
❌ calculateHL()                     // 高低点（私有方法）
```

**保留**：
- ✅ calculateSMA() - 被其他指标使用
- ✅ calculateEMA() - EMA策略使用
- ✅ calculateRSI() - 组合策略使用
- ✅ calculateMACD() - 保留备用
- ✅ calculateBollingerBands() - 平衡策略使用
- ✅ calculateATR() - 所有策略使用
- ✅ calculateStochastic() - 保留备用
- ✅ calculateADX() - 所有策略使用
- ✅ calculateWilliamsR() - 平衡策略使用
- ✅ isGoldenCross() - 保留备用
- ✅ isDeathCross() - 保留备用

---

## 📝 配置清理

### application.yml - 删除无用配置

```yaml
❌ trading.binance.symbol: BTCUSDT      # 不再使用Binance
❌ trading.binance.interval: 1m         # 不再使用Binance
❌ trading.gold.symbol: BTCUSDT         # 错误配置
❌ trading.data-collector.source        # 固定为bybit
```

**保留**：
- ✅ bybit.* - Bybit相关配置
- ✅ trading.strategy.active - 策略工厂配置
- ✅ trading.risk.* - 风控配置
- ✅ feishu.* - 飞书通知配置

---

## 📚 文档清理

### 删除过时文档

```
❌ MEMORY_VARIABLE_ISSUE.md          # 已解决的历史问题
❌ TRADE_COUNT_ISSUE_ANALYSIS.md     # 已解决的历史问题
❌ STATE_PERSISTENCE_FIX_20260116.md # 已解决的历史问题
❌ STRATEGY_BUG_ANALYSIS_20260116.md # 已解决的历史问题
❌ VERIFICATION_REPORT_20260116.md   # 过时验证报告
❌ FIXES_20260115_*.md               # 过时修复记录
❌ FIXES_20260116_*.md               # 过时修复记录
❌ NO_TRADES_ANALYSIS_20260119.md    # 过时分析
❌ NO_TRADING_ANALYSIS_20260120.md   # 过时分析
```

### 保留重要文档

```
✅ TRADE_REVIEW_20260120_20260121.md      # 最新复盘
✅ FIXES_20260122_COMPLETED.md            # 最新修复
✅ TRAILING_STOP_FIX_20260122.md          # 最新修复
✅ STRATEGY_FACTORY_GUIDE.md              # 使用指南
✅ STRATEGY_ARCHITECTURE_PROPOSAL.md      # 架构方案
✅ HOW_TO_ENABLE_NEW_STRATEGY.md          # 策略启用指南
✅ COMPLETE_OPTIMIZATION_20260121.md      # 优化记录
✅ INDICATOR_OPTIMIZATION_RECOMMENDATIONS.md  # 指标优化建议
✅ README.md                               # 项目说明（需更新）
```

---

## 🎯 清理步骤

### 第一步：删除未使用的策略方法

1. **AggressiveScalpingStrategy.java**
   - 删除8个未使用方法
   - 保留balancedAggressiveStrategy()和analyzeCandlePattern()

2. **删除整个类**
   - AdvancedTradingStrategyService.java
   - BrokerTradeService.java

3. **TradingStrategyService.java**
   - 标记方法为@Deprecated
   - 添加注释说明替代方案

### 第二步：清理IndicatorService

- 删除4个未使用的指标方法
- 保留核心指标（ADX、ATR、EMA、RSI、Williams、布林带）

### 第三步：清理RealMarketDataService

- 删除5个未使用的数据源方法
- 保留Bybit相关方法

### 第四步：清理配置文件

- 删除Binance相关配置
- 删除过时配置项
- 整理注释说明

### 第五步：清理文档

- 删除12个过时文档
- 创建新的README.md
- 整理项目结构说明

---

## 📊 清理前后对比

### 代码文件数量

| 类型 | 清理前 | 清理后 | 减少 |
|------|--------|--------|------|
| Service类 | 17个 | 15个 | -2个 |
| 策略方法 | 25个 | 12个 | -13个 |
| 指标方法 | 15个 | 11个 | -4个 |
| 配置项 | 50+ | 35+ | -15+ |
| 文档文件 | 25个 | 13个 | -12个 |

### 代码行数（预估）

- **删除前**：约15,000行
- **删除后**：约10,000行
- **减少**：约33%

---

## ✅ 清理后的项目结构

```
src/main/java/com/ltp/peter/augtrade/
├── controller/                 # 控制器层
├── entity/                     # 实体类
├── mapper/                     # 数据访问层
├── service/
│   ├── TradingStrategyFactory.java      ✅ 策略工厂（核心）
│   ├── SimplifiedTrendStrategy.java     ✅ 精简趋势策略
│   ├── AggressiveScalpingStrategy.java  ✅ 平衡激进策略（精简版）
│   ├── BybitTradingService.java         ✅ Bybit交易服务
│   ├── PaperTradingService.java         ✅ 模拟交易服务
│   ├── IndicatorService.java            ✅ 指标服务（精简版）
│   ├── MarketDataService.java           ✅ 市场数据服务
│   ├── RealMarketDataService.java       ✅ 实时数据服务（精简版）
│   ├── TradeExecutionService.java       ✅ 交易执行服务
│   ├── RiskManagementService.java       ✅ 风控服务
│   ├── FeishuNotificationService.java   ✅ 飞书通知服务
│   ├── BacktestService.java             ✅ 回测服务
│   ├── MLPredictionService.java         ✅ ML预测服务
│   ├── MLRecordService.java             ✅ ML记录服务
│   └── core/                            ✅ 核心策略框架
│       ├── indicator/                   # 技术指标
│       ├── signal/                      # 交易信号
│       └── strategy/                    # 策略框架
└── task/
    └── TradingScheduler.java            ✅ 定时任务调度器
```

---

## 🚀 执行清理

准备好后执行以下命令：

```bash
# 1. 备份当前代码
git add .
git commit -m "Backup before cleanup"

# 2. 删除文件（手动执行）
rm src/main/java/com/ltp/peter/augtrade/service/AdvancedTradingStrategyService.java
rm src/main/java/com/ltp/peter/augtrade/service/BrokerTradeService.java

# 3. 删除过时文档
rm MEMORY_VARIABLE_ISSUE.md
rm TRADE_COUNT_ISSUE_ANALYSIS.md
rm STATE_PERSISTENCE_FIX_20260116.md
# ... 其他过时文档

# 4. 提交清理
git add .
git commit -m "Code cleanup: Remove unused strategies and configurations"
```

---

## 💡 清理后的好处

1. **代码更简洁**
   - 减少33%代码量
   - 更容易维护

2. **结构更清晰**
   - 只保留实际使用的策略
   - 策略工厂统一管理

3. **性能更好**
   - 减少不必要的计算
   - 加快编译速度

4. **降低复杂度**
   - 新人更容易理解
   - 减少潜在bug

---

**创建时间**: 2026-01-22 16:18  
**状态**: 📋 计划已制定，等待执行确认
