# 📐 Service层分层重构方案

**设计时间：** 2026-01-08 10:43 AM  
**目标：** 提高代码可维护性、可测试性和可扩展性

---

## 🎯 当前Service层结构分析

### 现有Service（14个）

```
数据服务（3个）：
├── MarketDataService           // K线数据存储
├── RealMarketDataService       // 实时数据获取
└── MLRecordService            // ML预测记录

交易执行服务（4个）：
├── BrokerTradeService         // 经纪商抽象
├── BybitTradingService        // Bybit API
├── MT4TradingService          // MT4 API
└── PaperTradingService        // 模拟交易

策略服务（3个）：
├── TradingStrategyService     // 基础策略
├── AdvancedTradingStrategyService  // 高级策略
└── AggressiveScalpingStrategy  // 激进策略

支持服务（4个）：
├── IndicatorService           // 技术指标计算
├── MLPredictionService        // ML预测
├── TradeExecutionService      // 交易执行
└── RiskManagementService      // 风险管理
```

### 存在的问题

1. **职责不清晰**
   - AdvancedTradingStrategyService包含策略+指标计算
   - TradeExecutionService混合了执行+风控
   - 难以单独测试和复用

2. **层次混乱**
   - 没有明确的分层架构
   - Service之间互相调用，依赖关系复杂
   - 难以追踪调用链路

3. **代码重复**
   - 多个Service计算相同的指标
   - 相似的交易逻辑重复实现
   - 难以统一维护

4. **扩展困难**
   - 添加新策略需要修改多个文件
   - 添加新交易平台需要大量适配
   - 违反开闭原则

---

## 🏗️ 分层架构设计

### 三层架构

```
┌─────────────────────────────────────────────────────┐
│                  应用层 (Application Layer)          │
│  TradingScheduler, TradingController                │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              领域服务层 (Domain Service Layer)        │
│  - 策略编排服务 (Strategy Orchestration)             │
│  - 风控服务 (Risk Management)                        │
│  - 交易编排服务 (Trading Orchestration)              │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              核心服务层 (Core Service Layer)          │
│  - 策略服务 (Strategy)                               │
│  - 指标服务 (Indicator)                              │
│  - 信号服务 (Signal)                                 │
│  - ML服务 (Machine Learning)                         │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│            基础设施层 (Infrastructure Layer)          │
│  - 交易平台适配器 (Broker Adapter)                    │
│  - 数据访问服务 (Data Access)                         │
│  - 外部API服务 (External API)                        │
└─────────────────────────────────────────────────────┘
```

---

## 📦 详细分层设计

### 1. 基础设施层 (infrastructure)

**职责：** 与外部系统交互，数据持久化

```
service/
├── infrastructure/
│   ├── broker/                 // 交易平台适配器
│   │   ├── BrokerAdapter.java           // 经纪商接口
│   │   ├── BybitBrokerAdapter.java      // Bybit实现
│   │   ├── MT4BrokerAdapter.java        // MT4实现
│   │   └── PaperBrokerAdapter.java      // 模拟交易实现
│   │
│   ├── data/                   // 数据访问
│   │   ├── MarketDataRepository.java    // 市场数据
│   │   ├── PositionRepository.java      // 持仓数据
│   │   └── TradeOrderRepository.java    // 订单数据
│   │
│   └── external/               // 外部API
│       ├── BybitApiClient.java          // Bybit API
│       └── MT4ApiClient.java            // MT4 API
```

**特点：**
- 纯技术实现，无业务逻辑
- 可独立测试和替换
- 统一接口，多种实现

---

### 2. 核心服务层 (core)

**职责：** 核心业务逻辑，可独立复用

```
service/
├── core/
│   ├── strategy/               // 策略服务
│   │   ├── Strategy.java                // 策略接口
│   │   ├── WilliamsStrategy.java        // Williams策略
│   │   ├── RSIStrategy.java             // RSI策略
│   │   ├── MLStrategy.java              // ML策略
│   │   └── CompositeStrategy.java       // 组合策略
│   │
│   ├── indicator/              // 技术指标
│   │   ├── TechnicalIndicator.java      // 指标接口
│   │   ├── WilliamsRCalculator.java     // Williams %R
│   │   ├── RSICalculator.java           // RSI
│   │   ├── ADXCalculator.java           // ADX
│   │   ├── MACDCalculator.java          // MACD
│   │   └── BollingerBandsCalculator.java// 布林带
│   │
│   ├── signal/                 // 信号生成
│   │   ├── TradingSignal.java           // 信号模型
│   │   ├── SignalGenerator.java         // 信号生成器接口
│   │   ├── ScoringSignalGenerator.java  // 评分信号生成器
│   │   └── PatternSignalGenerator.java  // 形态信号生成器
│   │
│   ├── pattern/                // K线形态
│   │   ├── CandlePattern.java           // 形态接口
│   │   ├── BullishEngulfing.java        // 看涨吞没
│   │   ├── Hammer.java                  // 锤子线
│   │   └── ... (其他形态)
│   │
│   └── ml/                     // 机器学习
│       ├── MLPredictor.java             // 预测器接口
│       ├── TrendPredictor.java          // 趋势预测
│       └── MLModelService.java          // 模型管理
```

**特点：**
- 单一职责
- 无状态设计
- 易于测试和组合

---

### 3. 领域服务层 (domain)

**职责：** 业务流程编排，协调核心服务

```
service/
├── domain/
│   ├── trading/                // 交易编排
│   │   ├── TradingOrchestrator.java     // 交易编排器
│   │   ├── PositionManager.java         // 持仓管理
│   │   ├── OrderExecutor.java           // 订单执行
│   │   └── TradingContext.java          // 交易上下文
│   │
│   ├── strategy/               // 策略编排
│   │   ├── StrategyOrchestrator.java    // 策略编排器
│   │   ├── StrategySelector.java        // 策略选择器
│   │   └── StrategyEvaluator.java       // 策略评估
│   │
│   └── risk/                   // 风险管理
│       ├── RiskController.java          // 风控控制器
│       ├── PositionSizeCalculator.java  // 仓位计算
│       ├── StopLossManager.java         // 止损管理
│       └── EmergencyStopHandler.java    // 紧急停止
```

**特点：**
- 协调多个核心服务
- 包含业务规则
- 有状态管理

---

## 🔄 重构步骤

### 阶段1：创建基础结构（今天）

**优先级：高**

1. 创建目录结构
2. 定义核心接口
3. 迁移简单服务

**文件清单：**
```
✓ 创建 service/infrastructure/broker/BrokerAdapter.java
✓ 创建 service/core/indicator/TechnicalIndicator.java
✓ 创建 service/core/strategy/Strategy.java
✓ 创建 service/domain/trading/TradingOrchestrator.java
```

---

### 阶段2：迁移核心服务（1-2天）

**优先级：中**

1. 迁移IndicatorService → core/indicator/*
2. 迁移策略服务 → core/strategy/*
3. 提取信号生成逻辑 → core/signal/*

**重构示例：**

**Before（AdvancedTradingStrategyService）:**
```java
@Service
public class AdvancedTradingStrategyService {
    // 包含指标计算 + 信号生成 + 策略逻辑
    public Signal mlEnhancedWilliamsStrategy(String symbol) {
        // 300行混合逻辑
    }
}
```

**After（分层设计）:**
```java
// 核心层 - 指标计算
@Service
public class WilliamsRCalculator implements TechnicalIndicator {
    public double calculate(List<Kline> klines) {
        // 纯计算逻辑
    }
}

// 核心层 - 策略
@Service
public class WilliamsStrategy implements Strategy {
    public Signal generate(MarketContext context) {
        // 纯策略逻辑
    }
}

// 领域层 - 编排
@Service
public class StrategyOrchestrator {
    public Signal executeStrategy(String symbol) {
        // 协调多个策略
    }
}
```

---

### 阶段3：重构交易执行（2-3天）

**优先级：中**

1. 创建BrokerAdapter接口
2. 重构BybitTradingService → BybitBrokerAdapter
3. 重构PaperTradingService → PaperBrokerAdapter
4. 创建TradingOrchestrator统一交易流程

---

### 阶段4：完善风控和测试（3-5天）

**优先级：低**

1. 重构RiskManagementService → domain/risk/*
2. 添加单元测试
3. 添加集成测试
4. 性能优化

---

## 📋 核心接口设计

### 1. BrokerAdapter（经纪商适配器）

```java
public interface BrokerAdapter {
    /**
     * 获取当前价格
     */
    BigDecimal getCurrentPrice(String symbol);
    
    /**
     * 下市价单
     */
    String placeMarketOrder(OrderRequest request);
    
    /**
     * 获取持仓
     */
    List<Position> getOpenPositions(String symbol);
    
    /**
     * 平仓
     */
    boolean closePosition(String positionId);
    
    /**
     * 获取账户余额
     */
    BigDecimal getAccountBalance();
}
```

---

### 2. Strategy（策略接口）

```java
public interface Strategy {
    /**
     * 生成交易信号
     */
    TradingSignal generate(MarketContext context);
    
    /**
     * 策略名称
     */
    String getName();
    
    /**
     * 策略权重（用于组合策略）
     */
    int getWeight();
}
```

---

### 3. TechnicalIndicator（技术指标接口）

```java
public interface TechnicalIndicator<T> {
    /**
     * 计算指标值
     */
    T calculate(List<Kline> klines);
    
    /**
     * 指标名称
     */
    String getName();
    
    /**
     * 所需K线数量
     */
    int getRequiredPeriods();
}
```

---

### 4. TradingOrchestrator（交易编排器）

```java
@Service
public class TradingOrchestrator {
    
    @Autowired
    private StrategyOrchestrator strategyOrchestrator;
    
    @Autowired
    private RiskController riskController;
    
    @Autowired
    private BrokerAdapter brokerAdapter;
    
    /**
     * 执行完整交易流程
     */
    public void executeTradingCycle(String symbol) {
        // 1. 风控检查
        if (!riskController.preTradeCheck()) {
            return;
        }
        
        // 2. 生成信号
        TradingSignal signal = strategyOrchestrator.generateSignal(symbol);
        
        // 3. 仓位计算
        BigDecimal positionSize = riskController.calculatePositionSize();
        
        // 4. 执行交易
        if (signal.isValid()) {
            brokerAdapter.placeMarketOrder(createOrder(signal, positionSize));
        }
    }
}
```

---

## 🎯 重构的好处

### 1. 可维护性提升

**Before:**
```java
// 300行混合逻辑，难以理解
public Signal mlEnhancedWilliamsStrategy(String symbol) {
    // 指标计算
    // 信号生成
    // 风控检查
    // 订单执行
}
```

**After:**
```java
// 每个类职责单一，易于理解
williamsCalculator.calculate(klines);
rsiCalculator.calculate(klines);
strategyOrchestrator.generate(context);
riskController.check();
brokerAdapter.placeOrder(order);
```

---

### 2. 可测试性提升

**Before:**
```java
// 难以测试，依赖太多
@Test
public void testStrategy() {
    // 需要mock数据库、API、其他服务
}
```

**After:**
```java
// 单元测试简单
@Test
public void testWilliamsCalculator() {
    List<Kline> klines = createTestData();
    double result = calculator.calculate(klines);
    assertEquals(-65.3, result, 0.1);
}
```

---

### 3. 可扩展性提升

**添加新策略：**
```java
// 只需实现Strategy接口
@Service
public class MyNewStrategy implements Strategy {
    @Override
    public TradingSignal generate(MarketContext context) {
        // 新策略逻辑
    }
}

// 自动注册到StrategyOrchestrator
```

**添加新交易平台：**
```java
// 只需实现BrokerAdapter接口
@Service
public class BinanceBrokerAdapter implements BrokerAdapter {
    // Binance API实现
}

// 无需修改其他代码
```

---

## 📊 重构优先级

### 立即开始（阶段1）

1. ✅ 创建目录结构
2. ✅ 定义核心接口
3. ✅ 编写重构文档

### 1-2天内（阶段2）

4. 迁移指标计算逻辑
5. 重构策略服务
6. 提取信号生成

### 1周内（阶段3-4）

7. 重构交易执行
8. 完善风控服务
9. 添加测试

---

## 🔧 立即行动

**是否开始阶段1：创建基础结构？**

包括：
1. 创建目录结构
2. 定义BrokerAdapter接口
3. 定义Strategy接口
4. 定义TechnicalIndicator接口
5. 创建TradingOrchestrator框架

预计时间：1-2小时

**告诉我是否立即开始重构！** 🚀
