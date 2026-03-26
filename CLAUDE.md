# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
mvn clean package -DskipTests

# Run application (port 3131, context-path /api)
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=Jin10PriceTest

# Run a single test method
mvn test -Dtest=Jin10PriceTest#methodName
```

## Database Setup

MySQL database name is `aug` (connection URL uses `aug`, but schema.sql creates `aug_trade` — verify before setup):

```bash
# Initialize schema
mysql -u root -p < src/main/resources/sql/schema.sql

# Apply migrations (manual, no Flyway/Liquibase configured)
mysql -u root -p aug < src/main/resources/db/migration/V1_3__create_ml_prediction_record.sql
mysql -u root -p aug < src/main/resources/db/migration/V1_4__add_vwap_supertrend_obv_fields.sql
mysql -u root -p aug < src/main/resources/db/migration/V1_5__add_signal_tracking_fields.sql
```

Credentials in `application.yml`: `root / 12345678`, port 3306.

## Architecture Overview

### Request Flow
```
TradingScheduler (60s interval)
  → TradingStrategyService
    → StrategyOrchestrator          # Entry point for signal generation
      → MarketDataService           # Fetch latest klines from DB
      → [Indicator Calculators]     # RSI, MACD, ADX, Bollinger, Williams R, VWAP, Supertrend, OBV, ATR, EMA, HMA, Momentum, VolumeBreakout, SwingPoint
      → MarketContext (built with all indicator values)
      → CompositeStrategy           # Weighted voting across sub-strategies
        → [Strategy implementations] # Each returns TradingSignal
    → RiskManagementService         # Pre-trade risk checks
    → BinanceFuturesTradingService  # Execute order (XAUUSDT futures)
    → FeishuNotificationService     # Push result to Lark bot
```

### Key Abstractions

**`Strategy` interface** (`strategy/core/Strategy.java`) — All strategies implement `generateSignal(MarketContext)`, `getName()`, `getWeight()`. The active strategy is configured via `trading.strategy.active` in `application.yml` (currently `composite`).

**`MarketContext`** — Immutable context object (Lombok `@Builder`) passed to all strategies. Contains klines list, current price, all computed indicator values (as `Map<String, Object>`), ML prediction, and candle pattern.

**`CompositeStrategy`** — Collects signals from all `Strategy` beans, applies weighted voting. Threshold is 6 (a single strategy can trigger). Sub-strategies are Spring beans injected as `List<Strategy>`.

**`TradingStrategyFactory`** — Selects active strategy bean by name from config.

### Package Structure

| Package | Responsibility |
|---|---|
| `indicator` | Stateless calculators (RSI, MACD, ADX, Bollinger, Williams R, ATR, EMA, HMA, VWAP, Supertrend, OBV, Momentum, VolumeBreakout, SwingPoint, CandlePatternAnalyzer) |
| `strategy/core` | Strategy interface + all strategy implementations + StrategyOrchestrator + MarketContext + MarketRegimeDetector |
| `strategy/gold` | Gold-specific multi-dimension pricing model (Liquidity, Gamma, ETF Flow, DXY Correlation, Seasonality, etc.) |
| `trading/broker` | Exchange adapters: `BinanceFuturesTradingService` (XAUUSDT futures), `BinanceTradingService` (spot), `BybitTradingService`, `BinanceLiveAdapter` |
| `trading/execution` | `PaperTradingService` — simulated trading without real orders |
| `trading/risk` | `RiskManagementService` — pre-trade checks (position size, daily loss, drawdown) |
| `market` | `RealMarketDataService`, `BinanceHistoricalDataFetcher`, `HistoricalDataFetcher` interface |
| `backtest` | `BacktestService` — historical simulation |
| `ml` | `MLPredictionService`, `MLPredictionEnhancedService`, `MLRecordService` |
| `notification` | `FeishuNotificationService` — Lark webhook |
| `scheduler` | `TradingScheduler` — `@Scheduled` entry points |
| `controller` | REST endpoints for manual triggers and dashboard |
| `entity/mapper` | MyBatis-Plus entities and mappers for `t_kline`, `t_trade_order`, `t_position`, `t_backtest_result`, `t_backtest_trade`, `t_ml_prediction_record`, `t_trading_state` |

### Safety Switches

Real trading is **disabled by default**. To enable, set in `application.yml`:
- `binance.futures.live-mode: true` — enables futures order execution
- `binance.live.mode: true` — enables spot order execution
- `binance.api.key` / `binance.api.secret` — required for real trading

Risk limits are enforced in `RiskManagementService` regardless of mode.

## Coding Rules

### Java SLF4J Logging — NEVER use format specifiers inside `{}`

SLF4J placeholders are bare `{}` only. Format specifiers like `{:.2f}` or `{:.3f}` are Python/C-style and **silently produce garbage output** in Java (they are NOT errors — the literal text `{:.2f}` gets printed instead of the number).

**Wrong:**
```java
log.info("price={:.2f}", price);          // prints "price={:.2f}"
log.debug("ATR={:.3f}", atrValue);        // prints "ATR={:.3f}"
```

**Correct — pre-format numbers with `String.format()` before passing as arguments:**
```java
log.info("price={}", String.format("%.2f", price));
log.debug("ATR={}", String.format("%.3f", atrValue));
```

### Adding a New Strategy

1. Create a class in `strategy/core/` implementing `Strategy`
2. Annotate with `@Service`
3. Implement `generateSignal(MarketContext)`, `getName()`, `getWeight()`
4. It will be auto-discovered by `CompositeStrategy` via Spring's `List<Strategy>` injection
5. To make it the sole active strategy, register the name in `TradingStrategyFactory` and set `trading.strategy.active` in config

---

## Current Strategy: CompositeStrategy v3.2 (三层串联 + 宏观过滤)

**重构日期**：2026-03-24（v3.2 宏观过滤升级）
**文件**：`strategy/core/CompositeStrategy.java`
**设计原则**：不追随动量，等价格回到关键 S/R 位再入场；三层必须全部通过才开仓；宏观方向锁防止逆势。

### 三层串联逻辑（v3.2）

```
Layer 1 【价格结构】
  → 价格距支撑位 ≤ 0.35%（isAtSupport）
  → 或价格距阻力位 ≤ 0.35%（isAtResistance）
  → 否则直接 HOLD，不进入后续判断
  → 依赖：KeyLevelCalculator（多层次 S/R 位）

Layer 2 【趋势方向 + 宏观过滤】
  → ADX ≥ 30（趋势足够强）
  → 宏观方向锁（MacroTrend 来自 7d/24h 价格变化）：
      MACRO_BULL/UP → 禁止做空（除非 24h 下跌 < -0.3%，真实回调才允许）
      MACRO_BEAR/DOWN → 禁止做多
      MACRO_NEUTRAL 但 7d>0.5%+24h>0 → 同样禁止做空
  → 正常做多：trendUp = ST.isUpTrend && EMA.isUpTrend
  → 正常做空：trendDown = ST.isDownTrend && EMA.isDownTrend
  → 牛市回调做多（trendUpRelaxed）：MACRO_BULL + EMA=UP + ST=DOWN（短暂回调）
  → HMA 不得与任意做多方向冲突（冲突时直接 HOLD）

Layer 3 【入场触发】
  → 做多（正常）：trendUp + atSupport + WilliamsR < -60 + 无强烈看跌形态
  → 做多（牛市回调）：trendUpRelaxed + atSupport + WilliamsR < -70 + 无强烈看跌形态
  → 做空：trendDown + atResistance + WilliamsR > -20 + 无强烈看涨形态
```

### TP/SL 计算规则

```
SL（做多）= (currentPrice - nearestSupport) + 1.5 × ATR(14)   → 支撑下方
SL（做空）= (nearestResistance - currentPrice) + 1.5 × ATR(14) → 阻力上方

TP（做多）= 第二近阻力位（若距离 ≥ 2×SL），否则 fallback 3×ATR
TP（做空）= 第二近支撑位（若距离 ≥ 2×SL），否则 fallback 3×ATR

最低赔率要求：TP:SL ≥ 2:1，不满足直接返回 HOLD
```

### 关键位计算（KeyLevelCalculator）

| 来源 | 强度 |
|---|---|
| 前日最高/最低价（PDH/PDL） | STRONG |
| $50 整数关口（如 3000、3050） | STRONG |
| $25 整数关口（如 3025、3075） | MEDIUM |
| 多小时摆动高低点（12根K线窗口 ≈ 1小时） | MEDIUM |
| 当日最新高低 | MEDIUM |

相邻关键位距离 < 0.2% 时合并（取中间价，强度取高）。

### 信号强度计算（computeStrength）

| 条件 | 加分 |
|---|---|
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
| 最高上限 | 100 |

### 禁用的子策略

v3 起以下策略的 `isEnabled()` 返回 `false`，不再参与投票：
- `TrendFilterStrategy` — 趋势判断内化至 Layer 2
- `WilliamsStrategy` — WR 极值判断内化至 Layer 3
- `SupertrendStrategy` — 趋势方向判断内化至 Layer 2

### 依赖的 MarketContext 指标键

| 键名 | 提供方 | 用于 |
|---|---|---|
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

### 数据依据（v3 设计来源 + 专业量化研究）

| 发现 | 来源 | 影响 |
|---|---|---|
| WR 极值区（<-80 / >-20）胜率 83-100% | 回测实证 | WR 成为强制入场触发器 |
| WR 中性区 + SELL：胜率 33%，均亏 -$86 | 回测实证 | 非极值区直接过滤 |
| ADX ≥ 40：胜率 85%；ADX 25-40：50% | 回测实证 | Layer 2 设置 ADX ≥ 30 门槛 |
| HMA/ST 方向冲突：均亏 -$88.70/单 | 回测实证 | HMA 冲突直接拒绝 |
| 追动量（放量+强动量加分）：均亏 -$57.43/单 | 回测实证 | 废弃动量加分逻辑 |
| TrendFilter 单独触发：亏损 -$963 / 13笔 | 回测实证 | 禁用子策略投票 |
| 黄金均值回归效果显著弱于股市 | QuantifiedStrategies | 不依赖纯均值回归，WR 需配合趋势方向 |
| WR 做空策略在牛市不盈利 | QuantifiedStrategies | MACRO_BULL 时禁止做空或要求 24h 回调确认 |
| ADX 过滤器将 profit factor 从 1.7 → 2.4 | QuantifiedStrategies | Layer 2 保留 ADX ≥ 30 硬门槛 |
| 高手续费下开仓频率越高侵蚀越严重 | 量化研究通识 | 三层过滤维持低频（年均 10-20 笔） |
| 宏观趋势过滤在日内策略中同样有效 | QuantifiedStrategies | 即使 5m 图也使用 7d 宏观锁 |
| S/R 单一来源胜率 9%，需 ≥2 来源 | 回测实证 | Layer 3 强制 sourceCount ≥ 2 |
| 黄金 Hurst 指数日线 ≈ 0.55-0.65（轻度趋势性）| 学术共识 | 确认趋势跟踪策略优于均值回归 |
| ADX 最优周期 5-10 日（非14日）| QuantifiedStrategies | 黄金ADX天然偏低，14日≥30已是强趋势 |
| 几乎所有黄金收益来自夜盘（隔夜漂移）| QuantifiedStrategies | 美欧重叠时段（UTC 13-17点）噪音最大 |
| FVG（公允价值缺口）有效窗口 30-40 分钟 | ICT/SMC | 可作为 Layer 1 额外加分条件 |
| ADX≥40 时黄金回调深度通常不足 | 量化实践 | 强趋势下 WR 门槛应动态放宽至 -50 |
| OB/CHoCH 无独立统计验证，事后解释性强 | 量化研究批评 | 不单独引入，现有 SwingLevel 已覆盖 70% |
| PDH/PDL 是机构最广泛使用的参考线 | 机构共识 | STRONG 级别关键位，维持现有设计 |

---

## 专业量化研究笔记（QuantifiedStrategies / QuantPedia / ICT）

> 整理自专业量化网站实证研究，指导策略优化方向。

### 黄金市场特性

- **均值回归效果远弱于股市**：金价统计特征更接近随机游走，Hurst 指数日线约 0.55-0.65（轻度趋势）。纯均值回归策略在黄金上表现差，应只在趋势方向上使用超卖/超买入场。
- **几乎所有黄金历史收益发生在夜盘（隔夜漂移）**：GLD ETF 2004-2026 回测显示日间平均收益约 -0%，夜盘是主要正期望来源。对应策略：美欧重叠时段（UTC 13:00-17:00）信号质量最低，应额外提高门槛。
- **WR 做多历史 CAGR 11.9%，做空长期亏损**：在黄金历史数据上，WR 超卖做多有正期望，WR 超买做空的长期期望为负，尤其牛市中。

### ADX 在黄金上的特殊性

- **最优周期**：5-10 日（远短于 Wilder 原始 14 日）
- **黄金 ADX 天然偏低**：历史上 50 日 ADX 几乎从未超过 25，5m 图 ADX≥30 已是较强趋势信号
- **WR 应随 ADX 动态调整**：强趋势（ADX≥40）时回调深度通常不足 -70，WR 门槛应放宽至 -50（做多）；弱趋势（ADX 30-40）维持 -60

### FVG（公允价值缺口）

识别规则（可编程）：
```
看涨FVG：K1.high < K3.low，且 K2 实体 > 1.5 × ATR(14)
看跌FVG：K1.low > K3.high，且 K2 实体 > 1.5 × ATR(14)
有效期：≤ 8 根 K 线（40 分钟）内未被填充
```
- FVG+SwingLevel 双重确认时信号可靠性更高（建议加入 `computeStrength` 的加分逻辑）
- 不建议作为 Layer 1 的必要条件，作为可选加分项即可

### SMC/ICT 体系评估

| 概念 | 量化验证 | 建议 |
|---|---|---|
| Order Block | 无独立验证，与 SwingLevel 70% 重叠 | 现有体系已覆盖 |
| FVG | 无独立验证，但逻辑清晰可量化 | 加入 computeStrength 加分 |
| CHoCH/BOS | 无独立验证，近似于 MacroTrend 概念 | 用现有 MacroTrend 替代 |
| OTE（62-79%回调）| 无独立验证 | WR<-60 在数学上已覆盖 |
| ICT Kill Zones | 与隔夜漂移研究方向一致 | 作为时段过滤参考 |

---

## 回测说明

### 策略名称对应关系

| strategyName 参数 | 对应方法 | 说明 |
|---|---|---|
| `COMPOSITE` | executeCompositeBacktest | 旧版加权投票，固定 $15/$45 SL/TP |
| `COMPOSITE_V3` | executeCompositeV3Backtest | **当前策略** — 三层串联，ATR-based SL/TP |
| `RSI` | executeRSIBacktest | 简单 RSI 超买超卖 |
| `BREAKOUT` | executeBreakoutBacktest | 布林带突破 |
| `SHORT_TERM` | executeShortTermBacktest | SMA 交叉短线 |

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

# 查询结果
GET /api/backtest/result/{backtestId}

# 查询交易明细
GET /api/backtest/trades/{backtestId}

# 所有回测列表
GET /api/backtest/list
```

### COMPOSITE_V3 回测关键参数

- **K线窗口**：2016 根（7天），确保 MacroTrend 7d 窗口有效
- **起始索引**：第 2016 根 K 线开始（保证宏观趋势计算数据充足）
- **开仓数量**：固定 10 手黄金（与实盘一致）
- **SL/TP**：来自 CompositeStrategy 计算的 ATR-based 值（不再使用固定美元数）
- **做多/做空**：均支持（v3.2 内置宏观过滤）
- **手续费率**：0.035%（VIP 折扣）

---

## 专业量化研究笔记（QuantifiedStrategies / QuantPedia）

> 这部分整理自专业量化网站的实证研究，指导策略设计方向。

### 黄金特性

- **黄金均值回归效果远弱于股市**（QuantifiedStrategies）：金价的统计特征更接近随机游走，不像股市那样有强烈的均值回归倾向。纯均值回归策略在黄金上表现差，建议只在趋势方向上使用均值回归入场。
- **黄金更适合趋势跟踪，但胜率低**：趋势策略黄金典型胜率 20-40%，但盈亏比可达 3-6:1。不要因为 30-40% 的胜率而放弃策略。
- **WR 做多策略历史 CAGR 11.9%，做空策略长期亏损**：在黄金历史数据上，WR 超卖做多有正期望，WR 超买做空的长期期望为负，尤其在牛市中。

### 宏观趋势过滤器

- **宏观过滤器在日内策略中同样有效**（QuantifiedStrategies）：即使是 5m 日内策略，加入宏观趋势过滤后 profit factor 从 1.7 提升至 2.4，市场暴露时间从 40% 降至 15%。
- **多时间框架一致性**：业界标准做法是：只在宏观方向（日线/周线）顺势做单，逆势信号直接过滤。
- **7d 价格变化是有效的宏观代理**：7d 涨 >1.5% → 认定为短期强牛市，此时禁止做空（除非 24h 出现真实回调 < -0.3%）。

### S/R 支撑阻力

- **S/R 是主观科学**（QuantifiedStrategies）：人眼容易"看到自己想看的东西"，单一来源 S/R 可靠性极低。本项目通过"≥2 来源确认"解决此问题。
- **整数关口（Round50/Round25）单独使用胜率接近随机**：必须与摆动高低点或 PDH/PDL 共同确认。
- **0.35% 触发阈值**：在当前黄金 $3000-5000 价位，0.35% ≈ $10.5-17.5，这是合理的"关键位附近"定义。

### 手续费与频率

- **Scalping 对 95% 的交易者是亏损策略**（QuantifiedStrategies）：原因一是手续费，原因二是价格自相关性不足。0.035% 双边手续费在 5m 图上，每笔至少需要 0.07% 的价格运动才能盈亏平衡。
- **年均 10-20 笔是合理区间**（高过滤策略）：低频高质（盈亏比 ≥ 3:1）优于高频低质（频繁小盈亏）。
- **最低盈亏比要求 2:1**（量化界标准）：本项目 `MIN_REWARD_RISK_RATIO = 2.0` 已达到最低标准，不应降低。

### ADX 指标

- **ADX ≥ 40：胜率 85%；ADX 25-40：胜率 50%**（QuantifiedStrategies）：ADX 是有效的趋势强度过滤器，阈值越高越精准但信号越少。本项目设 ADX ≥ 30 作为下限，在信号频率和准确率间取得平衡。
- **ADX 不区分方向**：只表示趋势强度，方向由 ST/EMA 判断。

### Williams %R

- **WR 中性区（-70 到 -30）信号无效**：有效信号集中在极端区（< -80 超卖，> -20 超买）。本项目 Layer 3 用 WR < -60（做多）和 WR > -20（做空）作为入场过滤。
- **牛市回调模式需要更深超卖**：当使用放宽条件（ST 已翻下但 EMA 仍向上）做多时，要求 WR < -70（比正常 -60 更严格），以确保价格确实在深度回调中。