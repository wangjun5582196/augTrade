package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Supertrend 超级趋势指标
 * 
 * 日内短线最强趋势跟踪指标之一：
 * - 基于ATR动态计算趋势线
 * - 价格在Supertrend上方 = 上升趋势（做多）
 * - 价格在Supertrend下方 = 下降趋势（做空）
 * - 趋势翻转信号（从上方穿到下方或反之）是强烈的入场/出场点
 * 
 * 优势：
 * - 结合趋势方向 + 动态止损于一体
 * - Supertrend线本身就是动态止损位
 * - 减少假信号（比EMA交叉更可靠）
 * - 特别适合5分钟周期的短线交易
 * 
 * 参数：
 * - ATR周期：10（默认）
 * - 乘数：3.0（默认，越大越不敏感）
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class SupertrendCalculator implements TechnicalIndicator<SupertrendCalculator.SupertrendResult> {

    private static final int DEFAULT_ATR_PERIOD = 10;
    private static final double DEFAULT_MULTIPLIER = 3.0;

    @Override
    public SupertrendResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_ATR_PERIOD, DEFAULT_MULTIPLIER);
    }

    /**
     * 计算Supertrend
     * 
     * @param klines K线数据（最新在前）
     * @param atrPeriod ATR周期
     * @param multiplier ATR乘数
     * @return Supertrend结果
     */
    public SupertrendResult calculate(List<Kline> klines, int atrPeriod, double multiplier) {
        if (klines == null || klines.size() < atrPeriod + 2) {
            return null;
        }

        int size = Math.min(klines.size(), 100); // 最多计算100根K线

        // 1. 计算ATR序列（从旧到新）
        double[] atrValues = new double[size];
        double[] closes = new double[size];
        double[] highs = new double[size];
        double[] lows = new double[size];

        // 数据转换（klines是最新在前，需要反转为从旧到新）
        for (int i = 0; i < size; i++) {
            int idx = size - 1 - i; // 反转索引
            Kline k = klines.get(idx);
            closes[i] = k.getClosePrice().doubleValue();
            highs[i] = k.getHighPrice().doubleValue();
            lows[i] = k.getLowPrice().doubleValue();
        }

        // 2. 计算TR和ATR
        double[] tr = new double[size];
        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < size; i++) {
            double hl = highs[i] - lows[i];
            double hpc = Math.abs(highs[i] - closes[i - 1]);
            double lpc = Math.abs(lows[i] - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }

        // ATR使用RMA（Wilder's平滑）
        atrValues[atrPeriod - 1] = 0;
        for (int i = 0; i < atrPeriod; i++) {
            atrValues[atrPeriod - 1] += tr[i];
        }
        atrValues[atrPeriod - 1] /= atrPeriod;

        for (int i = atrPeriod; i < size; i++) {
            atrValues[i] = (atrValues[i - 1] * (atrPeriod - 1) + tr[i]) / atrPeriod;
        }

        // 3. 计算Supertrend
        double[] upperBand = new double[size];
        double[] lowerBand = new double[size];
        double[] supertrend = new double[size];
        boolean[] isUpTrend = new boolean[size];

        int startIdx = atrPeriod;

        for (int i = startIdx; i < size; i++) {
            double midpoint = (highs[i] + lows[i]) / 2.0;
            double basicUpper = midpoint + multiplier * atrValues[i];
            double basicLower = midpoint - multiplier * atrValues[i];

            // Upper Band: 如果当前基本上轨 < 前一上轨 且 前一收盘 > 前一上轨，保持前一上轨
            if (i == startIdx) {
                upperBand[i] = basicUpper;
                lowerBand[i] = basicLower;
                isUpTrend[i] = closes[i] > basicUpper;
            } else {
                upperBand[i] = (basicUpper < upperBand[i - 1] || closes[i - 1] > upperBand[i - 1])
                        ? basicUpper : upperBand[i - 1];
                lowerBand[i] = (basicLower > lowerBand[i - 1] || closes[i - 1] < lowerBand[i - 1])
                        ? basicLower : lowerBand[i - 1];

                // 趋势判断
                if (isUpTrend[i - 1]) {
                    // 前一根是上升趋势
                    isUpTrend[i] = closes[i] >= lowerBand[i]; // 价格未跌破下轨，维持上升
                } else {
                    // 前一根是下降趋势
                    isUpTrend[i] = closes[i] > upperBand[i]; // 价格突破上轨，转为上升
                }
            }

            supertrend[i] = isUpTrend[i] ? lowerBand[i] : upperBand[i];
        }

        // 4. 构建结果（使用最新的值）
        int latest = size - 1;
        int prev = size - 2;

        SupertrendResult result = new SupertrendResult();
        result.setSupertrendValue(supertrend[latest]);
        result.setUpTrend(isUpTrend[latest]);
        result.setCurrentPrice(closes[latest]);
        result.setUpperBand(upperBand[latest]);
        result.setLowerBand(lowerBand[latest]);
        result.setAtr(atrValues[latest]);

        // 检测趋势翻转
        if (prev >= startIdx) {
            result.setTrendChanged(isUpTrend[latest] != isUpTrend[prev]);
            result.setJustTurnedBullish(isUpTrend[latest] && !isUpTrend[prev]);
            result.setJustTurnedBearish(!isUpTrend[latest] && isUpTrend[prev]);
        }

        // 计算价格与Supertrend的距离百分比
        double distance = Math.abs(closes[latest] - supertrend[latest]);
        result.setDistancePercent(supertrend[latest] > 0 ? distance / supertrend[latest] * 100 : 0);

        log.debug("[Supertrend] 值={}, 趋势={}, 翻转={}, 价格={}, 距离={}%",
                String.format("%.2f", supertrend[latest]),
                isUpTrend[latest] ? "上升" : "下降",
                result.isTrendChanged() ? "是" : "否",
                String.format("%.2f", closes[latest]),
                String.format("%.3f", result.getDistancePercent()));

        return result;
    }

    @Override
    public String getName() {
        return "Supertrend";
    }

    @Override
    public int getRequiredPeriods() {
        return DEFAULT_ATR_PERIOD + 2;
    }

    @Override
    public String getDescription() {
        return "Supertrend超级趋势 - 基于ATR的趋势跟踪指标，兼具趋势判断与动态止损功能";
    }

    /**
     * Supertrend计算结果
     */
    @Data
    public static class SupertrendResult {
        private double supertrendValue;   // Supertrend线的值
        private boolean upTrend;          // 是否上升趋势
        private double currentPrice;      // 当前价格
        private double upperBand;         // 上轨
        private double lowerBand;         // 下轨
        private double atr;              // 当前ATR值
        private boolean trendChanged;     // 趋势是否刚翻转
        private boolean justTurnedBullish; // 是否刚转多
        private boolean justTurnedBearish; // 是否刚转空
        private double distancePercent;   // 价格与Supertrend的距离百分比

        /**
         * 是否下降趋势
         */
        public boolean isDownTrend() {
            return !upTrend;
        }

        /**
         * 获取趋势描述
         */
        public String getTrendDescription() {
            if (justTurnedBullish) return "🔥 刚翻转看涨";
            if (justTurnedBearish) return "🔥 刚翻转看跌";
            return upTrend ? "📈 上升趋势" : "📉 下降趋势";
        }

        /**
         * 获取动态止损位
         * Supertrend线本身就是理想的止损位
         */
        public double getDynamicStopLoss() {
            return supertrendValue;
        }
    }
}
