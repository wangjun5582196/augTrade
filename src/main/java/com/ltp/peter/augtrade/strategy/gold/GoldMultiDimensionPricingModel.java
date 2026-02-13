package com.ltp.peter.augtrade.strategy.gold;

import com.ltp.peter.augtrade.strategy.gold.dimension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 黄金多维度定价模型 - 核心引擎
 *
 * 基于博主分析框架：
 * 宏观流动性定大方向 → 微观数据定区间 → 情绪/季节性定节奏
 *
 * 模型工作流程：
 * 1. 宏观流动性 → 年度/月度目标价区间
 * 2. 期权Gamma敞口 → 周度支撑/阻力/磁吸点
 * 3. 11个维度评估器并行评分 → 加权综合评分
 * 4. 综合评分 → 信号方向 + 强度
 * 5. 结合定价区间和信号 → 生成交易建议
 *
 * 权重分配（总计100%）：
 * - 流动性宏观趋势: 25%  （宏观层）
 * - 期权Gamma敞口:  20%  （微观层）
 * - 保证金压力:     10%  （微观层，反向指标）
 * - 看涨期权成交量:  8%  （微观层）
 * - 市场强度指标:    8%  （情绪层）
 * - ETF区域流向:     8%  （情绪层）
 * - COMEX交割量:     7%  （微观层）
 * - 散户做空指数:    5%  （情绪层）
 * - 季节性:          4%  （情绪层）
 * - 美元相关性:      3%  （情绪层）
 * - 回撤幅度:        2%  （情绪层）
 *
 * @author Peter Wang
 */
public class GoldMultiDimensionPricingModel {

    private static final Logger log = LoggerFactory.getLogger(GoldMultiDimensionPricingModel.class);

    /**
     * 所有维度评估器
     */
    private final List<DimensionEvaluator> evaluators;

    /**
     * 流动性定价系数（基于历史回归）
     * GoldTarget = ALPHA * Liquidity + BETA * LiquidityGrowth + GAMMA * DebtToGDP + INTERCEPT
     */
    private static final double ALPHA = 0.018;    // 流动性绝对水平系数
    private static final double BETA = 8000.0;    // 流动性增长率系数
    private static final double GAMMA = 1200.0;   // 债务/GDP系数
    private static final double INTERCEPT = -500;  // 截距项

    /**
     * 月度波动率映射（用于从年度目标推导月度区间）
     */
    private static final double MONTHLY_VOLATILITY = 0.08; // 8%月度标准波动率

    /**
     * 博主风控参数
     */
    private static final double MIN_TOLERANCE_USD = 1500.0;  // 最低容错空间
    private static final double SCALING_RANGE_USD = 100.0;    // 分批建仓间距
    private static final int SCALING_LEVELS = 5;               // 分批建仓层数

    public GoldMultiDimensionPricingModel() {
        this.evaluators = initializeEvaluators();
    }

    /**
     * 初始化所有11个维度评估器
     */
    private List<DimensionEvaluator> initializeEvaluators() {
        List<DimensionEvaluator> list = new ArrayList<>();
        // 宏观层
        list.add(new LiquidityDimensionEvaluator());
        // 微观层
        list.add(new GammaExposureDimensionEvaluator());
        list.add(new MarginPressureDimensionEvaluator());
        list.add(new CallOptionVolumeDimensionEvaluator());
        list.add(new ComexDeliveryDimensionEvaluator());
        // 情绪层
        list.add(new MarketStrengthDimensionEvaluator());
        list.add(new ETFFlowDimensionEvaluator());
        list.add(new RetailSentimentDimensionEvaluator());
        list.add(new SeasonalityDimensionEvaluator());
        list.add(new DxyCorrelationDimensionEvaluator());
        list.add(new DrawdownDimensionEvaluator());
        return Collections.unmodifiableList(list);
    }

    /**
     * 执行完整的多维度定价分析
     *
     * @param context 定价输入上下文
     * @return 定价分析结果
     */
    public GoldPricingResult analyze(GoldPricingContext context) {
        log.info("========== 开始黄金多维度定价分析 ==========");
        log.info("日期: {}, 当前金价: {}", context.getDate(), context.getCurrentGoldPrice());

        // Step 1: 计算年度目标价区间（宏观流动性定调）
        double[] annualTarget = calculateAnnualTarget(context);

        // Step 2: 计算月度波动区间
        double[] monthlyRange = calculateMonthlyRange(context, annualTarget);

        // Step 3: 计算Gamma敞口定位（支撑/阻力/磁吸点）
        GammaAnalysis gammaAnalysis = analyzeGammaExposure(context);

        // Step 4: 执行所有维度评估，获取评分
        List<GoldPricingResult.DimensionScore> dimensionScores = evaluateAllDimensions(context);

        // Step 5: 计算综合加权评分
        double compositeScore = calculateCompositeScore(dimensionScores);

        // Step 6: 确定信号方向和强度
        GoldPricingResult.SignalDirection direction = determineDirection(compositeScore);
        GoldPricingResult.SignalStrength strength = GoldPricingResult.SignalStrength.fromAbsoluteScore(
                Math.abs(compositeScore));

        // Step 7: 计算置信度
        double confidence = calculateConfidence(dimensionScores, compositeScore);

        // Step 8: 生成交易建议
        GoldPricingResult.TradingAdvice advice = generateAdvice(
                context, compositeScore, direction, gammaAnalysis, monthlyRange);

        // Step 9: 生成分析报告
        String report = generateAnalysisReport(context, dimensionScores, compositeScore,
                direction, annualTarget, monthlyRange, gammaAnalysis);

        // Step 10: 构建结果
        GoldPricingResult result = GoldPricingResult.builder()
                .date(context.getDate())
                .currentPrice(context.getCurrentGoldPrice())
                .annualTargetLow(annualTarget[0])
                .annualTargetHigh(annualTarget[1])
                .monthlyRangeLow(monthlyRange[0])
                .monthlyRangeHigh(monthlyRange[1])
                .weeklyRangeLow(gammaAnalysis.weeklyLow)
                .weeklyRangeHigh(gammaAnalysis.weeklyHigh)
                .gammaMagnetPrice(gammaAnalysis.magnetPrice)
                .absoluteSupport(gammaAnalysis.absoluteSupport)
                .absoluteResistance(gammaAnalysis.absoluteResistance)
                .compositeScore(compositeScore)
                .direction(direction)
                .strength(strength)
                .confidence(confidence)
                .dimensionScores(dimensionScores)
                .advice(advice)
                .marketStateDescription(describeMarketState(direction, strength))
                .analysisReport(report)
                .build();

        log.info("综合评分: {}, 方向: {}, 强度: {}, 置信度: {}%",
                String.format("%.1f", compositeScore), direction.getDescription(),
                strength.getDescription(), String.format("%.1f", confidence));
        log.info("========== 黄金多维度定价分析完成 ==========");

        return result;
    }

    // ==================== 核心计算方法 ====================

    /**
     * Step 1: 基于全球流动性计算年度目标价区间
     *
     * 公式: GoldTarget = α×Liquidity + β×GrowthRate + γ×DebtToGDP + intercept
     */
    private double[] calculateAnnualTarget(GoldPricingContext context) {
        double liquidity = context.getGlobalLiquidity();
        double growthRate = context.getLiquidityGrowthRate();
        double debtGdp = context.getDebtToGDPRatio();

        double midpoint = ALPHA * liquidity * 1_000_000_000_000.0 / 1_000_000_000.0
                + BETA * growthRate
                + GAMMA * debtGdp
                + INTERCEPT;

        // 若流动性数据不足，使用当前价格的1.2-1.4倍作为年度目标
        if (liquidity <= 0) {
            double current = context.getCurrentGoldPrice();
            return new double[]{current * 1.05, current * 1.40};
        }

        // 年度区间：中位数 ± 10%
        double low = midpoint * 0.90;
        double high = midpoint * 1.10;

        // 确保合理性：目标价不低于当前价的80%
        low = Math.max(low, context.getCurrentGoldPrice() * 0.80);

        log.info("年度目标价区间: {} - {} (中位: {})",
                String.format("%.0f", low), String.format("%.0f", high), String.format("%.0f", midpoint));
        return new double[]{low, high};
    }

    /**
     * Step 2: 从年度目标推导月度波动区间
     */
    private double[] calculateMonthlyRange(GoldPricingContext context, double[] annualTarget) {
        double annualMid = (annualTarget[0] + annualTarget[1]) / 2.0;
        double currentPrice = context.getCurrentGoldPrice();

        // 月度区间 = 当前价格 ± 月度波动率
        double monthlyLow = currentPrice * (1 - MONTHLY_VOLATILITY);
        double monthlyHigh = currentPrice * (1 + MONTHLY_VOLATILITY);

        // 约束：月度区间不超出年度目标
        monthlyLow = Math.max(monthlyLow, annualTarget[0] * 0.95);
        monthlyHigh = Math.min(monthlyHigh, annualTarget[1]);

        log.info("月度波动区间: {} - {}",
                String.format("%.0f", monthlyLow), String.format("%.0f", monthlyHigh));
        return new double[]{monthlyLow, monthlyHigh};
    }

    /**
     * Step 3: 分析Gamma敞口确定周度支撑阻力
     */
    private GammaAnalysis analyzeGammaExposure(GoldPricingContext context) {
        GammaAnalysis analysis = new GammaAnalysis();
        Map<Double, Double> gammaMap = context.getGoldGammaExposure();
        double currentPrice = context.getCurrentGoldPrice();

        if (gammaMap == null || gammaMap.isEmpty()) {
            // 无Gamma数据，使用当前价格±3%作为默认
            analysis.absoluteSupport = currentPrice * 0.97;
            analysis.absoluteResistance = currentPrice * 1.03;
            analysis.magnetPrice = currentPrice;
            analysis.weeklyLow = currentPrice * 0.97;
            analysis.weeklyHigh = currentPrice * 1.03;
            return analysis;
        }

        double maxAbsGamma = 0;

        // 所有支撑位（当前价格下方的正Gamma）
        TreeMap<Double, Double> supports = new TreeMap<>();
        // 所有阻力位（当前价格上方的负Gamma或正Gamma做空区域）
        TreeMap<Double, Double> resistances = new TreeMap<>();

        for (Map.Entry<Double, Double> entry : gammaMap.entrySet()) {
            double strike = entry.getKey();
            double gamma = entry.getValue();

            if (Math.abs(gamma) > maxAbsGamma) {
                maxAbsGamma = Math.abs(gamma);
                analysis.magnetPrice = strike;
            }

            if (strike <= currentPrice && gamma > 0) {
                supports.put(strike, gamma);
            } else if (strike > currentPrice) {
                resistances.put(strike, gamma);
            }
        }

        // 绝对支撑 = Gamma最大的支撑位
        if (!supports.isEmpty()) {
            analysis.absoluteSupport = supports.entrySet().stream()
                    .max(Comparator.comparingDouble(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(currentPrice * 0.95);
        } else {
            analysis.absoluteSupport = currentPrice * 0.95;
        }

        // 绝对阻力 = 最近的阻力位
        if (!resistances.isEmpty()) {
            analysis.absoluteResistance = resistances.firstKey();
        } else {
            analysis.absoluteResistance = currentPrice * 1.05;
        }

        // 周度范围
        analysis.weeklyLow = analysis.absoluteSupport;
        analysis.weeklyHigh = analysis.absoluteResistance;

        log.info("Gamma分析: 支撑={}, 阻力={}, 磁吸点={}",
                String.format("%.0f", analysis.absoluteSupport),
                String.format("%.0f", analysis.absoluteResistance),
                String.format("%.0f", analysis.magnetPrice));

        return analysis;
    }

    /**
     * Step 4: 执行所有维度评估
     */
    private List<GoldPricingResult.DimensionScore> evaluateAllDimensions(GoldPricingContext context) {
        List<GoldPricingResult.DimensionScore> scores = new ArrayList<>();

        for (DimensionEvaluator evaluator : evaluators) {
            try {
                GoldPricingResult.DimensionScore score = evaluator.evaluate(context);
                scores.add(score);
                log.info("[{}] 评分: {} (权重: {}%, 加权贡献: {}) {}",
                        evaluator.getName(),
                        String.format("%.1f", score.getScore()),
                        String.format("%.0f", evaluator.getWeight() * 100),
                        String.format("%.2f", score.getWeightedContribution()),
                        score.isExtremeTriggered() ? "⚡极端信号" : "");
            } catch (Exception e) {
                log.warn("维度 [{}] 评估失败: {}", evaluator.getName(), e.getMessage());
                scores.add(GoldPricingResult.DimensionScore.builder()
                        .dimensionName(evaluator.getName())
                        .category(evaluator.getCategory())
                        .score(0)
                        .weight(evaluator.getWeight())
                        .weightedContribution(0)
                        .explanation("评估失败: " + e.getMessage())
                        .extremeTriggered(false)
                        .build());
            }
        }

        return scores;
    }

    /**
     * Step 5: 计算加权综合评分
     */
    private double calculateCompositeScore(List<GoldPricingResult.DimensionScore> scores) {
        double totalWeightedScore = 0;
        double totalWeight = 0;

        for (GoldPricingResult.DimensionScore ds : scores) {
            totalWeightedScore += ds.getScore() * ds.getWeight();
            totalWeight += ds.getWeight();
        }

        if (totalWeight <= 0) return 0;
        return totalWeightedScore / totalWeight;
    }

    /**
     * Step 6: 确定信号方向
     */
    private GoldPricingResult.SignalDirection determineDirection(double compositeScore) {
        if (compositeScore >= 60) return GoldPricingResult.SignalDirection.STRONG_BULLISH;
        if (compositeScore >= 25) return GoldPricingResult.SignalDirection.BULLISH;
        if (compositeScore >= -25) return GoldPricingResult.SignalDirection.NEUTRAL;
        if (compositeScore >= -60) return GoldPricingResult.SignalDirection.BEARISH;
        return GoldPricingResult.SignalDirection.STRONG_BEARISH;
    }

    /**
     * Step 7: 计算信号置信度
     * 置信度取决于：维度评分的一致性 + 极端信号数量
     */
    private double calculateConfidence(List<GoldPricingResult.DimensionScore> scores, double compositeScore) {
        if (scores.isEmpty()) return 0;

        // 1. 方向一致性（所有维度评分同方向的比例）
        long bullishCount = scores.stream().filter(s -> s.getScore() > 10).count();
        long bearishCount = scores.stream().filter(s -> s.getScore() < -10).count();
        long totalActive = bullishCount + bearishCount;
        double consistency = totalActive == 0 ? 0.5 :
                (double) Math.max(bullishCount, bearishCount) / totalActive;

        // 2. 极端信号数量加成
        long extremeCount = scores.stream().filter(GoldPricingResult.DimensionScore::isExtremeTriggered).count();
        double extremeBonus = Math.min(0.2, extremeCount * 0.05);

        // 3. 综合评分绝对值加成
        double scoreBonus = Math.min(0.2, Math.abs(compositeScore) / 500.0);

        // 置信度 = 一致性基础 + 极端信号加成 + 评分加成
        double confidence = (consistency * 60) + (extremeBonus * 100) + (scoreBonus * 100);
        return Math.min(95, Math.max(10, confidence));
    }

    /**
     * Step 8: 生成交易建议
     */
    private GoldPricingResult.TradingAdvice generateAdvice(
            GoldPricingContext context,
            double compositeScore,
            GoldPricingResult.SignalDirection direction,
            GammaAnalysis gammaAnalysis,
            double[] monthlyRange) {

        double currentPrice = context.getCurrentGoldPrice();
        GoldPricingResult.AdviceType adviceType;
        double entryLow, entryHigh, stopLoss, takeProfit;
        double positionSize;
        int riskLevel;
        String description;

        switch (direction) {
            case STRONG_BULLISH:
                adviceType = GoldPricingResult.AdviceType.AGGRESSIVE_LONG;
                entryLow = gammaAnalysis.absoluteSupport;
                entryHigh = currentPrice;
                stopLoss = gammaAnalysis.absoluteSupport * 0.97;
                takeProfit = monthlyRange[1];
                positionSize = 30;
                riskLevel = 3;
                description = "强烈看多信号，以Gamma支撑位为中心积极做多，止损设在绝对支撑下方3%";
                break;
            case BULLISH:
                adviceType = GoldPricingResult.AdviceType.SCALE_IN_LONG;
                entryLow = gammaAnalysis.absoluteSupport - SCALING_RANGE_USD;
                entryHigh = gammaAnalysis.absoluteSupport + SCALING_RANGE_USD;
                stopLoss = gammaAnalysis.absoluteSupport * 0.95;
                takeProfit = gammaAnalysis.absoluteResistance;
                positionSize = 20;
                riskLevel = 2;
                description = String.format(
                        "逢低做多为主旋律，以%.0f为中心上下%.0f美元分批买入",
                        gammaAnalysis.absoluteSupport, SCALING_RANGE_USD);
                break;
            case NEUTRAL:
                adviceType = GoldPricingResult.AdviceType.RANGE_TRADE;
                entryLow = gammaAnalysis.weeklyLow;
                entryHigh = gammaAnalysis.weeklyHigh;
                stopLoss = gammaAnalysis.weeklyLow * 0.97;
                takeProfit = gammaAnalysis.weeklyHigh * 1.02;
                positionSize = 15;
                riskLevel = 3;
                description = String.format(
                        "震荡行情，高抛低吸策略，区间%.0f-%.0f",
                        gammaAnalysis.weeklyLow, gammaAnalysis.weeklyHigh);
                break;
            case BEARISH:
                adviceType = GoldPricingResult.AdviceType.WAIT;
                entryLow = 0;
                entryHigh = 0;
                stopLoss = 0;
                takeProfit = 0;
                positionSize = 0;
                riskLevel = 4;
                description = "偏空信号，建议观望等待，不宜追空黄金（长期趋势仍看多）";
                break;
            default:
                adviceType = GoldPricingResult.AdviceType.WAIT;
                entryLow = 0;
                entryHigh = 0;
                stopLoss = 0;
                takeProfit = 0;
                positionSize = 0;
                riskLevel = 5;
                description = "强烈看空，建议清仓等待";
                break;
        }

        // 生成分批建仓价位
        List<Double> scalingLevels = new ArrayList<>();
        if (adviceType == GoldPricingResult.AdviceType.SCALE_IN_LONG ||
                adviceType == GoldPricingResult.AdviceType.AGGRESSIVE_LONG) {
            double center = gammaAnalysis.absoluteSupport;
            for (int i = -(SCALING_LEVELS / 2); i <= SCALING_LEVELS / 2; i++) {
                scalingLevels.add(center + i * SCALING_RANGE_USD);
            }
        }

        return GoldPricingResult.TradingAdvice.builder()
                .type(adviceType)
                .entryRangeLow(entryLow)
                .entryRangeHigh(entryHigh)
                .stopLoss(stopLoss)
                .takeProfit(takeProfit)
                .minToleranceRequired(MIN_TOLERANCE_USD)
                .positionSizePercent(positionSize)
                .scalingLevels(scalingLevels)
                .description(description)
                .riskLevel(riskLevel)
                .build();
    }

    /**
     * 描述市场状态
     */
    private String describeMarketState(GoldPricingResult.SignalDirection direction,
                                       GoldPricingResult.SignalStrength strength) {
        return String.format("市场状态: %s (%s)", direction.getDescription(), strength.getDescription());
    }

    /**
     * Step 9: 生成详细分析报告
     */
    private String generateAnalysisReport(
            GoldPricingContext context,
            List<GoldPricingResult.DimensionScore> scores,
            double compositeScore,
            GoldPricingResult.SignalDirection direction,
            double[] annualTarget,
            double[] monthlyRange,
            GammaAnalysis gammaAnalysis) {

        StringBuilder report = new StringBuilder();
        report.append("═══════════════════════════════════════════\n");
        report.append("       黄金多维度定价模型分析报告\n");
        report.append("═══════════════════════════════════════════\n\n");

        report.append(String.format("📅 分析日期: %s\n", context.getDate()));
        report.append(String.format("💰 当前金价: %.0f 美元/盎司\n\n", context.getCurrentGoldPrice()));

        // 定价区间
        report.append("┌─ 定价区间 ─────────────────────────────┐\n");
        report.append(String.format("│ 📈 年度目标: %.0f - %.0f 美元/盎司      │\n",
                annualTarget[0], annualTarget[1]));
        report.append(String.format("│ 📊 月度区间: %.0f - %.0f 美元/盎司      │\n",
                monthlyRange[0], monthlyRange[1]));
        report.append(String.format("│ 📉 周度区间: %.0f - %.0f 美元/盎司      │\n",
                gammaAnalysis.weeklyLow, gammaAnalysis.weeklyHigh));
        report.append(String.format("│ 🧲 Gamma磁吸点: %.0f 美元              │\n",
                gammaAnalysis.magnetPrice));
        report.append(String.format("│ 🛡️ 绝对支撑: %.0f 美元                │\n",
                gammaAnalysis.absoluteSupport));
        report.append(String.format("│ 🚧 绝对阻力: %.0f 美元                │\n",
                gammaAnalysis.absoluteResistance));
        report.append("└───────────────────────────────────────┘\n\n");

        // 综合评分
        report.append(String.format("📊 综合评分: %.1f / [-100, +100]\n", compositeScore));
        report.append(String.format("🎯 信号方向: %s\n\n", direction.getDescription()));

        // 各维度明细
        report.append("┌─ 各维度评分明细 ──────────────────────┐\n");

        // 按分类分组显示
        Map<GoldPricingResult.DimensionCategory, List<GoldPricingResult.DimensionScore>> grouped =
                scores.stream().collect(Collectors.groupingBy(GoldPricingResult.DimensionScore::getCategory));

        for (GoldPricingResult.DimensionCategory cat : GoldPricingResult.DimensionCategory.values()) {
            List<GoldPricingResult.DimensionScore> catScores = grouped.getOrDefault(cat, Collections.emptyList());
            if (!catScores.isEmpty()) {
                report.append(String.format("│                                       │\n"));
                report.append(String.format("│ 【%s】                              │\n", cat.getDescription()));
                for (GoldPricingResult.DimensionScore ds : catScores) {
                    String arrow = ds.getScore() > 10 ? "🟢" : ds.getScore() < -10 ? "🔴" : "🟡";
                    String extremeFlag = ds.isExtremeTriggered() ? " ⚡" : "";
                    report.append(String.format("│ %s %-12s: %+6.1f (%.0f%%)%s\n",
                            arrow, ds.getDimensionName(), ds.getScore(),
                            ds.getWeight() * 100, extremeFlag));
                    report.append(String.format("│   └ %s\n", ds.getExplanation()));
                }
            }
        }
        report.append("└───────────────────────────────────────┘\n\n");

        // 风控提醒
        report.append("⚠️ 风控提醒:\n");
        report.append(String.format("  • 最低容错空间: %.0f 美元\n", MIN_TOLERANCE_USD));
        report.append("  • 分批建仓，控制仓位\n");
        report.append("  • 以黄金为主，白银为辅\n");
        report.append("  • 假期前需合理安排仓位\n");

        return report.toString();
    }

    // ==================== 内部辅助类 ====================

    /**
     * Gamma分析结果
     */
    private static class GammaAnalysis {
        double absoluteSupport;
        double absoluteResistance;
        double magnetPrice;
        double weeklyLow;
        double weeklyHigh;
    }
}
