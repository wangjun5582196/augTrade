package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

import java.time.Month;
import java.util.Map;

/**
 * 维度9：季节性评估器
 *
 * 博主核心逻辑：
 * - 从季节性来看，2月份震荡概率较大
 * - 不要期待单边走势，最好按照宽幅震荡来看待
 * - 美元指数2月总体表现为震荡上涨
 *
 * 评分逻辑：
 * - 基于黄金历史月度表现的统计规律
 * - 看多月份给正分，看空月份给负分
 * - 震荡月份评分接近0（中性）
 *
 * 黄金季节性规律（基于历史统计）：
 * - 强势月份：1月、8月、9月、11月、12月（通常上涨）
 * - 弱势月份：3月、6月（通常回调）
 * - 震荡月份：2月、4月、5月、7月、10月
 *
 * @author Peter Wang
 */
public class SeasonalityDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "季节性";
    private static final double WEIGHT = 0.04;

    /**
     * 黄金月度季节性评分（基于历史统计）
     * 正值=历史偏涨，负值=历史偏跌，0=震荡
     */
    private static final Map<Month, SeasonalProfile> SEASONAL_PROFILES = Map.ofEntries(
            Map.entry(Month.JANUARY, new SeasonalProfile(40, "1月通常为开年做多行情，历史偏涨")),
            Map.entry(Month.FEBRUARY, new SeasonalProfile(0, "2月震荡概率大，不宜期待单边走势，宽幅震荡为主")),
            Map.entry(Month.MARCH, new SeasonalProfile(-20, "3月通常小幅回调，获利了结压力")),
            Map.entry(Month.APRIL, new SeasonalProfile(10, "4月温和偏多")),
            Map.entry(Month.MAY, new SeasonalProfile(-10, "5月'Sell in May'效应，偏弱")),
            Map.entry(Month.JUNE, new SeasonalProfile(-25, "6月通常较弱，夏季淡季开始")),
            Map.entry(Month.JULY, new SeasonalProfile(5, "7月温和偏震荡")),
            Map.entry(Month.AUGUST, new SeasonalProfile(35, "8月避险需求增加，历史偏涨")),
            Map.entry(Month.SEPTEMBER, new SeasonalProfile(45, "9月通常是黄金最强月份之一")),
            Map.entry(Month.OCTOBER, new SeasonalProfile(5, "10月偏震荡")),
            Map.entry(Month.NOVEMBER, new SeasonalProfile(30, "11月年末配置需求增加，偏涨")),
            Map.entry(Month.DECEMBER, new SeasonalProfile(25, "12月年末效应，通常偏涨"))
    );

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        Month currentMonth = context.getDate().getMonth();
        SeasonalProfile profile = SEASONAL_PROFILES.getOrDefault(currentMonth,
                new SeasonalProfile(0, "无季节性数据"));

        double score = profile.score;
        String explanation = String.format("当前%d月，%s；",
                currentMonth.getValue(), profile.description);

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.SENTIMENT)
                .score(score)
                .weight(WEIGHT)
                .weightedContribution(score * WEIGHT)
                .explanation(explanation)
                .extremeTriggered(Math.abs(score) >= 40)
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

    /**
     * 季节性概况
     */
    private static class SeasonalProfile {
        final double score;
        final String description;

        SeasonalProfile(double score, String description) {
            this.score = score;
            this.description = description;
        }
    }
}
