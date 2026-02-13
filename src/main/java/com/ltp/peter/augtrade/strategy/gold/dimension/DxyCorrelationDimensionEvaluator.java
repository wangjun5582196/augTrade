package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度10：美元指数相关性评估器
 *
 * 博主核心逻辑：
 * - 金价与美元指数的30、60、90日滚动相关性
 * - 负相关性大幅增加 → 金价受美元压制加大
 * - 短期30d滚动相关性达到-0.56（最大值-1）
 * - 美元指数2月总体表现为震荡上涨 → 金价2月震荡概率大
 *
 * 评分逻辑：
 * - 负相关性极强（<-0.5） + 美元走弱 → 看多
 * - 负相关性极强（<-0.5） + 美元走强 → 看空
 * - 相关性接近0 → 金价独立运行，中性
 * - 正相关（罕见）→ 需要特别关注
 *
 * @author Peter Wang
 */
public class DxyCorrelationDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "美元相关性";
    private static final double WEIGHT = 0.03;

    // 美元指数关键阈值
    private static final double DXY_STRONG = 105;
    private static final double DXY_NEUTRAL = 100;
    private static final double DXY_WEAK = 95;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double corr30d = context.getGoldDxyCorrelation30d();
        double corr60d = context.getGoldDxyCorrelation60d();
        double corr90d = context.getGoldDxyCorrelation90d();
        double dxy = context.getDxyIndex();

        // 使用加权相关性（短期权重更大）
        double weightedCorr = corr30d * 0.5 + corr60d * 0.3 + corr90d * 0.2;

        explanation.append(String.format("金价-美元相关性: 30d=%.2f, 60d=%.2f, 90d=%.2f (加权=%.2f)；",
                corr30d, corr60d, corr90d, weightedCorr));

        if (weightedCorr < -0.4) {
            // 强负相关，美元走势对金价影响大
            if (dxy > DXY_STRONG) {
                score = -40;
                explanation.append(String.format(
                        "负相关性强且美元指数%.1f偏强，金价承压，短期看空；", dxy));
            } else if (dxy < DXY_WEAK) {
                score = 40;
                explanation.append(String.format(
                        "负相关性强且美元指数%.1f偏弱，利好金价，看多；", dxy));
            } else {
                score = -10;
                explanation.append(String.format(
                        "负相关性强但美元指数%.1f中性，金价受美元牵制但空间有限；", dxy));
            }
        } else if (weightedCorr < -0.2) {
            // 中度负相关
            if (dxy > DXY_STRONG) {
                score = -20;
                explanation.append(String.format("中度负相关+美元偏强(%.1f)，小幅看空；", dxy));
            } else if (dxy < DXY_WEAK) {
                score = 20;
                explanation.append(String.format("中度负相关+美元偏弱(%.1f)，小幅看多；", dxy));
            } else {
                score = 0;
                explanation.append("中度负相关+美元中性，影响不大；");
            }
        } else if (weightedCorr > 0.2) {
            // 正相关（罕见情况）
            extreme = true;
            if (dxy > DXY_STRONG) {
                score = 20;
                explanation.append(String.format(
                        "金价与美元呈现罕见正相关+美元走强(%.1f)，可能反映避险需求同时推升两者；", dxy));
            } else {
                score = -10;
                explanation.append("金价与美元呈现罕见正相关，需密切关注市场逻辑变化；");
            }
        } else {
            // 弱相关，金价独立运行
            score = 5;
            explanation.append("金价与美元弱相关，金价独立运行中；");
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
