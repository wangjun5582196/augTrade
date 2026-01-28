package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ATR (Average True Range) 计算器
 * 
 * ATR用于衡量市场波动性，是动态止损止盈的重要指标
 * 
 * 计算公式：
 * 1. TR (True Range) = max(High - Low, |High - PreClose|, |Low - PreClose|)
 * 2. ATR = TR的N期移动平均（通常N=14）
 * 
 * 应用：
 * - 动态止损：StopLoss = ATR * 1.5
 * - 动态止盈：TakeProfit = ATR * 3.0
 * - 波动率过滤：ATR > 阈值时暂停交易
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class ATRCalculator implements TechnicalIndicator<Double> {
    
    private static final int DEFAULT_PERIOD = 14;
    
    @Override
    public Double calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_PERIOD);
    }
    
    /**
     * 计算ATR
     * 
     * @param klines K线数据（按时间降序排列，最新的在前）
     * @param period ATR周期（默认14）
     * @return ATR值，如果数据不足返回null
     */
    public Double calculate(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            log.warn("ATR计算失败：K线数据不足（需要{}根，实际{}根）", period + 1, 
                    klines == null ? 0 : klines.size());
            return null;
        }
        
        try {
            // 反转K线顺序，使最旧的在前（便于计算）
            List<Kline> reversedKlines = new ArrayList<>(klines);
            java.util.Collections.reverse(reversedKlines);
            
            // 计算TR (True Range) 序列
            List<BigDecimal> trList = new ArrayList<>();
            
            for (int i = 1; i < reversedKlines.size(); i++) {
                Kline current = reversedKlines.get(i);
                Kline previous = reversedKlines.get(i - 1);
                
                BigDecimal tr = calculateTrueRange(current, previous);
                trList.add(tr);
            }
            
            // 🔥 P1修复-20260128: 使用Wilder's Smoothing计算ATR
            // Bug修复：之前使用简单移动平均，导致ATR波动过大
            if (trList.size() < period) {
                log.warn("ATR计算失败：TR数据不足（需要{}个，实际{}个）", period, trList.size());
                return null;
            }
            
            // 第一个ATR：简单平均
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < period; i++) {
                sum = sum.add(trList.get(i));
            }
            BigDecimal atr = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            
            // 后续ATR：Wilder's Smoothing
            // ATR = (prevATR * (period-1) + currentTR) / period
            for (int i = period; i < trList.size(); i++) {
                BigDecimal prevATR = atr;
                BigDecimal currentTR = trList.get(i);
                atr = prevATR.multiply(BigDecimal.valueOf(period - 1))
                        .add(currentTR)
                        .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            }
            
            log.debug("ATR计算完成: {} (周期: {})", atr.doubleValue(), period);
            return atr.doubleValue();
            
        } catch (Exception e) {
            log.error("ATR计算异常", e);
            return null;
        }
    }
    
    /**
     * 计算真实波幅 (True Range)
     * 
     * TR = max(
     *   当前最高价 - 当前最低价,
     *   |当前最高价 - 前收盘价|,
     *   |当前最低价 - 前收盘价|
     * )
     * 
     * @param current 当前K线
     * @param previous 前一根K线
     * @return TR值
     */
    private BigDecimal calculateTrueRange(Kline current, Kline previous) {
        BigDecimal high = current.getHighPrice();
        BigDecimal low = current.getLowPrice();
        BigDecimal prevClose = previous.getClosePrice();
        
        // 方法1：当前最高价 - 当前最低价
        BigDecimal range1 = high.subtract(low);
        
        // 方法2：|当前最高价 - 前收盘价|
        BigDecimal range2 = high.subtract(prevClose).abs();
        
        // 方法3：|当前最低价 - 前收盘价|
        BigDecimal range3 = low.subtract(prevClose).abs();
        
        // 取最大值
        BigDecimal tr = range1;
        if (range2.compareTo(tr) > 0) {
            tr = range2;
        }
        if (range3.compareTo(tr) > 0) {
            tr = range3;
        }
        
        return tr;
    }
    
    /**
     * 计算基于ATR的动态止损价格
     * 
     * @param klines K线数据
     * @param currentPrice 当前价格
     * @param side 交易方向（"LONG" 或 "SHORT"）
     * @param atrMultiplier ATR乘数（建议1.5-2.0）
     * @return 止损价格
     */
    public BigDecimal calculateDynamicStopLoss(List<Kline> klines, 
                                               BigDecimal currentPrice, 
                                               String side, 
                                               double atrMultiplier) {
        Double atr = calculate(klines);
        if (atr == null) {
            log.warn("ATR计算失败，无法计算动态止损");
            return null;
        }
        
        BigDecimal atrValue = BigDecimal.valueOf(atr * atrMultiplier);
        
        if ("LONG".equals(side)) {
            // 做多：止损在入场价下方
            return currentPrice.subtract(atrValue);
        } else {
            // 做空：止损在入场价上方
            return currentPrice.add(atrValue);
        }
    }
    
    /**
     * 计算基于ATR的动态止盈价格
     * 
     * @param klines K线数据
     * @param currentPrice 当前价格
     * @param side 交易方向（"LONG" 或 "SHORT"）
     * @param atrMultiplier ATR乘数（建议2.5-3.0）
     * @return 止盈价格
     */
    public BigDecimal calculateDynamicTakeProfit(List<Kline> klines, 
                                                 BigDecimal currentPrice, 
                                                 String side, 
                                                 double atrMultiplier) {
        Double atr = calculate(klines);
        if (atr == null) {
            log.warn("ATR计算失败，无法计算动态止盈");
            return null;
        }
        
        BigDecimal atrValue = BigDecimal.valueOf(atr * atrMultiplier);
        
        if ("LONG".equals(side)) {
            // 做多：止盈在入场价上方
            return currentPrice.add(atrValue);
        } else {
            // 做空：止盈在入场价下方
            return currentPrice.subtract(atrValue);
        }
    }
    
    /**
     * 判断当前市场波动是否适合交易
     * 
     * @param klines K线数据
     * @param minATR 最小ATR阈值（波动太小不交易）
     * @param maxATR 最大ATR阈值（波动太大不交易）
     * @return true表示适合交易，false表示不适合
     */
    public boolean isVolatilitySuitableForTrading(List<Kline> klines, 
                                                  double minATR, 
                                                  double maxATR) {
        Double atr = calculate(klines);
        if (atr == null) {
            return false;
        }
        
        if (atr < minATR) {
            log.warn("⚠️ 市场波动过低（ATR: {} < {}），不适合交易", atr, minATR);
            return false;
        }
        
        if (atr > maxATR) {
            log.warn("⚠️ 市场波动过高（ATR: {} > {}），风险过大", atr, maxATR);
            return false;
        }
        
        log.debug("✅ 市场波动适中（ATR: {}），适合交易", atr);
        return true;
    }
    
    @Override
    public String getName() {
        return "ATR";
    }
    
    @Override
    public int getRequiredPeriods() {
        return DEFAULT_PERIOD + 1; // ATR需要14+1=15根K线
    }
    
    @Override
    public String getDescription() {
        return "平均真实波幅（Average True Range）- 用于衡量市场波动性并计算动态止损止盈";
    }
}
