package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ADX (Average Directional Index) 平均趋势指数计算器
 * 
 * ADX用于判断趋势强度：
 * - ADX > 25: 强趋势市场，适合趋势跟踪策略
 * - ADX < 20: 震荡市场，适合区间交易策略
 * - ADX 20-25: 过渡区域
 * 
 * 🔥 P0修复-20260115: 增加+DI/-DI计算，判断趋势方向
 * - +DI > -DI: 上升趋势
 * - +DI < -DI: 下降趋势
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class ADXCalculator implements TechnicalIndicator<Double> {
    
    /**
     * 🔥 P0修复: ADX结果类，包含ADX值、+DI、-DI和趋势方向
     */
    @Data
    @AllArgsConstructor
    public static class ADXResult {
        private double adx;        // ADX值
        private double plusDI;     // +DI值
        private double minusDI;    // -DI值
        private TrendDirection trendDirection;  // 趋势方向
        
        /**
         * 判断是否为上升趋势
         */
        public boolean isUpTrend() {
            return trendDirection == TrendDirection.UP;
        }
        
        /**
         * 判断是否为下降趋势
         */
        public boolean isDownTrend() {
            return trendDirection == TrendDirection.DOWN;
        }
        
        /**
         * 判断是否为强上升趋势
         */
        public boolean isStrongUpTrend() {
            return adx > 25 && trendDirection == TrendDirection.UP;
        }
        
        /**
         * 判断是否为强下降趋势
         */
        public boolean isStrongDownTrend() {
            return adx > 25 && trendDirection == TrendDirection.DOWN;
        }
    }
    
    /**
     * 趋势方向枚举
     */
    public enum TrendDirection {
        UP,      // 上升趋势 (+DI > -DI)
        DOWN,    // 下降趋势 (+DI < -DI)
        NEUTRAL  // 中性 (+DI ≈ -DI)
    }
    
    private static final int DEFAULT_PERIOD = 14;
    private final int period;
    
    public ADXCalculator() {
        this.period = DEFAULT_PERIOD;
    }
    
    public ADXCalculator(int period) {
        this.period = period;
    }
    
    @Override
    public Double calculate(List<Kline> klines) {
        ADXResult result = calculateWithDirection(klines);
        return result != null ? result.getAdx() : null;
    }
    
    /**
     * 🔥 P0修复: 计算ADX并返回完整结果(包含+DI/-DI和趋势方向)
     */
    public ADXResult calculateWithDirection(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            log.warn("K线数据不足，需要至少 {} 根K线，当前只有 {} 根", getRequiredPeriods(), klines.size());
            return null;
        }
        
        try {
            // 计算+DM和-DM (Directional Movement)
            List<Double> plusDM = new ArrayList<>();
            List<Double> minusDM = new ArrayList<>();
            List<Double> trueRanges = new ArrayList<>();
            
            for (int i = 1; i < klines.size(); i++) {
                Kline current = klines.get(i);
                Kline previous = klines.get(i - 1);
                
                double highDiff = current.getHighPrice().doubleValue() - previous.getHighPrice().doubleValue();
                double lowDiff = previous.getLowPrice().doubleValue() - current.getLowPrice().doubleValue();
                
                // 计算+DM和-DM
                double plusDMValue = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
                double minusDMValue = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
                
                plusDM.add(plusDMValue);
                minusDM.add(minusDMValue);
                
                // 计算真实波幅 (True Range)
                double tr1 = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
                double tr2 = Math.abs(current.getHighPrice().doubleValue() - previous.getClosePrice().doubleValue());
                double tr3 = Math.abs(current.getLowPrice().doubleValue() - previous.getClosePrice().doubleValue());
                
                trueRanges.add(Math.max(tr1, Math.max(tr2, tr3)));
            }
            
            // 计算平滑的+DM、-DM和TR
            double smoothedPlusDM = calculateSmoothed(plusDM, period);
            double smoothedMinusDM = calculateSmoothed(minusDM, period);
            double smoothedTR = calculateSmoothed(trueRanges, period);
            
            if (smoothedTR == 0) {
                log.warn("平滑后的真实波幅为0，无法计算ADX");
                return new ADXResult(0.0, 0.0, 0.0, TrendDirection.NEUTRAL);
            }
            
            // 计算+DI和-DI (Directional Indicators)
            double plusDI = (smoothedPlusDM / smoothedTR) * 100;
            double minusDI = (smoothedMinusDM / smoothedTR) * 100;
            
            // 计算DX (Directional Index)
            double diDiff = Math.abs(plusDI - minusDI);
            double diSum = plusDI + minusDI;
            
            if (diSum == 0) {
                return new ADXResult(0.0, plusDI, minusDI, TrendDirection.NEUTRAL);
            }
            
            double dx = (diDiff / diSum) * 100;
            
            // 🔥 P0修复: 判断趋势方向
            TrendDirection direction;
            double diDiffPercent = Math.abs(plusDI - minusDI) / Math.max(plusDI, minusDI) * 100;
            
            if (plusDI > minusDI && diDiffPercent > 10) {
                // +DI明显大于-DI (差距>10%)，上升趋势
                direction = TrendDirection.UP;
            } else if (minusDI > plusDI && diDiffPercent > 10) {
                // -DI明显大于+DI (差距>10%)，下降趋势
                direction = TrendDirection.DOWN;
            } else {
                // 两者接近，中性
                direction = TrendDirection.NEUTRAL;
            }
            
            log.debug("[ADX] ADX={}, +DI={}, -DI={}, 趋势={}", 
                     String.format("%.2f", dx),
                     String.format("%.2f", plusDI),
                     String.format("%.2f", minusDI),
                     direction);
            
            // ADX是DX的移动平均
            // 简化版本：直接返回DX作为ADX的近似值
            // 完整版本需要对DX序列进行移动平均
            return new ADXResult(dx, plusDI, minusDI, direction);
            
        } catch (Exception e) {
            log.error("计算ADX时发生错误", e);
            return null;
        }
    }
    
    /**
     * 计算平滑值（Wilder's Smoothing）
     */
    private double calculateSmoothed(List<Double> values, int period) {
        if (values.size() < period) {
            return 0.0;
        }
        
        // 第一个平滑值是简单平均
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += values.get(i);
        }
        
        double smoothed = sum / period;
        
        // 后续使用Wilder's Smoothing: 
        // Smoothed = (Previous Smoothed * (period - 1) + Current Value) / period
        for (int i = period; i < values.size(); i++) {
            smoothed = ((smoothed * (period - 1)) + values.get(i)) / period;
        }
        
        return smoothed;
    }
    
    @Override
    public String getName() {
        return "ADX";
    }
    
    @Override
    public int getRequiredPeriods() {
        // ADX需要至少2倍周期的数据才能准确计算
        return period * 2;
    }
    
    @Override
    public String getDescription() {
        return "ADX (" + period + "期) - 平均趋势指数，衡量趋势强度";
    }
    
    /**
     * 判断是否为强趋势市场
     */
    public boolean isStrongTrend(Double adx) {
        return adx != null && adx > 25;
    }
    
    /**
     * 判断是否为震荡市场
     */
    public boolean isRangeMarket(Double adx) {
        return adx != null && adx < 20;
    }
}
