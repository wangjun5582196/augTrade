package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 价格动量计算器
 * 计算短期和中期价格动量，用于判断价格变化速度
 * 
 * @author Peter Wang
 * @since 2026-03-09
 */
@Slf4j
@Component
public class MomentumCalculator {
    
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
            
            // 计算动量
            BigDecimal momentum2 = currentPrice.subtract(price2);
            BigDecimal momentum5 = currentPrice.subtract(price5);
            
            log.debug("[MomentumCalculator] 当前价:{}, 2根前:{}, 5根前:{}, Momentum2:{}, Momentum5:{}", 
                    currentPrice, price2, price5, momentum2, momentum5);
            
            return MomentumResult.builder()
                    .momentum2(momentum2)
                    .momentum5(momentum5)
                    .isStrongUp(momentum2.compareTo(BigDecimal.ZERO) > 0 && 
                               momentum5.compareTo(BigDecimal.ZERO) > 0)
                    .isStrongDown(momentum2.compareTo(BigDecimal.ZERO) < 0 && 
                                 momentum5.compareTo(BigDecimal.ZERO) < 0)
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
        private BigDecimal momentum2;      // 2根K线动量
        private BigDecimal momentum5;      // 5根K线动量
        private boolean isStrongUp;        // 是否强烈上涨
        private boolean isStrongDown;      // 是否强烈下跌
    }
}
