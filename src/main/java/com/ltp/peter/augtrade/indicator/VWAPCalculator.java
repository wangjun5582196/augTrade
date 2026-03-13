package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * VWAP - 成交量加权平均价 (Volume Weighted Average Price)
 * 
 * 日内短线交易的"圣杯"指标：
 * - 机构交易员最常用的日内基准价格
 * - 价格 > VWAP = 多头占优（日内看涨）
 * - 价格 < VWAP = 空头占优（日内看跌）
 * - VWAP本身作为日内支撑/阻力位
 * 
 * 增强版本包含：
 * - VWAP ± 1σ 和 ± 2σ 带（类似布林带，但基于成交量加权）
 * - 价格偏离VWAP百分比（判断超买超卖）
 * - 日内重置逻辑
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class VWAPCalculator implements TechnicalIndicator<VWAPCalculator.VWAPResult> {

    private static final int DEFAULT_PERIOD = 60; // 默认60根K线（5分钟周期=5小时）

    @Override
    public VWAPResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_PERIOD);
    }

    /**
     * 计算VWAP及其标准差带
     * 
     * @param klines K线数据（最新在前）
     * @param period 计算周期
     * @return VWAP结果
     */
    public VWAPResult calculate(List<Kline> klines, int period) {
        if (klines == null || klines.size() < 5) {
            return null;
        }

        // 按交易日重置：VWAP只使用当日K线，避免跨日计算失去参考意义
        // 以最新K线的日期为基准，过滤出当日数据
        List<Kline> workingKlines = klines;
        if (klines.get(0).getTimestamp() != null) {
            LocalDate latestDate = klines.get(0).getTimestamp().toLocalDate();
            List<Kline> todayKlines = klines.stream()
                    .filter(k -> k.getTimestamp() != null && k.getTimestamp().toLocalDate().equals(latestDate))
                    .collect(Collectors.toList());
            if (todayKlines.size() >= 5) {
                workingKlines = todayKlines;
                log.debug("[VWAP] 使用当日{}根K线计算（日期：{}）", workingKlines.size(), latestDate);
            } else {
                log.debug("[VWAP] 当日K线不足5根（{}根），降级使用最近{}根K线", todayKlines.size(), Math.min(period, klines.size()));
            }
        }

        int actualPeriod = Math.min(period, workingKlines.size());

        BigDecimal totalPV = BigDecimal.ZERO;   // Σ(TP * Volume)
        BigDecimal totalVolume = BigDecimal.ZERO; // Σ(Volume)
        BigDecimal totalPV2 = BigDecimal.ZERO;  // Σ(TP² * Volume) 用于计算标准差

        for (int i = 0; i < actualPeriod; i++) {
            Kline kline = workingKlines.get(i);
            // 典型价格 = (High + Low + Close) / 3
            BigDecimal tp = kline.getHighPrice()
                    .add(kline.getLowPrice())
                    .add(kline.getClosePrice())
                    .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

            BigDecimal vol = kline.getVolume();
            if (vol.compareTo(BigDecimal.ZERO) <= 0) {
                vol = BigDecimal.ONE; // 避免零成交量
            }

            totalPV = totalPV.add(tp.multiply(vol));
            totalPV2 = totalPV2.add(tp.multiply(tp).multiply(vol));
            totalVolume = totalVolume.add(vol);
        }

        if (totalVolume.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // VWAP = Σ(TP * V) / Σ(V)
        double vwap = totalPV.divide(totalVolume, 6, RoundingMode.HALF_UP).doubleValue();

        // VWAP标准差 = sqrt(Σ(TP² * V) / Σ(V) - VWAP²)
        double variance = totalPV2.divide(totalVolume, 6, RoundingMode.HALF_UP).doubleValue() - vwap * vwap;
        double stdDev = variance > 0 ? Math.sqrt(variance) : 0;

        double currentPrice = klines.get(0).getClosePrice().doubleValue();
        double deviation = vwap > 0 ? (currentPrice - vwap) / vwap * 100 : 0;

        VWAPResult result = new VWAPResult();
        result.setVwap(vwap);
        result.setUpperBand1(vwap + stdDev);       // VWAP + 1σ
        result.setLowerBand1(vwap - stdDev);       // VWAP - 1σ
        result.setUpperBand2(vwap + 2 * stdDev);   // VWAP + 2σ
        result.setLowerBand2(vwap - 2 * stdDev);   // VWAP - 2σ
        result.setStdDev(stdDev);
        result.setDeviationPercent(deviation);
        result.setCurrentPrice(currentPrice);

        log.debug("[VWAP] VWAP={}, 价格={}, 偏离={}%, σ={}", 
                String.format("%.2f", vwap), 
                String.format("%.2f", currentPrice),
                String.format("%.3f", deviation), 
                String.format("%.2f", stdDev));

        return result;
    }

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public int getRequiredPeriods() {
        return 10;
    }

    @Override
    public String getDescription() {
        return "成交量加权平均价(VWAP) - 日内短线交易的核心基准价格指标";
    }

    /**
     * VWAP计算结果
     */
    @Data
    public static class VWAPResult {
        private double vwap;            // VWAP值
        private double upperBand1;      // VWAP + 1σ
        private double lowerBand1;      // VWAP - 1σ
        private double upperBand2;      // VWAP + 2σ
        private double lowerBand2;      // VWAP - 2σ
        private double stdDev;          // 标准差
        private double deviationPercent; // 价格偏离VWAP百分比
        private double currentPrice;    // 当前价格

        /**
         * 价格是否在VWAP上方（多头区域）
         */
        public boolean isPriceAboveVWAP() {
            return currentPrice > vwap;
        }

        /**
         * 价格是否在VWAP下方（空头区域）
         */
        public boolean isPriceBelowVWAP() {
            return currentPrice < vwap;
        }

        /**
         * 价格是否触及VWAP（±0.05%以内）
         */
        public boolean isPriceTouchingVWAP() {
            return Math.abs(deviationPercent) < 0.05;
        }

        /**
         * 价格是否在VWAP+1σ上方（过度偏离，可能回调）
         */
        public boolean isAboveUpperBand1() {
            return currentPrice > upperBand1;
        }

        /**
         * 价格是否在VWAP-1σ下方（过度偏离，可能反弹）
         */
        public boolean isBelowLowerBand1() {
            return currentPrice < lowerBand1;
        }

        /**
         * 价格是否在VWAP+2σ上方（极度偏离，高概率回调）
         */
        public boolean isAboveUpperBand2() {
            return currentPrice > upperBand2;
        }

        /**
         * 价格是否在VWAP-2σ下方（极度偏离，高概率反弹）
         */
        public boolean isBelowLowerBand2() {
            return currentPrice < lowerBand2;
        }

        /**
         * 获取VWAP位置描述
         */
        public String getPositionDescription() {
            if (isAboveUpperBand2()) return "极度超买(>+2σ)";
            if (isAboveUpperBand1()) return "偏多(>+1σ)";
            if (isPriceAboveVWAP()) return "VWAP上方(多头)";
            if (isPriceTouchingVWAP()) return "VWAP附近(中性)";
            if (isBelowLowerBand1()) return "偏空(<-1σ)";
            if (isBelowLowerBand2()) return "极度超卖(<-2σ)";
            return "VWAP下方(空头)";
        }
    }
}
