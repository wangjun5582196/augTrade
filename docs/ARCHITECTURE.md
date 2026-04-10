# AugTrade 系统技术文档

> 版本：v3.2 | 更新日期：2026-04-07 | 交易品种：XAUUSDT 黄金永续合约

---

## 目录

1. [技术栈](#1-技术栈)
2. [系统架构](#2-系统架构)
3. [请求链路](#3-请求链路)
4. [包结构](#4-包结构)
5. [策略架构与设计模式](#5-策略架构与设计模式)
6. [CompositeStrategy v3.2 详解](#6-compositestrategy-v32-详解)
7. [技术指标层](#7-技术指标层)
8. [风险管理](#8-风险管理)
9. [数据模型](#9-数据模型)
10. [REST API](#10-rest-api)
11. [配置参考](#11-配置参考)
12. [数据库](#12-数据库)
13. [回测框架](#13-回测框架)
14. [编码规范](#14-编码规范)

---

## 1. 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17+ |
| 框架 | Spring Boot | 3.1.5 |
| ORM | MyBatis-Plus | 3.5.7 |
| 数据库 | MySQL | 8.x |
| HTTP 客户端 | OKHttp3 | 4.12.0 |
| JSON | Gson | - |
| 数学计算 | Apache Commons Math | 3.6.1 |
| 构建工具 | Maven | - |
| 通知 | Feishu/Lark Webhook | - |
| 交易所 | Binance Futures (XAUUSDT) | - |

---

## 2. 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                    REST API Controller 层                     │
│   TradingController · DashboardController · BacktestController│
│   LiveTradingController · PaperTradingController · ...       │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│                   调度层 TradingScheduler                     │
│              @Scheduled 每 60s 触发完整交易流程               │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│              策略编排层 StrategyOrchestrator                   │
│   ① 从 DB 读取 K 线  ② 计算全部指标  ③ 构建 MarketContext    │
│   ④ 调用活跃策略生成信号  ⑤ 写入宏观趋势（7d/24h/5h）        │
└──────────┬───────────────────┬───────────────────────────────┘
           │                   │
┌──────────▼──────────┐ ┌──────▼──────────────────────────────┐
│    策略层 Strategy   │ │      指标层 Indicator Calculators    │
│  CompositeStrategy  │ │  16 个无状态计算器                    │
│  三层串联过滤        │ │  ADX·ATR·Bollinger·EMA·HMA·RSI·...  │
│  8 个子策略 Bean     │ │  结果注入 MarketContext.indicators    │
└──────────┬──────────┘ └──────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────┐
│           风控层 RiskManagementService                        │
│   持仓规模 · 日亏损 · 最大回撤 · 亏损冷却期                   │
└──────────┬──────────────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────┐
│           执行层 Broker Adapter                              │
│   BinanceFuturesTradingService (XAUUSDT 合约)                │
│   BinanceTradingService (现货) · PaperTradingService (模拟)  │
└──────────┬──────────────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────┐
│           持久层 MyBatis-Plus + MySQL                        │
│   t_kline · t_trade_order · t_position · t_backtest_result  │
│   t_backtest_trade · t_ml_prediction_record · t_trading_state│
└─────────────────────────────────────────────────────────────┘
```

**安全开关**：实盘默认关闭，`binance.futures.live-mode: false`，`PaperTradingService` 为默认执行器。

---

## 3. 请求链路

```
TradingScheduler.checkAndExecuteTrade()  [每 60s]
  └─ TradingStrategyService
       └─ StrategyOrchestrator.generateSignalWithContext(symbol)
            ├─ MarketDataService.getKlines()          // 从 DB 读取最新 K 线
            ├─ [TechnicalIndicator×16].calculate()    // 计算所有指标
            ├─ calculateMacroTrend()                  // 7d/24h/5h 宏观趋势
            └─ MarketContext (Builder 构建，不可变)
                 └─ CompositeStrategy.generateSignal(context)
                      ├─ Layer 1: KeyLevelCalculator  // 价格结构
                      ├─ Layer 2: ADX + MacroTrend + ST/EMA/HMA
                      └─ Layer 3: WilliamsR + CandlePattern
       ├─ RiskManagementService.checkRiskBeforeTrade()
       ├─ BinanceFuturesTradingService.executeOrder() // live-mode=true 才执行
       └─ FeishuNotificationService.notify()
```

---

## 4. 包结构

```
src/main/java/com/ltp/peter/augtrade/
├── AugTradeApplication.java
├── strategy/
│   ├── core/
│   │   ├── Strategy.java                  # 策略接口（核心抽象）
│   │   ├── CompositeStrategy.java         # 三层串联策略 v3.2
│   │   ├── StrategyOrchestrator.java      # 策略编排器（总入口）
│   │   ├── MarketContext.java             # 不可变市场上下文
│   │   ├── MarketRegimeDetector.java      # 市场制度检测
│   │   ├── BalancedAggressiveStrategy.java
│   │   ├── BollingerBreakoutStrategy.java
│   │   ├── RSIStrategy.java
│   │   ├── RangingMarketStrategy.java
│   │   ├── SRRejectionScalpingStrategy.java
│   │   ├── SupertrendStrategy.java        # isEnabled()=false（v3 已内化）
│   │   ├── TrendFilterStrategy.java       # isEnabled()=false（v3 已内化）
│   │   ├── VWAPStrategy.java              # weight=0（已禁用）
│   │   └── WilliamsStrategy.java          # isEnabled()=false（v3 已内化）
│   ├── TradingStrategyFactory.java        # 策略选择工厂
│   ├── TradingStrategyService.java
│   ├── SimplifiedTrendStrategy.java
│   ├── AggressiveScalpingStrategy.java
│   └── signal/
│       └── TradingSignal.java
├── indicator/                             # 16 个无状态指标计算器
│   ├── TechnicalIndicator.java            # 指标接口
│   ├── IndicatorService.java
│   ├── ADXCalculator.java
│   ├── ATRCalculator.java
│   ├── BollingerBandsCalculator.java
│   ├── CandlePatternAnalyzer.java
│   ├── EMACalculator.java
│   ├── HMACalculator.java
│   ├── KeyLevelCalculator.java            # S/R 关键位（Layer 1 核心）
│   ├── MACDCalculator.java
│   ├── MomentumCalculator.java
│   ├── OBVCalculator.java
│   ├── RSICalculator.java
│   ├── StochRSICalculator.java
│   ├── SupertrendCalculator.java
│   ├── SwingPointCalculator.java
│   ├── VWAPCalculator.java
│   ├── VolumeBreakoutCalculator.java
│   └── WilliamsRCalculator.java
├── trading/
│   ├── broker/
│   │   ├── BrokerAdapter.java             # 券商适配器接口
│   │   ├── BinanceFuturesTradingService.java  # XAUUSDT 合约（主执行器）
│   │   ├── BinanceTradingService.java     # 现货
│   │   ├── BinanceLiveAdapter.java
│   │   ├── BinanceFuturesAdapter.java
│   │   ├── BybitTradingService.java
│   │   └── OrderRequest.java
│   ├── execution/
│   │   ├── TradeExecutionService.java
│   │   └── PaperTradingService.java       # 模拟交易（默认执行器）
│   └── risk/
│       └── RiskManagementService.java
├── scheduler/
│   ├── TradingScheduler.java
│   └── StartupDataLoader.java
├── market/
│   ├── MarketDataService.java
│   ├── RealMarketDataService.java
│   ├── BinanceHistoricalDataFetcher.java
│   └── OkxKlineDataFetcher.java
├── backtest/
│   └── BacktestService.java
├── ml/
│   ├── MLPredictionService.java
│   ├── MLPredictionEnhancedService.java
│   └── MLRecordService.java
├── notification/
│   └── FeishuNotificationService.java
├── state/
│   └── TradingStateService.java
├── entity/                                # MyBatis-Plus 实体
│   ├── Kline.java
│   ├── Position.java
│   ├── TradeOrder.java
│   ├── BacktestResult.java
│   ├── BacktestTrade.java
│   ├── MLPredictionRecord.java
│   └── TradingState.java
├── mapper/                                # MyBatis-Plus Mapper
│   ├── KlineMapper.java
│   ├── PositionMapper.java
│   ├── TradeOrderMapper.java
│   └── ...
└── controller/
    ├── TradingController.java
    ├── DashboardController.java
    ├── BacktestController.java
    ├── LiveTradingController.java
    ├── PaperTradingController.java
    └── ...
```

---

## 5. 策略架构与设计模式

### 5.1 策略模式（Strategy Pattern）

**核心接口** `Strategy`，所有策略实现三个方法：

```java
public interface Strategy {
    TradingSignal generateSignal(MarketContext context);  // 生成信号
    String getName();                                      // 策略名称
    int getWeight();                                       // 投票权重
    default boolean isEnabled() { return true; }          // 是否启用
}
```

新增策略仅需 `@Service` + 实现接口，无需修改任何调用方。

### 5.2 组合模式（Composite Pattern）

`CompositeStrategy` 聚合所有 `Strategy` Bean（Spring 自动注入 `List<Strategy>`），v3 内部改为**三层串联**而非加权投票：

```
子策略 Bean（Spring 注入） → CompositeStrategy（三层串联）→ TradingSignal
```

### 5.3 不可变上下文模式（Context Object Pattern）

`MarketContext` 由 `StrategyOrchestrator` **统一构建一次**，所有策略**只读**同一对象：

```java
@Data
@Builder
public class MarketContext {
    private String symbol;
    private List<Kline> klines;
    private BigDecimal currentPrice;
    private Map<String, Object> indicators;  // 所有指标结果
    private Double mlPrediction;
    private String candlePattern;
}
```

避免重复计算，保证信号与上下文数据一致。

### 5.4 工厂模式（Factory Pattern）

`TradingStrategyFactory` 根据 `trading.strategy.active` 配置选择激活策略：

| 配置值 | 激活策略 |
|--------|----------|
| `composite` | CompositeStrategy（三层串联） |
| `sr_rejection` | SRRejectionScalpingStrategy |
| `balanced-aggressive` | BalancedAggressiveStrategy |
| `simplified-trend` | SimplifiedTrendStrategy |

### 5.5 责任链模式（Chain of Responsibility）

`CompositeStrategy v3.2` 内部的三层串联逻辑：

```
Layer 1 【价格结构】
  价格不在 S/R 位 ───────────────────────────────→ HOLD（链断）
  价格在 S/R 位 ↓

Layer 2 【趋势 + 宏观过滤】
  趋势不符 / 宏观锁阻止 ────────────────────────→ HOLD（链断）
  趋势通过 ↓

Layer 3 【入场触发】
  WilliamsR + CandlePattern 确认 ───────────────→ BUY / SELL
  不满足或赔率 < 2:1 ────────────────────────────→ HOLD
```

### 5.6 适配器模式（Adapter Pattern）

`BrokerAdapter` 接口统一多个交易所的订单执行差异：

```
BrokerAdapter
  ├─ BinanceFuturesAdapter  (XAUUSDT 合约)
  ├─ BinanceLiveAdapter     (现货)
  └─ BybitTradingService
```

### 5.7 安全开关模式（Feature Flag）

```yaml
binance.futures.live-mode: false   # 合约实盘开关（默认关闭）
binance.live.mode: false           # 现货实盘开关（默认关闭）
```

关闭时自动路由至 `PaperTradingService`，零风险验证策略。

---

## 6. CompositeStrategy v3.2 详解

**重构日期**：2026-03-24  
**设计原则**：不追随动量，等价格回到关键 S/R 位再入场；宏观方向锁防止逆势交易。

### 三层串联逻辑

#### Layer 1 — 价格结构

| 条件 | 阈值 | 依赖 |
|------|------|------|
| 距最近支撑位 | ≤ 0.35% | KeyLevelCalculator |
| 距最近阻力位 | ≤ 0.35% | KeyLevelCalculator |

两者任一满足则进入 Layer 2，否则直接 HOLD。

#### Layer 2 — 趋势方向 + 宏观过滤

```
ADX ≥ 30（趋势足够强，否则 HOLD）

宏观方向锁（7d/24h 价格变化）：
  MACRO_BULL / UP   → 禁止做空（除非 24h 下跌 < -0.3%，真实回调确认）
  MACRO_BEAR / DOWN → 禁止做多
  MACRO_NEUTRAL 但 7d > 0.5% + 24h > 0 → 同样禁止做空

正常做多：   Supertrend=UP  AND EMA=UP
正常做空：   Supertrend=DOWN AND EMA=DOWN
牛市回调做多：MACRO_BULL + EMA=UP + ST=DOWN（短暂回调视为机会）

HMA 冲突检测：HMA 方向与任意做多信号冲突 → 直接 HOLD
```

#### Layer 3 — 入场触发

| 方向 | 条件 |
|------|------|
| 做多（正常） | trendUp + atSupport + WR < -60 + 无强烈看跌形态 |
| 做多（牛市回调） | trendUpRelaxed + atSupport + WR < -70 + 无强烈看跌形态 |
| 做空 | trendDown + atResistance + WR > -20 + 无强烈看涨形态 |

### TP/SL 计算规则

```
做多 SL = (currentPrice - nearestSupport) + 1.5 × ATR(14)
做空 SL = (nearestResistance - currentPrice) + 1.5 × ATR(14)

做多 TP = 第二近阻力位（若 TP ≥ 2×SL），否则 fallback = 3×ATR
做空 TP = 第二近支撑位（若 TP ≥ 2×SL），否则 fallback = 3×ATR

最低赔率要求：TP:SL ≥ 2:1，不满足直接 HOLD
```

### 信号强度计算（computeStrength）

| 条件 | 加分 |
|------|------|
| 基础分（三层全通过） | +50 |
| ADX ≥ 50 | +20 |
| ADX ≥ 40 | +15 |
| ADX ≥ 30 | +10 |
| WR < -85（极端超卖） | +15 |
| WR < -70 | +8 |
| WR > -15（极端超买） | +15 |
| WR > -30 | +8 |
| Supertrend 刚翻转 | +10 |
| 关键位强度为 STRONG | +10 |
| 确认 K 线形态吻合 | +5 |
| **上限** | **100** |

### 关键位计算（KeyLevelCalculator）

| 来源 | 强度 | 说明 |
|------|------|------|
| 前日最高/最低价（PDH/PDL） | STRONG | 机构最广泛参考线 |
| $50 整数关口（如 3000、3050） | STRONG | 心理关口 |
| $25 整数关口（如 3025、3075） | MEDIUM | 次级关口 |
| 多小时摆动高低点（12根K线窗口） | MEDIUM | 约 1 小时窗口 |
| 当日最新高低 | MEDIUM | 日内参考 |

> 相邻关键位距离 < 0.2% 时合并，取中间价，强度取高。

### 禁用的子策略

v3 起以下策略的 `isEnabled()` 返回 `false`，不再参与投票（功能已内化至三层逻辑）：

| 策略 | 原因 |
|------|------|
| `TrendFilterStrategy` | 趋势判断内化至 Layer 2 |
| `WilliamsStrategy` | WR 极值判断内化至 Layer 3 |
| `SupertrendStrategy` | 趋势方向判断内化至 Layer 2 |

### MarketContext 指标键依赖

| 键名 | 提供方 | 用于 |
|------|--------|------|
| `KeyLevels` | KeyLevelCalculator | Layer 1 价格结构 |
| `ADX` | ADXCalculator | Layer 2 趋势强度 |
| `MacroTrend` | StrategyOrchestrator | Layer 2 宏观方向锁 |
| `MacroPriceChange24h` | StrategyOrchestrator | Layer 2 做空回调确认 |
| `MacroPriceChange7d` | StrategyOrchestrator | Layer 2 NEUTRAL 偏向检测 |
| `Supertrend` | SupertrendCalculator | Layer 2 趋势方向 |
| `EMATrend` | EMACalculator | Layer 2 趋势方向 |
| `HMA` | HMACalculator | Layer 2 冲突检测 |
| `WilliamsR` | WilliamsRCalculator | Layer 3 入场触发 |
| `CandlePattern` | CandlePatternAnalyzer | Layer 3 形态过滤 |

---

## 7. 技术指标层

16 个指标计算器均为**无状态** Spring Bean（`@Component`），接收 `List<Kline>` 返回计算结果。

| 计算器 | 功能 | 默认周期 | Layer 用途 |
|--------|------|----------|-----------|
| `ADXCalculator` | 平均趋势指数（趋势强度） | 14 | Layer 2 |
| `ATRCalculator` | 平均真实波幅（波动度） | 14 | TP/SL 计算 |
| `BollingerBandsCalculator` | 布林带（上中下轨） | 20 | 子策略 |
| `CandlePatternAnalyzer` | K 线形态识别 | - | Layer 3 |
| `EMACalculator` | 指数移动平均 | 20 | Layer 2 |
| `HMACalculator` | 赫尔移动平均（降低滞后） | 20 | Layer 2 冲突检测 |
| `KeyLevelCalculator` | 多层次支撑/阻力位 | - | Layer 1（核心）|
| `MACDCalculator` | MACD 指标 | 12/26/9 | 参考 |
| `MomentumCalculator` | 动量指标 | - | 参考 |
| `OBVCalculator` | 成交量确认（On-Balance Volume） | 20 | 参考 |
| `RSICalculator` | 相对强度指标 | 14 | 子策略 |
| `StochRSICalculator` | 随机相对强度 | 14 | 参考 |
| `SupertrendCalculator` | ATR 趋势跟踪（翻转信号） | 10 / 3.0 | Layer 2 |
| `SwingPointCalculator` | 摆动高低点 | 5 | KeyLevel 来源 |
| `VWAPCalculator` | 成交量加权均价 | 60 | 参考 |
| `VolumeBreakoutCalculator` | 成交量突破 | 20 | 参考 |
| `WilliamsRCalculator` | Williams %R（超买/超卖） | 14 | Layer 3（核心）|

---

## 8. 风险管理

### RiskManagementService — 5 项前置检查

| 检查项 | 配置键 | 默认值 |
|--------|--------|--------|
| 持仓规模上限 | `trading.risk.max-position-size` | 5.0 |
| 日亏损上限 | `trading.risk.max-daily-loss` | 500 |
| 最大回撤 | `trading.risk.max-drawdown-percent` | 5% |
| 单仓位模式（不叠仓） | 硬编码 | true |
| 亏损冷却期 | `trading.risk.cooldown.*` | 120 分钟 |

### 亏损冷却期逻辑

```yaml
trading:
  risk:
    cooldown:
      enabled: true
      loss-threshold: 50.0    # 单笔亏损超过 $50 触发冷却
      minutes: 120             # 冷却期 120 分钟内禁止同方向交易
```

### 移动止损（Trailing Stop）

```yaml
trading:
  risk:
    trailing-stop:
      enabled: true
      trigger-profit: 30.0    # 浮盈超 $30 开始追踪
      distance: 12.0           # 追踪距离 $12
      lock-profit-percent: 60.0 # 锁定已实现利润的 60%
```

---

## 9. 数据模型

### TradingSignal — 交易信号

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | Enum | BUY / SELL / HOLD |
| `strength` | int | 信号强度 0-100 |
| `score` | double | 综合评分 |
| `strategyName` | String | 生成信号的策略 |
| `reason` | String | 信号原因描述 |
| `suggestedStopLoss` | BigDecimal | 建议止损价 |
| `suggestedTakeProfit` | BigDecimal | 建议止盈价 |
| `signalGenerateTime` | Long | 信号时间戳 |

### Kline — K 线数据（`t_kline`）

| 字段 | 说明 |
|------|------|
| `symbol` | 交易品种（XAUUSDT） |
| `timestamp` | K 线时间戳 |
| `interval` | 周期（5m） |
| `openPrice` | 开盘价 |
| `highPrice` | 最高价 |
| `lowPrice` | 最低价 |
| `closePrice` | 收盘价 |
| `volume` | 成交量 |

### 其他实体

| 实体 | 表名 | 说明 |
|------|------|------|
| `Position` | `t_position` | 持仓记录 |
| `TradeOrder` | `t_trade_order` | 交易订单 |
| `BacktestResult` | `t_backtest_result` | 回测汇总 |
| `BacktestTrade` | `t_backtest_trade` | 回测逐笔 |
| `MLPredictionRecord` | `t_ml_prediction_record` | ML 预测记录 |
| `TradingState` | `t_trading_state` | 系统状态 |

---

## 10. REST API

### 基础信息

- **端口**：`3131`
- **Context Path**：`/api`
- **完整前缀**：`http://localhost:3131/api`

### 回测 API

```bash
# 触发回测
POST /api/backtest/execute
{
  "symbol": "XAUUSDT",
  "interval": "5m",
  "startTime": "2026-01-01 00:00:00",
  "endTime": "2026-03-23 00:00:00",
  "initialCapital": 10000,
  "strategyName": "COMPOSITE_V3"
}

# 查询回测结果
GET /api/backtest/result/{backtestId}

# 查询回测交易明细
GET /api/backtest/trades/{backtestId}

# 所有回测列表
GET /api/backtest/list
```

### 交易 API

```bash
# 查询当前信号
GET /api/trading/signal

# 手动触发交易检查
POST /api/trading/check

# 查询持仓
GET /api/trading/position

# 纸盘交易状态
GET /api/paper/status
```

---

## 11. 配置参考

### 关键配置项（application.yml）

```yaml
server:
  port: 3131
  servlet:
    context-path: /api

# 策略选择
trading:
  strategy:
    active: composite         # 可选: composite / sr_rejection / balanced-aggressive
    enabled: true
    interval: 60000           # 调度间隔 ms

  risk:
    max-position-size: 5.0
    max-daily-loss: 500
    max-drawdown-percent: 5.0
    cooldown:
      enabled: true
      loss-threshold: 50.0
      minutes: 120

# 合约实盘（默认关闭）
binance:
  futures:
    symbol: XAUUSDT
    live-mode: false          # ⚠️ 实盘开关
    leverage: 2
    max-order-amount: 10      # 单笔最大盎司
    max-daily-trades: 20
    max-daily-loss: 500.0
  api:
    key: ""                   # 实盘必填
    secret: ""

# 飞书通知
feishu:
  notification:
    enabled: true
  webhook:
    url: "https://open.feishu.cn/..."
```

---

## 12. 数据库

### 连接信息

```
Host:     localhost:3306
Database: aug
Username: root
Password: 12345678
```

### 初始化

```bash
mysql -u root -p < src/main/resources/sql/schema.sql
```

### 迁移（手动执行，无 Flyway）

```bash
mysql -u root -p aug < src/main/resources/db/migration/V1_3__create_ml_prediction_record.sql
mysql -u root -p aug < src/main/resources/db/migration/V1_4__add_vwap_supertrend_obv_fields.sql
mysql -u root -p aug < src/main/resources/db/migration/V1_5__add_signal_tracking_fields.sql
```

---

## 13. 回测框架

### 策略名称对应关系

| strategyName | 方法 | 说明 |
|---|---|---|
| `COMPOSITE` | executeCompositeBacktest | 旧版加权投票，固定 $15/$45 SL/TP |
| `COMPOSITE_V3` | executeCompositeV3Backtest | **当前策略**，ATR-based SL/TP |
| `RSI` | executeRSIBacktest | 简单 RSI 策略 |
| `BREAKOUT` | executeBreakoutBacktest | 布林带突破 |
| `SHORT_TERM` | executeShortTermBacktest | SMA 交叉短线 |

### COMPOSITE_V3 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| K 线窗口 | 2016 根 | 7 天数据，确保宏观趋势有效 |
| 起始索引 | 第 2016 根 | 跳过预热阶段 |
| 开仓数量 | 10 手 | 与实盘一致 |
| 手续费率 | 0.035% | VIP 折扣 |
| TP/SL | ATR-based | 来自 CompositeStrategy 计算 |

---

## 14. 编码规范

### SLF4J 日志 — 禁止在 `{}` 内使用格式符

SLF4J 占位符只接受裸 `{}`，格式符 `{:.2f}` 会**静默输出字面量**而非数值：

```java
// 错误
log.info("price={:.2f}", price);          // 输出 "price={:.2f}"

// 正确
log.info("price={}", String.format("%.2f", price));
```

### 添加新策略

1. 在 `strategy/core/` 创建类实现 `Strategy`
2. 添加 `@Service` 注解
3. 实现 `generateSignal()`、`getName()`、`getWeight()`
4. Spring 的 `List<Strategy>` 注入会自动发现该 Bean
5. 若要设为唯一活跃策略，在 `TradingStrategyFactory` 注册名称并更新配置

---

*文档由 Claude Code 辅助生成，基于代码实际状态。*
