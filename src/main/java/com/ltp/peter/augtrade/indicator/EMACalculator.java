package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EMA (Exponential Moving Average) 指数移动平均线计算器
 * 
 * EMA对近期价格赋予更高权重，比SMA更敏感
 * 用于识别趋势方向和支撑/阻力位
 * 
 * 计算公式：
 * EMA(t) = Price(t) * multiplier + EMA(t-1) * (1 - multiplier)
 * 其中 multiplier = 2 / (period + 1)
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class EMACalculator implements TechnicalIndicator<Double> {
    
    private static final int DEFAULT_PERIOD = 20;
    
    @Override
    public Double calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_PERIOD);
    }
    
    /**
     * 计算指定周期的EMA
     */
    public Double calculate(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            log.warn("K线数据不足，需要至少{}根K线", period);
            return null;
        }
        
        try {
            // K线数据从新到旧排列，需要反转
            List<Kline> reversedKlines = new ArrayList<>(klines);
            Collections.reverse(reversedKlines);
            
            // 计算EMA
            List<Double> emaValues = calculateEMAList(reversedKlines, period);
            
            // 返回最新的EMA值
            if (emaValues != null && !emaValues.isEmpty()) {
                return emaValues.get(emaValues.size() - 1);
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("计算EMA失败", e);
            return null;
        }
    }
    
    /**
     * 计算EMA序列
     */
    public List<Double> calculateEMAList(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            return null;
        }
        
        List<Double> emaValues = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        
        // 第一个EMA = SMA(period)
        double sma = 0.0;
        for (int i = 0; i < period; i++) {
            sma += klines.get(i).getClosePrice().doubleValue();
        }
        sma = sma / period;
        emaValues.add(sma);
        
        // 计算后续EMA
        for (int i = period; i < klines.size(); i++) {
            double price = klines.get(i).getClosePrice().doubleValue();
            double previousEMA = emaValues.get(emaValues.size() - 1);
            double ema = price * multiplier + previousEMA * (1 - multiplier);
            emaValues.add(ema);
        }
        
        return emaValues;
    }
    
    /**
     * 计算多个周期的EMA
     */
    public EMATrend calculateTrend(List<Kline> klines, int shortPeriod, int longPeriod) {
        if (klines == null || klines.size() < longPeriod) {
            log.warn("K线数据不足，需要至少{}根K线", longPeriod);
            return null;
        }
        
        try {
            Double emaShort = calculate(klines, shortPeriod);
            Double emaLong = calculate(klines, longPeriod);
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            
            if (emaShort == null || emaLong == null) {
                return null;
            }
            
            return EMATrend.builder()
                    .emaShort(emaShort)
                    .emaLong(emaLong)
                    .currentPrice(currentPrice.doubleValue())
                    .shortPeriod(shortPeriod)
                    .longPeriod(longPeriod)
                    .build();
            
        } catch (Exception e) {
            log.error("计算EMA趋势失败", e);
            return null;
        }
    }
    
    @Override
    public String getName() {
        return "EMA";
    }
    
    @Override
    public int getRequiredPeriods() {
        return DEFAULT_PERIOD;
    }
    
    @Override
    public String getDescription() {
        return String.format("指数移动平均线 (周期: %d)", DEFAULT_PERIOD);
    }
    
    /**
     * EMA趋势分析结果
     */
    @lombok.Data
    @lombok.Builder
    public static class EMATrend {
        private double emaShort;      // 短期EMA
        private double emaLong;       // 长期EMA
        private double currentPrice;  // 当前价格
        private int shortPeriod;      // 短期周期
        private int longPeriod;       // 长期周期
        
        /**
         * 判断是否上升趋势
         * 条件：价格 > EMA短期 > EMA长期
         */
        public boolean isUpTrend() {
            return currentPrice > emaShort && emaShort > emaLong;
        }
        
        /**
         * 判断是否下降趋势
         * 条件：价格 < EMA短期 < EMA长期
         */
        public boolean isDownTrend() {
            return currentPrice < emaShort && emaShort < emaLong;
        }
        
        /**
         * 判断是否震荡（无明确趋势）
         */
        public boolean isSideways() {
            return !isUpTrend() && !isDownTrend();
        }
        
        /**
         * 获取趋势强度 (0-100)
         */
        public int getTrendStrength() {
            double emaDiff = Math.abs(emaShort - emaLong);
            double priceEmaShortDiff = Math.abs(currentPrice - emaShort);
            
            // 趋势强度 = (EMA差距 + 价格与短期EMA差距) / 当前价格 * 1000
            double strength = (emaDiff + priceEmaShortDiff) / currentPrice * 1000;
            
            return (int) Math.min(strength * 10, 100);
        }
        
        /**
         * 获取趋势描述
         */
        public String getTrendDescription() {
            if (isUpTrend()) {
                return String.format("强势上涨 (价格:%.2f > EMA%d:%.2f > EMA%d:%.2f)", 
                        currentPrice, shortPeriod, emaShort, longPeriod, emaLong);
            } else if (isDownTrend()) {
                return String.format("强势下跌 (价格:%.2f < EMA%d:%.2f < EMA%d:%.2f)", 
                        currentPrice, shortPeriod, emaShort, longPeriod, emaLong);
            } else {
                return String.format("震荡整理 (价格:%.2f, EMA%d:%.2f, EMA%d:%.2f)", 
                        currentPrice, shortPeriod, emaShort, longPeriod, emaLong);
            }
        }
    }
}
