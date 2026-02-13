package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度8：散户做空指数评估器
 *
 * 博主核心逻辑：
 * - 黄金散户做空指数处在2025年以来的绝对低位 → 散户做多太多了
 * - 白银散户做空指数也处在低位
 * - 散户做空指数在低位横盘一段时间后上升 → 金银价格回升
 * - 这是一个节奏/时机指标，不是方向指标
 *
 * 评分逻辑：
 * - 做空指数极低（散户极度做多）→ 短期需横盘消化 → 中性偏空（短期）
 * - 做空指数低位开始回升 → 价格即将回升 → 看多
 * - 做空指数高位（散户大量做空）→ 反向看多
 * - 做空指数极高 → 极端反向看多
 *
 * @author Peter Wang
 */
public class RetailSentimentDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "散户做空指数";
    private static final double WEIGHT = 0.05;

    private static final double EXTREME_LOW = 15;
    private static final double LOW = 30;
    private static final double HIGH = 65;
    private static final double EXTREME_HIGH = 80;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double retailShortIndex = context.getRetailShortIndex();

        if (retailShortIndex >= EXTREME_HIGH) {
            score = 60;
            extreme = true;
            explanation.append(String.format(
                    "散户做空指数%.1f处于极高水平（散户极度看空），经典反向信号，强看多；",
                    retailShortIndex));
        } else if (retailShortIndex >= HIGH) {
            score = 30;
            explanation.append(String.format(
                    "散户做空指数%.1f偏高（散户偏空），反向看多；",
                    retailShortIndex));
        } else if (retailShortIndex >= LOW) {
            score = 0;
            explanation.append(String.format(
                    "散户做空指数%.1f处于中位，情绪均衡，中性；",
                    retailShortIndex));
        } else if (retailShortIndex >= EXTREME_LOW) {
            score = -20;
            explanation.append(String.format(
                    "散户做空指数%.1f偏低（散户过度做多），短期需横盘消化，节奏偏谨慎；",
                    retailShortIndex));
        } else {
            score = -40;
            extreme = true;
            explanation.append(String.format(
                    "散户做空指数%.1f处于绝对低位（散户极度做多），" +
                    "预示短期可能继续横盘甚至回调，但中期看多不变（需等做空指数回升）；",
                    retailShortIndex));
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
