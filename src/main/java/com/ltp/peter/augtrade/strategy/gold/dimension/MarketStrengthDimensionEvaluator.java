package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度6：市场强度指标评估器
 *
 * 博主核心逻辑：
 * - 金银市场强度指标遭遇极端值 → 清洗结束
 * - 黄金多空顶底指标创新低 → 金价已见底
 * - 白银市场强度指标创下2024年8月8号以来新低
 * - 极端值出现后回升阶段 → 反转确认
 *
 * 评分逻辑：
 * - 市场强度处于极低值且开始回升 → 强烈看多（底部反转）
 * - 市场强度处于极低值 → 看多（接近底部）
 * - 市场强度处于中位 → 中性
 * - 市场强度处于极高值 → 看空（可能见顶）
 * - 顶底指标创新低 → 加分确认
 *
 * @author Peter Wang
 */
public class MarketStrengthDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "市场强度指标";
    private static final double WEIGHT = 0.08;

    // 市场强度阈值（0-100）
    private static final double EXTREME_LOW = 15;
    private static final double LOW = 30;
    private static final double HIGH = 70;
    private static final double EXTREME_HIGH = 85;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double goldStrength = context.getGoldMarketStrength();
        double silverStrength = context.getSilverMarketStrength();
        double topBottom = context.getGoldTopBottomIndicator();
        double topBottomLow = context.getGoldTopBottomRecentLow();

        // 1. 黄金市场强度评分（-50到+50）
        double goldScore;
        if (goldStrength <= EXTREME_LOW) {
            goldScore = 50;
            extreme = true;
            explanation.append(String.format(
                    "黄金市场强度%.1f达到极端低位，清洗大概率已结束，强烈看多；", goldStrength));
        } else if (goldStrength <= LOW) {
            goldScore = 30;
            explanation.append(String.format(
                    "黄金市场强度%.1f处于低位，偏底部信号；", goldStrength));
        } else if (goldStrength >= EXTREME_HIGH) {
            goldScore = -50;
            explanation.append(String.format(
                    "黄金市场强度%.1f达到极端高位，需警惕见顶风险；", goldStrength));
        } else if (goldStrength >= HIGH) {
            goldScore = -30;
            explanation.append(String.format(
                    "黄金市场强度%.1f偏高，偏顶部信号；", goldStrength));
        } else {
            goldScore = 0;
            explanation.append(String.format(
                    "黄金市场强度%.1f处于中位水平；", goldStrength));
        }
        score += goldScore * 0.5;

        // 2. 白银市场强度评分（辅助）
        double silverScore;
        if (silverStrength <= EXTREME_LOW) {
            silverScore = 50;
            explanation.append(String.format(
                    "白银市场强度%.1f极端低位，白银清洗也已结束；", silverStrength));
        } else if (silverStrength <= LOW) {
            silverScore = 20;
            explanation.append(String.format(
                    "白银市场强度%.1f偏低；", silverStrength));
        } else if (silverStrength >= EXTREME_HIGH) {
            silverScore = -40;
            explanation.append(String.format(
                    "白银市场强度%.1f极高，白银可能见顶；", silverStrength));
        } else {
            silverScore = 0;
        }
        score += silverScore * 0.2;

        // 3. 顶底指标评分（-30到+30）
        if (topBottomLow > 0 && topBottom <= topBottomLow * 1.05) {
            // 当前值接近或低于近期最低值 → 见底
            score += 30;
            extreme = true;
            explanation.append(String.format(
                    "多空顶底指标%.2f接近/创新低(近期低点%.2f)，预示金价已见底；",
                    topBottom, topBottomLow));
        } else if (topBottomLow > 0 && topBottom <= topBottomLow * 1.2) {
            score += 15;
            explanation.append(String.format(
                    "多空顶底指标%.2f接近近期低位区域；", topBottom));
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
