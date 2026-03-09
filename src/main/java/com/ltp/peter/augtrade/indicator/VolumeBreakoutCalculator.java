package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 成交量突破计算器
 * 计算当前成交量相对于平均成交量的比率
 * 
 * @author Peter Wang
 * @since 2026-03-09
 */
@Slf4j
@Component
public class VolumeBreakoutCalculator {
    
    private static final int DEFAULT_PERIOD = 20;
    
    /**
     * 计算成交量突破
     * 
     * @param klines K线数据（倒序，最新的在前）
     * @return 成交量突破结果
     */
    public VolumeBreakoutResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_PERIOD);
    }
    
    /**
     * 计算成交量突破
     * 
     * @param klines K线数据
     * @param period 平均周期
     * @return 成交量突破结果
     */
    public VolumeBreakoutResult calculate(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            log.warn("[VolumeBreakoutCalculator] K线数量不足，需要至少{}根", period + 1);
            return null;
        }
        
        try {
            BigDecimal currentVolume = klines.get(0).getVolume();
            
            // 计算平均成交量
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 1; i <= period; i++) {
                sum = sum.add(klines.get(i).getVolume());
            }
            BigDecimal avgVolume = sum.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
            
            // 计算比率
            double volumeRatio = 1.0;
            if (avgVolume.compareTo(BigDecimal.ZERO) > 0) {
                volumeRatio = currentVolume.divide(avgVolume, 4, RoundingMode.HALF_UP).doubleValue();
            }
            
            boolean isBreakout = volumeRatio > 1.5;      // 放量突破
            boolean isShrinking = volumeRatio < 0.5;     // 缩量
            
            log.debug("[VolumeBreakoutCalculator] 当前成交量:{}, 平均成交量:{}, 比率:{}, 突破:{}, 缩量:{}", 
                    currentVolume, avgVolume, String.format("%.2f", volumeRatio), 
                    isBreakout, isShrinking);
            
            return VolumeBreakoutResult.builder()
                    .volumeRatio(volumeRatio)
                    .currentVolume(currentVolume)
                    .avgVolume(avgVolume)
                    .isBreakout(isBreakout)
                    .isShrinking(isShrinking)
                    .build();
            
        } catch (Exception e) {
            log.error("[VolumeBreakoutCalculator] 计算成交量突破失败", e);
            return null;
        }
    }
    
    /**
     * 成交量突破结果
     */
    @Data
    @lombok.Builder
    public static class VolumeBreakoutResult {
        private double volumeRatio;         // 成交量比率
        private BigDecimal currentVolume;   // 当前成交量
        private BigDecimal avgVolume;       // 平均成交量
        private boolean isBreakout;         // 是否放量突破 (>1.5)
        private boolean isShrinking;        // 是否缩量 (<0.5)
    }
}
