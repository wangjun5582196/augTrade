package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 摆动高低点计算器
 * 识别价格的摆动高点和低点，用于判断支撑阻力位
 * 
 * @author Peter Wang
 * @since 2026-03-09
 */
@Slf4j
@Component
public class SwingPointCalculator {
    
    private static final int DEFAULT_LOOKBACK = 5;
    
    /**
     * 计算摆动点
     * 
     * @param klines K线数据（倒序，最新的在前）
     * @return 摆动点结果
     */
    public SwingPointResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_LOOKBACK);
    }
    
    /**
     * 计算摆动点
     * 
     * @param klines K线数据
     * @param lookback 回看周期
     * @return 摆动点结果
     */
    public SwingPointResult calculate(List<Kline> klines, int lookback) {
        if (klines == null || klines.size() < lookback * 2 + 1) {
            log.warn("[SwingPointCalculator] K线数量不足，需要至少{}根", lookback * 2 + 1);
            return null;
        }
        
        try {
            SwingPoint lastSwingHigh = findLastSwingHigh(klines, lookback);
            SwingPoint lastSwingLow = findLastSwingLow(klines, lookback);
            
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            
            // 判断价格是否突破摆动高点
            boolean isBreakingHigh = lastSwingHigh != null && 
                                    currentPrice.compareTo(lastSwingHigh.getPrice()) > 0;
            
            // 判断价格是否在摆动低点附近（支撑有效）
            boolean isNearSupport = false;
            if (lastSwingLow != null) {
                BigDecimal distance = currentPrice.subtract(lastSwingLow.getPrice());
                // 价格在摆动低点上方0.5%以内
                isNearSupport = distance.doubleValue() > 0 && 
                               distance.doubleValue() < lastSwingLow.getPrice().doubleValue() * 0.005;
            }
            
            log.debug("[SwingPointCalculator] 当前价:{}, 摆动高点:{}, 摆动低点:{}, 突破高点:{}, 接近支撑:{}", 
                    currentPrice, 
                    lastSwingHigh != null ? lastSwingHigh.getPrice() : "null",
                    lastSwingLow != null ? lastSwingLow.getPrice() : "null",
                    isBreakingHigh, isNearSupport);
            
            return SwingPointResult.builder()
                    .lastSwingHigh(lastSwingHigh)
                    .lastSwingLow(lastSwingLow)
                    .isBreakingHigh(isBreakingHigh)
                    .isNearSupport(isNearSupport)
                    .build();
            
        } catch (Exception e) {
            log.error("[SwingPointCalculator] 计算摆动点失败", e);
            return null;
        }
    }
    
    /**
     * 查找最近的摆动高点
     */
    private SwingPoint findLastSwingHigh(List<Kline> klines, int lookback) {
        for (int i = lookback; i < Math.min(klines.size() - lookback, 50); i++) {
            Kline current = klines.get(i);
            boolean isSwingHigh = true;
            
            // 检查左右两边的K线
            for (int j = 1; j <= lookback; j++) {
                if (i - j < 0 || i + j >= klines.size()) {
                    isSwingHigh = false;
                    break;
                }
                if (current.getHighPrice().compareTo(klines.get(i - j).getHighPrice()) <= 0 ||
                    current.getHighPrice().compareTo(klines.get(i + j).getHighPrice()) <= 0) {
                    isSwingHigh = false;
                    break;
                }
            }
            
            if (isSwingHigh) {
                return SwingPoint.builder()
                        .price(current.getHighPrice())
                        .index(i)
                        .time(current.getTimestamp())
                        .build();
            }
        }
        return null;
    }
    
    /**
     * 查找最近的摆动低点
     */
    private SwingPoint findLastSwingLow(List<Kline> klines, int lookback) {
        for (int i = lookback; i < Math.min(klines.size() - lookback, 50); i++) {
            Kline current = klines.get(i);
            boolean isSwingLow = true;
            
            // 检查左右两边的K线
            for (int j = 1; j <= lookback; j++) {
                if (i - j < 0 || i + j >= klines.size()) {
                    isSwingLow = false;
                    break;
                }
                if (current.getLowPrice().compareTo(klines.get(i - j).getLowPrice()) >= 0 ||
                    current.getLowPrice().compareTo(klines.get(i + j).getLowPrice()) >= 0) {
                    isSwingLow = false;
                    break;
                }
            }
            
            if (isSwingLow) {
                return SwingPoint.builder()
                        .price(current.getLowPrice())
                        .index(i)
                        .time(current.getTimestamp())
                        .build();
            }
        }
        return null;
    }
    
    /**
     * 摆动点结果
     */
    @Data
    @lombok.Builder
    public static class SwingPointResult {
        private SwingPoint lastSwingHigh;    // 最近摆动高点
        private SwingPoint lastSwingLow;     // 最近摆动低点
        private boolean isBreakingHigh;      // 是否突破摆动高点
        private boolean isNearSupport;       // 是否接近支撑位
    }
    
    /**
     * 摆动点
     */
    @Data
    @lombok.Builder
    public static class SwingPoint {
        private BigDecimal price;            // 价格
        private int index;                   // K线索引
        private LocalDateTime time;          // 时间
    }
}
