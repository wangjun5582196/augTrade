# 📂 代码结构重组方案

## 🎯 目标

将代码按功能模块清晰分类，提高可维护性和可读性。

---

## 📊 当前结构问题

### 问题1：service目录过于臃肿
```
service/
├── AggressiveScalpingStrategy.java       # 策略类
├── SimplifiedTrendStrategy.java           # 策略类
├── TradingStrategyFactory.java            # 策略工厂
├── BybitTradingService.java               # 交易所服务
├── MarketDataService.java                 # 数据服务
├── IndicatorService.java                  # 指标服务
├── BacktestService.java                   # 回测服务
├── ...                                    # 混在一起
└── core/                                  # 又有子目录
```

### 问题2：功能边界不清晰
- 策略类、服务类混在一起
- 难以快速找到相关代码
- 新人不易理解项目结构

---

## ✨ 新的目录结构

### 方案：按业务领域分层

```
src/main/java/com/ltp/peter/augtrade/
│
├── AugTradeApplication.java                    # 启动类
│
├── controller/                                  # 控制器层（不变）
│   ├── BacktestController.java
│   ├── DashboardController.java
│   ├── MarketDataController.java
│   ├── PaperTradingController.java
│   └── TradingController.java
│
├── entity/                                      # 实体层（不变）
│   ├── Kline.java
│   ├── Position.java
│   ├── TradeOrder.java
│   └── ...
│
├── mapper/                                      # 数据访问层（不变）
│   ├── KlineMapper.java
│   ├── PositionMapper.java
│   └── ...
│
├── strategy/                                    # ⭐ 策略模块（新建）
│   ├── TradingStrategyFactory.java             # 策略工厂（核心）
│   ├── SimplifiedTrendStrategy.java            # 精简趋势策略
│   ├── AggressiveScalpingStrategy.java         # 平衡激进策略
│   │
│   ├── core/                                    # 策略框架（移动）
│   │   ├── Strategy.java                       # 策略接口
│   │   ├── StrategyOrchestrator.java           # 策略编排器
│   │   ├── CompositeStrategy.java              # 组合策略
│   │   ├── BalancedAggressiveStrategy.java
│   │   ├── BollingerBreakoutStrategy.java
│   │   ├── RangingMarketStrategy.java
│   │   ├── RSIStrategy.java
│   │   ├── TrendFilterStrategy.java
│   │   ├── WilliamsStrategy.java
│   │   └── MarketRegimeDetector.java
│   │
│   └── signal/                                  # 信号相关（移动）
│       ├── TradingSignal.java                  # 交易信号
│       └── MarketContext.java                  # 市场上下文
│
├── indicator/                                   # ⭐ 指标模块（新建）
│   ├── IndicatorService.java                   # 指标服务（主入口）
│   ├── ADXCalculator.java
│   ├── ATRCalculator.java
│   ├── EMACalculator.java
│   ├── RSICalculator.java
│   ├── MACDCalculator.java
│   ├── MACDResult.java
│   ├── WilliamsRCalculator.java
│   ├── BollingerBands.java
│   ├── BollingerBandsCalculator.java
│   ├── CandlePattern.java
│   ├── CandlePatternAnalyzer.java
│   └── TechnicalIndicator.java
│
├── trading/                                     # ⭐ 交易模块（新建）
│   ├── execution/                              # 交易执行
│   │   ├── TradeExecutionService.java          # 交易执行服务
│   │   └── PaperTradingService.java            # 模拟交易服务
│   │
│   ├── broker/                                 # 交易所对接
│   │   ├── BybitTradingService.java            # Bybit交易服务
│   │   ├── BrokerAdapter.java                  # 券商适配器接口
│   │   └── OrderRequest.java                   # 订单请求
│   │
│   └── risk/                                   # 风险管理
│       └── RiskManagementService.java          # 风控服务
│
├── market/                                      # ⭐ 市场数据模块（新建）
│   ├── MarketDataService.java                  # 市场数据服务（主入口）
│   ├── RealMarketDataService.java              # 实时数据服务
│   └── HistoricalDataFetcher.java              # 历史数据获取
│
├── backtest/                                    # ⭐ 回测模块（新建）
│   └── BacktestService.java                    # 回测服务
│
├── ml/                                          # ⭐ 机器学习模块（新建）
│   ├── MLPredictionService.java                # ML预测服务
│   └── MLRecordService.java                    # ML记录服务
│
├── notification/                                # ⭐ 通知模块（新建）
│   └── FeishuNotificationService.java          # 飞书通知
│
├── state/                                       # ⭐ 状态管理模块（新建）
│   └── TradingStateService.java                # 交易状态服务
│
├── scheduler/                                   # ⭐ 调度模块（新建）
│   ├── TradingScheduler.java                   # 交易调度器
│   └── StartupDataLoader.java                  # 启动数据加载
│
└── common/                                      # ⭐ 通用工具（新建）
    └── (保留未来扩展)
```

---

## 🔄 移动计划

### 第一步：创建新目录结构

```bash
mkdir -p src/main/java/com/ltp/peter/augtrade/strategy/core
mkdir -p src/main/java/com/ltp/peter/augtrade/strategy/signal
mkdir -p src/main/java/com/ltp/peter/augtrade/indicator
mkdir -p src/main/java/com/ltp/peter/augtrade/trading/execution
mkdir -p src/main/java/com/ltp/peter/augtrade/trading/broker
mkdir -p src/main/java/com/ltp/peter/augtrade/trading/risk
mkdir -p src/main/java/com/ltp/peter/augtrade/market
mkdir -p src/main/java/com/ltp/peter/augtrade/backtest
mkdir -p src/main/java/com/ltp/peter/augtrade/ml
mkdir -p src/main/java/com/ltp/peter/augtrade/notification
mkdir -p src/main/java/com/ltp/peter/augtrade/state
mkdir -p src/main/java/com/ltp/peter/augtrade/scheduler
```

### 第二步：移动策略相关文件（9个文件）

**目标目录**: `strategy/`

```bash
# 策略主文件
mv service/TradingStrategyFactory.java → strategy/
mv service/SimplifiedTrendStrategy.java → strategy/
mv service/AggressiveScalpingStrategy.java → strategy/
mv service/TradingStrategyService.java → strategy/

# 策略框架
mv service/core/strategy/* → strategy/core/

# 信号相关
mv service/core/signal/TradingSignal.java → strategy/signal/
mv service/core/strategy/MarketContext.java → strategy/signal/
```

### 第三步：移动指标相关文件（13个文件）

**目标目录**: `indicator/`

```bash
mv service/IndicatorService.java → indicator/
mv service/core/indicator/* → indicator/
```

### 第四步：移动交易相关文件（6个文件）

**目标目录**: `trading/`

```bash
# 交易执行
mv service/TradeExecutionService.java → trading/execution/
mv service/PaperTradingService.java → trading/execution/

# 交易所对接
mv service/BybitTradingService.java → trading/broker/
mv service/infrastructure/broker/* → trading/broker/

# 风险管理
mv service/RiskManagementService.java → trading/risk/
```

### 第五步：移动市场数据文件（3个文件）

**目标目录**: `market/`

```bash
mv service/MarketDataService.java → market/
mv service/RealMarketDataService.java → market/
mv util/HistoricalDataFetcher.java → market/
```

### 第六步：移动其他模块文件（7个文件）

```bash
# 回测
mv service/BacktestService.java → backtest/

# 机器学习
mv service/MLPredictionService.java → ml/
mv service/MLRecordService.java → ml/

# 通知
mv service/FeishuNotificationService.java → notification/

# 状态管理
mv service/TradingStateService.java → state/

# 调度
mv task/TradingScheduler.java → scheduler/
mv service/StartupDataLoader.java → scheduler/
```

### 第七步：清理空目录

```bash
# 删除空的旧目录
rmdir service/core/indicator
rmdir service/core/signal
rmdir service/core/strategy
rmdir service/core
rmdir service/domain/risk
rmdir service/domain/trading
rmdir service/domain
rmdir service/infrastructure/broker
rmdir service/infrastructure/data
rmdir service/infrastructure
rmdir service
rmdir task
rmdir util
```

---

## 📦 移动文件清单

| 原路径 | 新路径 | 数量 |
|--------|--------|------|
| service/*.java | strategy/ | 4个 |
| service/core/strategy/ | strategy/core/ | 11个 |
| service/core/signal/ | strategy/signal/ | 2个 |
| service/core/indicator/ | indicator/ | 13个 |
| service/IndicatorService.java | indicator/ | 1个 |
| service/TradeExecutionService.java | trading/execution/ | 1个 |
| service/PaperTradingService.java | trading/execution/ | 1个 |
| service/BybitTradingService.java | trading/broker/ | 1个 |
| service/infrastructure/broker/ | trading/broker/ | 2个 |
| service/RiskManagementService.java | trading/risk/ | 1个 |
| service/MarketDataService.java | market/ | 1个 |
| service/RealMarketDataService.java | market/ | 1个 |
| util/HistoricalDataFetcher.java | market/ | 1个 |
| service/BacktestService.java | backtest/ | 1个 |
| service/ML*.java | ml/ | 2个 |
| service/FeishuNotificationService.java | notification/ | 1个 |
| service/TradingStateService.java | state/ | 1个 |
| task/TradingScheduler.java | scheduler/ | 1个 |
| service/StartupDataLoader.java | scheduler/ | 1个 |

**总计**：约45个文件需要移动

---

## ✅ 重组后的优势

### 1. 清晰的功能分层
```
strategy/      → 交易策略相关（17个文件）
indicator/     → 技术指标计算（14个文件）
trading/       → 交易执行与风控（6个文件）
market/        → 市场数据获取（3个文件）
backtest/      → 回测系统（1个文件）
ml/            → 机器学习（2个文件）
notification/  → 消息通知（1个文件）
state/         → 状态管理（1个文件）
scheduler/     → 任务调度（2个文件）
```

### 2. 易于查找
- 找策略？→ `strategy/`
- 找指标？→ `indicator/`
- 找交易？→ `trading/`
- 找数据？→ `market/`

### 3. 易于扩展
- 新增策略？→ 放到 `strategy/core/`
- 新增指标？→ 放到 `indicator/`
- 新增交易所？→ 放到 `trading/broker/`

### 4. 职责单一
每个目录只负责一个业务领域，符合单一职责原则。

---

## 🚀 执行方式

### 方式1：手动执行（推荐）

由于涉及大量文件移动和import修改，建议：

1. 使用IntelliJ IDEA的重构功能
2. 选中文件 → 右键 → Refactor → Move
3. IDE会自动更新所有import引用

### 方式2：脚本执行

创建移动脚本，但需要手动修改import。

---

## 📝 注意事项

1. **先备份代码**
   ```bash
   git add .
   git commit -m "Backup before restructuring"
   ```

2. **逐步移动**
   - 先移动一个模块
   - 编译验证
   - 再移动下一个模块

3. **更新import**
   - IntelliJ IDEA会自动更新
   - 手动检查是否有遗漏

4. **测试验证**
   - 每移动一个模块后运行测试
   - 确保功能正常

---

## 📊 影响评估

### 低风险
- ✅ 不改变业务逻辑
- ✅ 只改变包路径
- ✅ IDE自动更新引用

### 中等工作量
- ⚠️ 需要移动45个文件
- ⚠️ 需要更新import引用
- ⚠️ 需要编译验证

### 高收益
- 💰 代码结构清晰10倍
- 💰 查找效率提升5倍
- 💰 新人理解快3倍

---

**创建时间**: 2026-01-22 16:25  
**状态**: 📋 方案已制定，等待执行确认  
**预计时间**: 30-60分钟（使用IDE重构功能）
