package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度4：看涨期权成交量评估器
 *
 * 博主核心逻辑：
 * - 看涨期权成交量距离高点下降不少 → 金价大概率已见底
 * - 看涨期权成交量3d-MA处于低位 → 金价大概率已见底
 * - 成交量从高位大幅下降意味着FOMO恐慌情绪已释放完毕
 *
 * 评分逻辑：
 * - 3d-MA相比近期高点下降超50% → 恐慌释放，见底概率大 → 看多
 * - 3d-MA处于近期高点附近 → FOMO过热 → 偏空
 *
 * @author Peter Wang
 */
public class CallOptionVolumeDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "看涨期权成交量";
    private static final double WEIGHT = 0.08;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double volume3dMA = context.getCallOptionVolume3dMA();
        double recentHigh = context.getCallOptionVolumeRecentHigh();

        if (recentHigh <= 0) {
            return DimensionScore.builder()
                    .dimensionName(NAME)
                    .category(DimensionCategory.MICRO)
                    .score(0)
                    .weight(WEIGHT)
                    .weightedContribution(0)
                    .explanation("无看涨期权成交量数据")
                    .extremeTriggered(false)
                    .build();
        }

        // 计算当前3d-MA相对近期高点的回落比例
        double declineRatio = (recentHigh - volume3dMA) / recentHigh;

        if (declineRatio >= 0.7) {
            // 成交量暴跌超70%，极端恐慌释放
            score = 80;
            extreme = true;
            explanation.append(String.format(
                    "看涨期权成交量3d-MA已从高点回落%.0f%%，恐慌情绪极度释放，金价大概率已见底（极端信号）；",
                    declineRatio * 100));
        } else if (declineRatio >= 0.5) {
            // 成交量下降超50%
            score = 50;
            explanation.append(String.format(
                    "看涨期权成交量3d-MA从高点回落%.0f%%，FOMO情绪大幅缓解，偏见底信号；",
                    declineRatio * 100));
        } else if (declineRatio >= 0.3) {
            // 成交量下降30-50%
            score = 20;
            explanation.append(String.format(
                    "看涨期权成交量3d-MA从高点回落%.0f%%，情绪开始冷却；",
                    declineRatio * 100));
        } else if (declineRatio >= 0.1) {
            // 成交量仍在高位附近
            score = -20;
            explanation.append(String.format(
                    "看涨期权成交量3d-MA仍在高位（仅回落%.0f%%），FOMO情绪未消退；",
                    declineRatio * 100));
        } else {
            // 成交量在峰值或创新高
            score = -60;
            explanation.append("看涨期权成交量接近或创新高，FOMO极度亢奋，需警惕回调风险；");
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
