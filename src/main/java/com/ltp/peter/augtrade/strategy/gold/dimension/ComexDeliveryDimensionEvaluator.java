package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度5：COMEX交割量评估器
 *
 * 博主核心逻辑：
 * - COMEX金银交割量本月相当迅猛
 * - 2月是黄金的交割大月，交割量已经迅速攀升
 * - 交割量迅猛 → 实物需求强劲 → 看多
 * - COMEX金库和国内上期所/上金所白银库存都非常低
 *
 * 评分逻辑：
 * - 交割量远超历史均值 → 实物需求旺盛 → 强看多
 * - 交割量接近历史均值 → 中性
 * - 交割量远低于历史均值 → 需求疲软 → 看空
 *
 * @author Peter Wang
 */
public class ComexDeliveryDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "COMEX交割量";
    private static final double WEIGHT = 0.07;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double goldDelivery = context.getComexGoldDeliveryVolume();
        double goldAvg = context.getComexGoldDeliveryAvg();
        double silverDelivery = context.getComexSilverDeliveryVolume();
        double silverAvg = context.getComexSilverDeliveryAvg();

        if (goldAvg <= 0 && silverAvg <= 0) {
            return DimensionScore.builder()
                    .dimensionName(NAME)
                    .category(DimensionCategory.MICRO)
                    .score(0)
                    .weight(WEIGHT)
                    .weightedContribution(0)
                    .explanation("无COMEX交割数据")
                    .extremeTriggered(false)
                    .build();
        }

        // 黄金交割量评分（主要权重）
        if (goldAvg > 0) {
            double goldRatio = goldDelivery / goldAvg;
            double goldScore;
            if (goldRatio >= 2.0) {
                goldScore = 60;
                extreme = true;
                explanation.append(String.format(
                        "COMEX黄金交割量为历史均值的%.1f倍，实物需求极其旺盛（极端信号）；", goldRatio));
            } else if (goldRatio >= 1.5) {
                goldScore = 40;
                explanation.append(String.format(
                        "COMEX黄金交割量为历史均值的%.1f倍，需求强劲；", goldRatio));
            } else if (goldRatio >= 1.0) {
                goldScore = 15;
                explanation.append(String.format(
                        "COMEX黄金交割量为历史均值的%.1f倍，需求正常偏强；", goldRatio));
            } else if (goldRatio >= 0.5) {
                goldScore = -10;
                explanation.append(String.format(
                        "COMEX黄金交割量仅为历史均值的%.1f倍，需求偏弱；", goldRatio));
            } else {
                goldScore = -40;
                explanation.append(String.format(
                        "COMEX黄金交割量仅为历史均值的%.1f倍，需求疲软；", goldRatio));
            }
            score += goldScore * 0.7; // 黄金占70%权重
        }

        // 白银交割量评分（辅助权重）
        if (silverAvg > 0) {
            double silverRatio = silverDelivery / silverAvg;
            double silverScore;
            if (silverRatio >= 2.0) {
                silverScore = 60;
                explanation.append(String.format(
                        "COMEX白银交割量为历史均值的%.1f倍，白银实物需求也很旺盛；", silverRatio));
            } else if (silverRatio >= 1.0) {
                silverScore = 15;
                explanation.append(String.format(
                        "COMEX白银交割量为历史均值的%.1f倍；", silverRatio));
            } else {
                silverScore = -20;
                explanation.append(String.format(
                        "COMEX白银交割量为历史均值的%.1f倍，白银需求偏弱；", silverRatio));
            }
            score += silverScore * 0.3; // 白银占30%权重
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
