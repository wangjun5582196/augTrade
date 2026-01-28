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
     * 🔥 P0修复-20260128: 计算ADX并返回完整结果(包含+DI/-DI和趋势方向)
     * Bug修复: 之前只返回DX值，现在正确计算ADX（DX的移动平均）
     */
    public ADXResult calculateWithDirection(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            log.warn("K线数据不足，需要至少 {} 根K线，当前只有 {} 根", getRequiredPeriods(), klines.size());
            return null;
        }
        
        try {
            // 🔥 调试：打印K线时间顺序，确认是否需要排序
            if (klines.size() >= 3) {
                log.debug("[ADX-DEBUG] K线顺序检查 - 第0根: {}, 第1根: {}, 第2根: {}", 
                         klines.get(0).getTimestamp(),
                         klines.get(1).getTimestamp(),
                         klines.get(2).getTimestamp());
            }
            
            // 1. 计算每根K线的+DM、-DM、TR
            List<Double> plusDMList = new ArrayList<>();
            List<Double> minusDMList = new ArrayList<>();
            List<Double> trList = new ArrayList<>();
            
            for (int i = 1; i < klines.size(); i++) {
                Kline current = klines.get(i);
                Kline previous = klines.get(i - 1);
                
                double highDiff = current.getHighPrice().doubleValue() - previous.getHighPrice().doubleValue();
                double lowDiff = previous.getLowPrice().doubleValue() - current.getLowPrice().doubleValue();
                
                // 计算+DM和-DM
                double plusDMValue = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
                double minusDMValue = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
                
                plusDMList.add(plusDMValue);
                minusDMList.add(minusDMValue);
                
                // 计算真实波幅 (True Range)
                double tr1 = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
                double tr2 = Math.abs(current.getHighPrice().doubleValue() - previous.getClosePrice().doubleValue());
                double tr3 = Math.abs(current.getLowPrice().doubleValue() - previous.getClosePrice().doubleValue());
                
                trList.add(Math.max(tr1, Math.max(tr2, tr3)));
            }
            
            // 2. 计算DX序列（每根K线一个DX值）
            List<Double> dxList = new ArrayList<>();
            
            for (int i = period - 1; i < plusDMList.size(); i++) {
                // 计算这个窗口的平滑值
                double smoothedPlusDM = calculateSmoothedForWindow(plusDMList, i - period + 1, i + 1);
                double smoothedMinusDM = calculateSmoothedForWindow(minusDMList, i - period + 1, i + 1);
                double smoothedTR = calculateSmoothedForWindow(trList, i - period + 1, i + 1);
                
                if (smoothedTR == 0) {
                    dxList.add(0.0);
                    continue;
                }
                
                double plusDI = (smoothedPlusDM / smoothedTR) * 100;
                double minusDI = (smoothedMinusDM / smoothedTR) * 100;
                
                double diDiff = Math.abs(plusDI - minusDI);
                double diSum = plusDI + minusDI;
                
                if (diSum == 0) {
                    dxList.add(0.0);
                } else {
                    double dx = (diDiff / diSum) * 100;
                    dxList.add(dx);
                }
            }
            
            // 3. 🔥 关键修复：计算ADX（DX的移动平均）
            if (dxList.size() < period) {
                log.warn("DX序列数据不足，需要至少 {} 个DX值", period);
                return null;
            }
            
            double adx = calculateSmoothedForWindow(dxList, dxList.size() - period, dxList.size());
            
            // 4. 计算最新的+DI和-DI
            double smoothedPlusDM = calculateSmoothed(plusDMList, period);
            double smoothedMinusDM = calculateSmoothed(minusDMList, period);
            double smoothedTR = calculateSmoothed(trList, period);
            
            // 🔥 调试：打印最近几个+DM和-DM值
            if (plusDMList.size() >= 5) {
                int size = plusDMList.size();
                log.debug("[ADX-DEBUG] 最近5个+DM: [{}, {}, {}, {}, {}]",
                         String.format("%.2f", plusDMList.get(size-5)),
                         String.format("%.2f", plusDMList.get(size-4)),
                         String.format("%.2f", plusDMList.get(size-3)),
                         String.format("%.2f", plusDMList.get(size-2)),
                         String.format("%.2f", plusDMList.get(size-1)));
                log.debug("[ADX-DEBUG] 最近5个-DM: [{}, {}, {}, {}, {}]",
                         String.format("%.2f", minusDMList.get(size-5)),
                         String.format("%.2f", minusDMList.get(size-4)),
                         String.format("%.2f", minusDMList.get(size-3)),
                         String.format("%.2f", minusDMList.get(size-2)),
                         String.format("%.2f", minusDMList.get(size-1)));
            }
            
            if (smoothedTR == 0) {
                log.warn("平滑后的真实波幅为0，无法计算+DI/-DI");
                return new ADXResult(adx, 0.0, 0.0, TrendDirection.NEUTRAL);
            }
            
            double plusDI = (smoothedPlusDM / smoothedTR) * 100;
            double minusDI = (smoothedMinusDM / smoothedTR) * 100;
            
            // 🔥 调试：打印平滑后的值
            log.debug("[ADX-DEBUG] 平滑后 - +DM: {}, -DM: {}, TR: {}, +DI: {}, -DI: {}",
                     String.format("%.2f", smoothedPlusDM),
                     String.format("%.2f", smoothedMinusDM),
                     String.format("%.2f", smoothedTR),
                     String.format("%.2f", plusDI),
                     String.format("%.2f", minusDI));
            
            // 5. 判断趋势方向
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
                     String.format("%.2f", adx),
                     String.format("%.2f", plusDI),
                     String.format("%.2f", minusDI),
                     direction);
            
            return new ADXResult(adx, plusDI, minusDI, direction);
            
        } catch (Exception e) {
            log.error("计算ADX时发生错误", e);
            return null;
        }
    }
    
    /**
     * 🔥 新增方法：计算指定窗口的平滑值
     */
    private double calculateSmoothedForWindow(List<Double> values, int start, int end) {
        if (end - start < period) {
            return 0.0;
        }
        
        // 第一个平滑值是简单平均
        double sum = 0.0;
        for (int i = start; i < start + period && i < end; i++) {
            sum += values.get(i);
        }
        
        double smoothed = sum / period;
        
        // 后续使用Wilder's Smoothing
        for (int i = start + period; i < end; i++) {
            smoothed = ((smoothed * (period - 1)) + values.get(i)) / period;
        }
        
        return smoothed;
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
