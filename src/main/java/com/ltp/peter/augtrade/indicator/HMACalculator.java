package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Hull Moving Average (HMA) 计算器
 * HMA = WMA(2*WMA(n/2) - WMA(n), sqrt(n))
 * 
 * @author Peter Wang
 * @since 2026-03-09
 */
@Slf4j
@Component
public class HMACalculator {
    
    private static final int DEFAULT_PERIOD = 20;
    
    /**
     * 计算HMA
     * 
     * @param klines K线数据（倒序，最新的在前）
     * @return HMA结果
     */
    public HMAResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_PERIOD);
    }
    
    /**
     * 计算HMA
     * 
     * @param klines K线数据
     * @param period HMA周期
     * @return HMA结果
     */
    public HMAResult calculate(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 10) {
            log.warn("[HMACalculator] K线数量不足，需要至少{}根", period + 10);
            return null;
        }
        
        try {
            int halfPeriod = period / 2;
            int sqrtPeriod = (int) Math.sqrt(period);
            
            // 计算 WMA(n/2)
            BigDecimal wmaHalf = calculateWMA(klines, halfPeriod, 0);
            
            // 计算 WMA(n)
            BigDecimal wmaFull = calculateWMA(klines, period, 0);
            
            // 计算 2*WMA(n/2) - WMA(n)
            BigDecimal rawHMA = wmaHalf.multiply(BigDecimal.valueOf(2)).subtract(wmaFull);
            
            // 构建用于最终WMA计算的临时序列
            // 需要 sqrtPeriod+1 个元素：前 sqrtPeriod 个用于计算当前HMA，
            // subList(1, sqrtPeriod+1) 的 sqrtPeriod 个用于计算 prevHMA（计算斜率用）
            List<BigDecimal> hmaSeriesList = new ArrayList<>();
            for (int i = 0; i < sqrtPeriod + 1 && i + period < klines.size(); i++) {
                BigDecimal wh = calculateWMA(klines, halfPeriod, i);
                BigDecimal wf = calculateWMA(klines, period, i);
                if (wh != null && wf != null) {
                    hmaSeriesList.add(wh.multiply(BigDecimal.valueOf(2)).subtract(wf));
                }
            }

            // 计算最终的HMA = WMA(rawHMA序列前sqrtPeriod个元素, sqrt(n))
            BigDecimal hma20 = calculateWMAFromList(
                    hmaSeriesList.subList(0, Math.min(sqrtPeriod, hmaSeriesList.size())), sqrtPeriod);
            
            if (hma20 == null) {
                return null;
            }
            
            // 计算HMA斜率（当前HMA - 前一个HMA）
            double slope = 0.0;
            String trend = "SIDEWAYS";
            
            // prevHMA 用 index 1 开始的 sqrtPeriod 个元素计算（需要 hmaSeriesList.size() >= sqrtPeriod+1）
            if (hmaSeriesList.size() >= sqrtPeriod + 1) {
                BigDecimal prevHMA = calculateWMAFromList(
                        hmaSeriesList.subList(1, sqrtPeriod + 1), sqrtPeriod);
                if (prevHMA != null) {
                    BigDecimal slopeBD = hma20.subtract(prevHMA);
                    slope = slopeBD.divide(prevHMA, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
                    
                    // 判断趋势方向
                    if (slope > 0.1) {
                        trend = "UP";
                    } else if (slope < -0.1) {
                        trend = "DOWN";
                    }
                }
            }
            
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            boolean priceAboveHMA = currentPrice.compareTo(hma20) > 0;
            
            log.debug("[HMACalculator] HMA20:{}, 斜率:{}, 趋势:{}, 当前价:{}, 价格在HMA上方:{}", 
                    hma20, String.format("%.4f%%", slope), trend, currentPrice, priceAboveHMA);
            
            return HMAResult.builder()
                    .hma20(hma20)
                    .slope(slope)
                    .trend(trend)
                    .priceAboveHMA(priceAboveHMA)
                    .build();
            
        } catch (Exception e) {
            log.error("[HMACalculator] 计算HMA失败", e);
            return null;
        }
    }
    
    /**
     * 计算加权移动平均 (WMA)
     */
    private BigDecimal calculateWMA(List<Kline> klines, int period, int offset) {
        if (klines.size() < offset + period) {
            return null;
        }
        
        BigDecimal sum = BigDecimal.ZERO;
        int weightSum = 0;
        
        for (int i = 0; i < period; i++) {
            int weight = period - i;
            sum = sum.add(klines.get(offset + i).getClosePrice().multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }
        
        return sum.divide(BigDecimal.valueOf(weightSum), 6, RoundingMode.HALF_UP);
    }
    
    /**
     * 从BigDecimal列表计算WMA
     */
    private BigDecimal calculateWMAFromList(List<BigDecimal> values, int period) {
        if (values == null || values.size() < period) {
            return null;
        }
        
        BigDecimal sum = BigDecimal.ZERO;
        int weightSum = 0;
        
        for (int i = 0; i < period; i++) {
            int weight = period - i;
            sum = sum.add(values.get(i).multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }
        
        return sum.divide(BigDecimal.valueOf(weightSum), 6, RoundingMode.HALF_UP);
    }
    
    /**
     * HMA计算结果
     */
    @Data
    @lombok.Builder
    public static class HMAResult {
        private BigDecimal hma20;          // HMA20值
        private double slope;              // HMA斜率（百分比）
        private String trend;              // 趋势方向：UP/DOWN/SIDEWAYS
        private boolean priceAboveHMA;     // 价格是否在HMA上方
    }
}
