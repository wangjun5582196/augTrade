package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 价格动量计算器
 * 计算短期和中期价格动量，用于判断价格变化速度
 * 
 * 🔥 优化-20260310: 增加百分比阈值，避免噪音信号
 * 
 * @author Peter Wang
 * @since 2026-03-09
 */
@Slf4j
@Component
public class MomentumCalculator {
    
    // 🔥 P0修复-20260310: 增加动量阈值，避免微小波动触发"强劲"信号
    private static final double MOMENTUM_THRESHOLD_PERCENT = 0.002; // 0.2%
    
    /**
     * 计算价格动量
     * 
     * @param klines K线数据（倒序，最新的在前）
     * @return 动量结果
     */
    public MomentumResult calculate(List<Kline> klines) {
        if (klines == null || klines.size() < 6) {
            log.warn("[MomentumCalculator] K线数量不足，需要至少6根");
            return null;
        }
        
        try {
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            BigDecimal price2 = klines.get(2).getClosePrice();  // 2根K线前
            BigDecimal price5 = klines.get(5).getClosePrice();  // 5根K线前
            
            // 计算动量（绝对值）
            BigDecimal momentum2 = currentPrice.subtract(price2);
            BigDecimal momentum5 = currentPrice.subtract(price5);
            
            // 🔥 P0修复-20260310: 计算动量比率，使用百分比阈值过滤噪音
            double momentum2Ratio = momentum2.divide(price2, 4, RoundingMode.HALF_UP).doubleValue();
            double momentum5Ratio = momentum5.divide(price5, 4, RoundingMode.HALF_UP).doubleValue();
            
            // 🔥 优化判断逻辑：需要超过0.2%才算"强劲"
            boolean isStrongUp = momentum2Ratio > MOMENTUM_THRESHOLD_PERCENT && 
                                momentum5Ratio > MOMENTUM_THRESHOLD_PERCENT;
            boolean isStrongDown = momentum2Ratio < -MOMENTUM_THRESHOLD_PERCENT && 
                                  momentum5Ratio < -MOMENTUM_THRESHOLD_PERCENT;
            
            log.debug("[MomentumCalculator] 当前价:{}, 2根前:{}, 5根前:{}, M2:{} ({:.4f}%), M5:{} ({:.4f}%), 强上涨:{}, 强下跌:{}", 
                    currentPrice, price2, price5, momentum2, momentum2Ratio * 100, 
                    momentum5, momentum5Ratio * 100, isStrongUp, isStrongDown);
            
            return MomentumResult.builder()
                    .momentum2(momentum2)
                    .momentum5(momentum5)
                    .momentum2Ratio(momentum2Ratio)
                    .momentum5Ratio(momentum5Ratio)
                    .isStrongUp(isStrongUp)
                    .isStrongDown(isStrongDown)
                    .build();
            
        } catch (Exception e) {
            log.error("[MomentumCalculator] 计算动量失败", e);
            return null;
        }
    }
    
    /**
     * 动量计算结果
     */
    @Data
    @lombok.Builder
    public static class MomentumResult {
        private BigDecimal momentum2;      // 2根K线动量（绝对值）
        private BigDecimal momentum5;      // 5根K线动量（绝对值）
        private double momentum2Ratio;     // 2根K线动量比率
        private double momentum5Ratio;     // 5根K线动量比率
        private boolean isStrongUp;        // 是否强烈上涨（>0.2%）
        private boolean isStrongDown;      // 是否强烈下跌（<-0.2%）
    }
}
