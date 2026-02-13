package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

import java.util.Collections;
import java.util.List;

/**
 * 维度3：保证金压力评估器（反向指标）
 *
 * 博主核心逻辑：
 * - CME、SHFE连续上调保证金是监管施压的表现
 * - 历史经验：保证金占头寸名义价值比例见顶后，白银价格才能见底
 * - 保证金比例下降，白银才能再次上涨
 * - 2026年2月白银保证金已达20%，回到历史高点水平（高于2008年和2011年）
 * - 这是一个典型的反向指标（Contrarian Indicator）
 *
 * 评分逻辑（反向）：
 * - 保证金率达到历史极值 → 价格见底概率大 → 看多（反向）
 * - 保证金率处于正常水平 → 中性
 * - 保证金率处于低位 → 投机过热，可能见顶 → 看空
 *
 * @author Peter Wang
 */
public class MarginPressureDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "保证金压力指标";
    private static final double WEIGHT = 0.10;

    // 保证金率分位数阈值
    private static final double MARGIN_EXTREME_HIGH_PERCENTILE = 0.95;  // 95%分位以上
    private static final double MARGIN_HIGH_PERCENTILE = 0.80;          // 80%分位
    private static final double MARGIN_LOW_PERCENTILE = 0.20;           // 20%分位

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double currentMarginRate = context.getCmeSilverMarginRate(); // 主要看白银保证金
        List<Double> historicalRatios = context.getHistoricalMarginRatios();

        if (historicalRatios == null || historicalRatios.isEmpty()) {
            // 仅基于绝对值判断
            score = evaluateByAbsoluteRate(currentMarginRate, explanation);
        } else {
            // 计算当前保证金率在历史中的分位数
            double percentile = calculatePercentile(historicalRatios, currentMarginRate);

            if (percentile >= MARGIN_EXTREME_HIGH_PERCENTILE) {
                // 保证金率达到历史极端高位 → 反向看多（价格见底）
                score = 80;
                extreme = true;
                explanation.append(String.format(
                        "CME保证金率%.1f%%已达历史%.0f%%分位（极端高位），" +
                        "历史经验表明保证金比例见顶后价格见底，" +
                        "类似1980年亨特兄弟事件和2011年白银清洗后的反弹，" +
                        "强烈看多（反向信号）；",
                        currentMarginRate, percentile * 100));
            } else if (percentile >= MARGIN_HIGH_PERCENTILE) {
                // 保证金率较高 → 偏多
                score = 40 + (percentile - MARGIN_HIGH_PERCENTILE) /
                        (MARGIN_EXTREME_HIGH_PERCENTILE - MARGIN_HIGH_PERCENTILE) * 40;
                explanation.append(String.format(
                        "CME保证金率%.1f%%处于历史%.0f%%分位（偏高），" +
                        "杠杆资金清洗进行中，偏看多（反向信号）；",
                        currentMarginRate, percentile * 100));
            } else if (percentile >= MARGIN_LOW_PERCENTILE) {
                // 保证金率正常 → 中性
                score = 0;
                explanation.append(String.format(
                        "CME保证金率%.1f%%处于历史%.0f%%分位（正常水平），中性信号；",
                        currentMarginRate, percentile * 100));
            } else {
                // 保证金率极低 → 投机过热，反向看空
                score = -60;
                explanation.append(String.format(
                        "CME保证金率%.1f%%处于历史%.0f%%分位（极低），" +
                        "杠杆投机过热，需警惕监管提保风险，看空（反向信号）；",
                        currentMarginRate, percentile * 100));
            }
        }

        score = Math.max(-100, Math.min(100, score));

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.MICRO)
                .score(score)
                .weight(WEIGHT)
                .weightedContribution(score * WEIGHT)
                .explanation(explanation.toString())
                .extremeTriggered(extreme)
                .build();
    }

    /**
     * 基于保证金绝对值评估（无历史数据时使用）
     */
    private double evaluateByAbsoluteRate(double marginRate, StringBuilder explanation) {
        if (marginRate >= 18) {
            explanation.append(String.format("CME保证金率%.1f%%（极高，接近2026年2月20%%水平），强烈看多（反向）；", marginRate));
            return 70;
        } else if (marginRate >= 14) {
            explanation.append(String.format("CME保证金率%.1f%%（偏高），偏看多（反向）；", marginRate));
            return 30;
        } else if (marginRate >= 8) {
            explanation.append(String.format("CME保证金率%.1f%%（正常），中性；", marginRate));
            return 0;
        } else {
            explanation.append(String.format("CME保证金率%.1f%%（偏低，杠杆过高），看空（反向）；", marginRate));
            return -40;
        }
    }

    /**
     * 计算当前值在历史数据中的分位数
     */
    private double calculatePercentile(List<Double> data, double value) {
        long count = data.stream().filter(d -> d <= value).count();
        return (double) count / data.size();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public DimensionCategory getCategory() {
        return DimensionCategory.MICRO;
    }

    @Override
    public double getWeight() {
        return WEIGHT;
    }
}
