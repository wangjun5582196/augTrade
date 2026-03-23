1# CLAUDE.md

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