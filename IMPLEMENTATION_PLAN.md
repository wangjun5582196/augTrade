# 优化版方案C实施计划
**开始时间**: 2026-03-09 17:32  
**预计完成**: 4-5天  
**目标**: 精简指标至9个，提升实时性，记录信号数据

---

## 📋 实施清单

### 阶段1：移除滞后指标（第1-2天）

#### 1.1 禁用/移除的子策略
- [ ] **RSIStrategy.java** - 与Williams %R重复，移除
- [ ] **BollingerBreakoutStrategy.java** - 布林带滞后，移除  
- [ ] **BalancedAggressiveStrategy.java** - 包含MACD等滞后指标，移除

#### 1.2 保留的子策略
- [x] **SupertrendStrategy.java** - 保留（权重8，实时性好）
- [x] **TrendFilterStrategy.java** - 保留（权重12，EMA趋势）
- [x] **VWAPStrategy.java** - 保留但降低权重到0（仅日志分析）
- [x] **WilliamsStrategy.java** - 保留（Williams %R）
- [x] **RangingMarketStrategy.java** - 保留（震荡市策略）

#### 1.3 修改CompositeStrategy
- [ ] 移除布林带价格过滤逻辑
- [ ] 移除EMA20/50趋势判断（替换为HMA）
- [ ] 简化validatePricePosition方法

---

### 阶段2：新增高实时性指标（第2-3天）

#### 2.1 创建新的指标计算器

##### ✅ 任务1：MomentumCalculator.java
```java
位置: src/main/java/com/ltp/peter/augtrade/indicator/MomentumCalculator.java

功能:
- 计算2根K线动量（短期）
- 计算5根K线动量（中期）  
- 与ATR结合判断动量强度

返回值: MomentumResult {
    momentum2: BigDecimal
    momentum5: BigDecimal
    isStrongUp: boolean
    isStrongDown: boolean
}
```

##### ✅ 任务2：VolumeBreakoutCalculator.java
```java
位置: src/main/java/com/ltp/peter/augtrade/indicator/VolumeBreakoutCalculator.java

功能:
- 计算当前成交量 / 20周期平均成交量
- 判断是否放量突破（>1.5倍）
- 判断是否缩量（<0.5倍）

返回值: VolumeBreakoutResult {
    volumeRatio: double
    isBreakout: boolean  // >1.5
    isShrinking: boolean // <0.5
    avgVolume: BigDecimal
}
```

##### ✅ 任务3：SwingPointCalculator.java
```java
位置: src/main/java/com/ltp/peter/augtrade/indicator/SwingPointCalculator.java

功能:
- 识别最近的摆动高点（支撑位）
- 识别最近的摆动低点（阻力位）
- 判断价格是否突破摆动点

返回值: SwingPointResult {
    lastSwingHigh: SwingPoint
    lastSwingLow: SwingPoint  
    isBreakingHigh: boolean
    isNearSupport: boolean
}

内部类 SwingPoint {
    price: BigDecimal
    index: int
    time: LocalDateTime
}
```

##### ✅ 任务4：HMACalculator.java
```java
位置: src/main/java/com/ltp/peter/augtrade/indicator/HMACalculator.java

功能:
- 计算Hull Moving Average（比EMA快50%）
- HMA = WMA(2×WMA(n/2) - WMA(n), sqrt(n))
- 判断HMA趋势方向（上升/下降）

返回值: HMAResult {
    hma20: double
    isUpTrend: boolean
    isDownTrend: boolean
    slope: double  // 斜率，判断趋势强度
}
```

#### 2.2 创建对应的Strategy类

##### ✅ 任务5：MomentumStrategy.java
```java
权重: 4分
逻辑:
- momentum2 > ATR × 0.2 → +2分
- momentum5 > ATR × 0.4 → +2分
```

##### ✅ 任务6：VolumeBreakoutStrategy.java  
```java
权重: 5分
逻辑:
- volumeRatio > 1.5 → +5分（强放量）
- volumeRatio > 1.2 → +3分（温和放量）
- volumeRatio < 0.5 → -2分（缩量警告）
```

##### ✅ 任务7：SwingPointStrategy.java
```java
权重: 7分
逻辑:
- 突破摆动高点 + 放量 → +7分
- 价格在摆动低点上方(支撑有效) → +4分
```

---

### 阶段3：修改CompositeStrategy（第3天）

#### 3.1 集成新指标
- [ ] 在generateSignal方法中调用新指标
- [ ] 计算动量评分（0-4分）
- [ ] 计算成交量评分（0-5分）
- [ ] 计算摆动点评分（0-7分）
- [ ] 累加到buyScore/sellScore

#### 3.2 修改价格位置过滤
- [ ] 移除布林带逻辑
- [ ] 改用摆动点作为支撑阻力判断
- [ ] 保留ADX动态容忍度逻辑

#### 3.3 修改趋势判断
- [ ] EMA20/50 → HMA20
- [ ] 简化趋势确认逻辑

---

### 阶段4：记录信号数据（第4天）

#### 4.1 扩展TradingSignal类
```java
位置: src/main/java/com/ltp/peter/augtrade/strategy/signal/TradingSignal.java

新增字段:
- buyScore: int  // 做多总分
- sellScore: int  // 做空总分
- buyReasons: List<String>  // 做多理由列表
- sellReasons: List<String>  // 做空理由列表

// 新增指标快照
- momentum2: BigDecimal
- momentum5: BigDecimal  
- volumeRatio: double
- lastSwingHigh: BigDecimal
- lastSwingLow: BigDecimal
- hma20: double
- hma20Slope: double
```

#### 4.2 扩展TradeOrder实体
```java
位置: src/main/java/com/ltp/peter/augtrade/entity/TradeOrder.java

新增字段（已有部分，需检查）:
- buy_score: int  // 做多评分
- sell_score: int  // 做空评分  
- signal_reasons: String  // JSON格式的理由列表

// 新指标字段
- momentum2: BigDecimal
- momentum5: BigDecimal
- volume_ratio: double
- swing_high: BigDecimal
- swing_low: BigDecimal  
- hma20: double
```

#### 4.3 创建数据库迁移脚本
```sql
位置: src/main/resources/db/migration/V1_5__add_signal_tracking_fields.sql

ALTER TABLE t_trade_order ADD COLUMN buy_score INT DEFAULT 0;
ALTER TABLE t_trade_order ADD COLUMN sell_score INT DEFAULT 0;
ALTER TABLE t_trade_order ADD COLUMN signal_reasons TEXT;
ALTER TABLE t_trade_order ADD COLUMN momentum2 DECIMAL(20,8);
ALTER TABLE t_trade_order ADD COLUMN momentum5 DECIMAL(20,8);
ALTER TABLE t_trade_order ADD COLUMN volume_ratio DECIMAL(10,4);
ALTER TABLE t_trade_order ADD COLUMN swing_high DECIMAL(20,2);
ALTER TABLE t_trade_order ADD COLUMN swing_low DECIMAL(20,2);
ALTER TABLE t_trade_order ADD COLUMN hma20 DECIMAL(20,2);
```

#### 4.4 修改TradeExecutionService
```java
位置: src/main/java/com/ltp/peter/augtrade/trading/execution/TradeExecutionService.java

修改: executeTrade方法
- 从TradingSignal提取信号数据
- 保存到TradeOrder对应字段
- 记录JSON格式的理由列表
```

---

### 阶段5：配置和测试（第5天）

#### 5.1 修改application.yml
```yaml
trading:
  indicators:
    momentum:
      enabled: true
      short-period: 2
      medium-period: 5
      threshold-multiplier: 0.3
    
    volume:
      enabled: true
      lookback: 20
      breakout-ratio: 1.5
      shrink-ratio: 0.5
    
    swing:
      enabled: true
      lookback: 5
      tolerance: 0.5
    
    hma:
      enabled: true
      period: 20
```

#### 5.2 单元测试
- [ ] MomentumCalculatorTest
- [ ] VolumeBreakoutCalculatorTest
- [ ] SwingPointCalculatorTest
- [ ] HMACalculatorTest

#### 5.3 集成测试
- [ ] CompositeStrategy集成测试
- [ ] 信号数据记录测试
- [ ] 数据库字段验证

#### 5.4 回测验证
- [ ] 使用最近3个月数据回测
- [ ] 对比优化前后效果
- [ ] 生成回测报告

---

## 📊 预期成果

### 指标精简效果
- 移除前：12-15个指标
- 移除后：9个指标
- 滞后指标：5个 → 0个

### 性能提升
- 计算时间：~3秒 → ~2秒（减少33%）
- 代码复杂度：降低30%

### 交易效果
- 开仓机会：增加35-45%
- 胜率：67.6% → 71-73%（提升4-6%）
- 月度盈利：提升45-60%

---

## 🎯 最终指标列表（9个）

### 快速信号层（4个）
1. ✅ K线形态（CandlePattern）- 已有
2. 🆕 摆动高低点（SwingPoint）- 新增
3. 🆕 价格动量（Momentum）- 新增
4. 🆕 成交量突破（VolumeBreakout）- 新增

### 趋势确认层（3个）
5. ✅ ADX - 已有
6. ✅ Supertrend - 已有
7. 🆕 HMA20 - 新增（替代EMA）

### 风控过滤层（2个）
8. ✅ ATR - 已有
9. ✅ Williams %R - 已有

---

## ⚠️ 注意事项

1. **数据库备份**：修改TradeOrder表前先备份
2. **灰度发布**：先在模拟账户测试1-2周
3. **性能监控**：记录每次策略执行时间
4. **日志完整**：所有新指标都要有详细日志
5. **回滚计划**：保留旧代码分支，随时可回滚

---

**创建时间**: 2026-03-09 17:32  
**负责人**: Peter Wang  
**状态**: 待开始
