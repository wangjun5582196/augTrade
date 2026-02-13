package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

import java.util.List;

/**
 * 维度11：回撤幅度评估器
 *
 * 博主核心逻辑：
 * - 金价本次最大回撤幅度达到2022年12月8日以来最大
 * - 放眼整个历史也是排名靠前的
 * - 以收盘价计算，7个交易日内崩盘程度远超1980年和2011年
 * - 极端回撤往往预示见底
 *
 * 评分逻辑：
 * - 回撤幅度达历史极端（95%分位以上）→ 强烈看多（见底信号）
 * - 回撤幅度较大（80%分位以上）→ 看多
 * - 回撤幅度正常 → 中性
 * - 无明显回撤 → 中性（不作为看空依据）
 *
 * @author Peter Wang
 */
public class DrawdownDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "回撤幅度";
    private static final double WEIGHT = 0.02;

    // 绝对回撤阈值
    private static final double EXTREME_DRAWDOWN = 10.0;   // 10%以上
    private static final double LARGE_DRAWDOWN = 6.0;       // 6%以上
    private static final double MODERATE_DRAWDOWN = 3.0;     // 3%以上

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double drawdown = context.getGoldDrawdownPercent();
        List<Double> historicalDrawdowns = context.getHistoricalMaxDrawdowns();

        if (drawdown <= 0) {
            return DimensionScore.builder()
                    .dimensionName(NAME)
                    .category(DimensionCategory.SENTIMENT)
                    .score(0)
                    .weight(WEIGHT)
                    .weightedContribution(0)
                    .explanation("当前无明显回撤，中性；")
                    .extremeTriggered(false)
                    .build();
        }

        if (historicalDrawdowns != null && !historicalDrawdowns.isEmpty()) {
            // 计算当前回撤在历史中的分位数
            long count = historicalDrawdowns.stream().filter(d -> d <= drawdown).count();
            double percentile = (double) count / historicalDrawdowns.size();

            if (percentile >= 0.95) {
                score = 80;
                extreme = true;
                explanation.append(String.format(
                        "金价回撤%.1f%%达到历史%.0f%%分位（极端水平），" +
                        "类似或超过2022年12月、2011年和1980年的崩盘级回撤，" +
                        "强烈预示见底（极端信号）；",
                        drawdown, percentile * 100));
            } else if (percentile >= 0.80) {
                score = 50;
                explanation.append(String.format(
                        "金价回撤%.1f%%达到历史%.0f%%分位（较大回撤），偏见底信号；",
                        drawdown, percentile * 100));
            } else if (percentile >= 0.50) {
                score = 15;
                explanation.append(String.format(
                        "金价回撤%.1f%%处于历史中位水平；",
                        drawdown));
            } else {
                score = 0;
                explanation.append(String.format(
                        "金价回撤%.1f%%在历史上并不极端，中性；",
                        drawdown));
            }
        } else {
            // 无历史数据，用绝对值判断
            if (drawdown >= EXTREME_DRAWDOWN) {
                score = 70;
                extreme = true;
                explanation.append(String.format(
                        "金价回撤%.1f%%（极端水平），强烈预示见底；", drawdown));
            } else if (drawdown >= LARGE_DRAWDOWN) {
                score = 40;
                explanation.append(String.format(
                        "金价回撤%.1f%%（较大），偏见底信号；", drawdown));
            } else if (drawdown >= MODERATE_DRAWDOWN) {
                score = 15;
                explanation.append(String.format(
                        "金价回撤%.1f%%（温和）；", drawdown));
            } else {
                score = 0;
                explanation.append(String.format(
                        "金价回撤%.1f%%（小幅），中性；", drawdown));
            }
        }

        score = Math.max(-100, Math.min(100, score));

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.SENTIMENT)
                .score(score)
                .weight(WEIGHT)
                .weightedContribution(score * WEIGHT)
                .explanation(explanation.toString())
                .extremeTriggered(extreme)
                .build();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public DimensionCategory getCategory() {
        return DimensionCategory.SENTIMENT;
    }

    @Override
    public double getWeight() {
        return WEIGHT;
    }
}
