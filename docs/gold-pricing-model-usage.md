# 黄金多维度定价模型 - 使用指南

## 🚀 快速开始（3步即可运行）

### 第1步：打开Demo文件
在IntelliJ IDEA中打开：
```
src/main/java/com/ltp/peter/augtrade/strategy/gold/GoldPricingModelDemo.java
```

### 第2步：运行
点击 `main` 方法左侧的 ▶️ 绿色运行按钮，选择 `Run 'GoldPricingModelDemo.main()'`

### 第3步：查看输出
控制台将输出完整的分析报告，包括：
- 年度/月度/周度定价区间
- 11个维度的评分详情
- 综合评分和信号方向
- 具体的交易操作建议（入场位、止损、止盈、分批建仓价位）

---

## 📖 如何在你自己的代码中使用

### 基本用法（3行代码）

```java
// 1. 构建输入数据
GoldPricingContext context = GoldPricingContext.builder()
    .date(LocalDate.now())
    .currentGoldPrice(4750.0)
    // ... 填入各维度数据
    .build();

// 2. 创建模型
GoldMultiDimensionPricingModel model = new GoldMultiDimensionPricingModel();

// 3. 获取分析结果
GoldPricingResult result = model.analyze(context);
```

### 读取结果

```java
// 查看综合评分（-100到+100，正=看多，负=看空）
double score = result.getCompositeScore();

// 查看信号方向
String direction = result.getDirection().getDescription(); 
// 输出: "强烈看多" / "看多" / "中性/震荡" / "看空" / "强烈看空"

// 查看定价区间
System.out.println("年度目标: " + result.getAnnualTargetLow() + " - " + result.getAnnualTargetHigh());
System.out.println("月度区间: " + result.getMonthlyRangeLow() + " - " + result.getMonthlyRangeHigh());
System.out.println("Gamma支撑: " + result.getAbsoluteSupport());
System.out.println("Gamma阻力: " + result.getAbsoluteResistance());

// 查看交易建议
GoldPricingResult.TradingAdvice advice = result.getAdvice();
System.out.println("策略: " + advice.getType().getDescription()); // "逢低分批做多"
System.out.println("入场区间: " + advice.getEntryRangeLow() + " - " + advice.getEntryRangeHigh());
System.out.println("止损: " + advice.getStopLoss());
System.out.println("止盈: " + advice.getTakeProfit());
System.out.println("分批建仓价位: " + advice.getScalingLevels());

// 查看每个维度的详细评分
for (GoldPricingResult.DimensionScore ds : result.getDimensionScores()) {
    System.out.printf("[%s] 评分: %+.1f (权重%.0f%%) - %s%n",
        ds.getDimensionName(), ds.getScore(), ds.getWeight() * 100, ds.getExplanation());
}

// 输出完整分析报告（格式化文本）
System.out.println(result.getAnalysisReport());
```

---

## 📊 输入数据说明

### 你需要填入的数据分三层：

#### 第一层：宏观数据（最重要，决定大方向）
| 字段 | 说明 | 数据来源 | 更新频率 |
|------|------|----------|----------|
| `globalLiquidity` | 全球流动性总量（万亿美元） | 各国央行资产负债表+全球M2 | 月度 |
| `previousGlobalLiquidity` | 上期流动性 | 同上 | 月度 |
| `globalDebt` | 全球债务总额（万亿美元） | IIF全球债务报告 | 季度 |
| `globalGDP` | 全球GDP（万亿美元） | IMF/世界银行 | 季度 |
| `goldReserveShare` | 黄金储备份额(%) | WGC/IMF COFER | 季度 |
| `goldReserveMarketCap` | 黄金储备市值（万亿） | WGC | 月度 |
| `usTreasuryTotal` | 美债总额（万亿） | 美国财政部 | 月度 |

#### 第二层：微观数据（定支撑阻力）
| 字段 | 说明 | 数据来源 | 更新频率 |
|------|------|----------|----------|
| `goldGammaExposure` | Gamma敞口分布 Map<执行价,Gamma值> | SpotGamma/期权数据商 | 每日 |
| `cmeSilverMarginRate` | CME白银保证金率(%) | CME官网 | 变动时 |
| `callOptionVolume3dMA` | 看涨期权成交量3日MA | CME/CBOE | 每日 |
| `callOptionVolumeRecentHigh` | 近期成交量高点 | 同上 | 每日 |
| `comexGoldDeliveryVolume` | COMEX黄金交割量 | CME | 每日 |
| `comexGoldDeliveryAvg` | 交割量历史均值 | CME | 固定 |

#### 第三层：情绪数据（定节奏）
| 字段 | 说明 | 数据来源 | 更新频率 |
|------|------|----------|----------|
| `goldMarketStrength` | 市场强度(0-100) | 自定义指标/技术分析 | 每日 |
| `goldTopBottomIndicator` | 顶底指标 | 自定义 | 每日 |
| `retailShortIndex` | 散户做空指数(0-100) | IG/券商数据 | 每日 |
| `etfFlowAsia/Europe/NorthAmerica` | ETF区域净流入（吨） | WGC/Bloomberg | 每周 |
| `goldDxyCorrelation30d` | 金价-美元30日相关性 | 自行计算 | 每日 |
| `dxyIndex` | 美元指数 | DXY | 实时 |
| `goldDrawdownPercent` | 当前回撤幅度(%) | 自行计算 | 每日 |

### ⚠️ 如果某些数据暂时拿不到？
**没关系！** 模型会自动处理缺失数据：
- 缺少Gamma数据 → 使用当前价格±3%作为默认支撑阻力
- 缺少流动性数据 → 使用当前价格×1.05~1.40作为年度目标
- 缺少历史保证金数据 → 使用绝对值判断
- 任何维度评估失败 → 该维度评分为0，不影响其他维度

---

## 🔄 与现有交易系统集成

### 方式1：在现有策略中调用
```java
// 在你的TradingScheduler或StrategyOrchestrator中
GoldMultiDimensionPricingModel goldModel = new GoldMultiDimensionPricingModel();

// 定期（如每日）运行分析
GoldPricingContext ctx = buildContextFromMarketData(); // 从你的数据源构建
GoldPricingResult result = goldModel.analyze(ctx);

// 根据结果调整策略参数
if (result.getDirection() == SignalDirection.BULLISH) {
    // 调整为偏多策略...
}
```

### 方式2：作为独立的分析工具
每天手动更新数据，运行Demo查看分析报告，辅助决策。

### 方式3：接入FeishuNotification
```java
// 将分析报告发送到飞书
String report = result.getAnalysisReport();
feishuNotificationService.sendMessage(report);
```

---

## 📋 输出结果一览

| 输出字段 | 类型 | 说明 |
|----------|------|------|
| `compositeScore` | double | 综合评分（-100到+100） |
| `direction` | enum | 强烈看多/看多/中性/看空/强烈看空 |
| `strength` | enum | 极端/强/中等/弱/噪音 |
| `confidence` | double | 置信度（0-95%） |
| `annualTargetLow/High` | double | 年度目标价区间 |
| `monthlyRangeLow/High` | double | 月度波动区间 |
| `weeklyRangeLow/High` | double | 周度波动区间 |
| `gammaMagnetPrice` | double | Gamma磁吸锚点 |
| `absoluteSupport` | double | 绝对支撑位 |
| `absoluteResistance` | double | 绝对阻力位 |
| `advice` | TradingAdvice | 含入场位/止损/止盈/仓位/分批价位 |
| `dimensionScores` | List | 11个维度各自评分详情 |
| `analysisReport` | String | 格式化的完整分析报告文本 |
