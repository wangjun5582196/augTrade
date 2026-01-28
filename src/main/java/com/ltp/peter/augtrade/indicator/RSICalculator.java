package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * RSI（相对强弱指标）计算器
 * 
 * RSI值范围：0-100
 * - RSI < 30: 超卖，可能反弹
 * - RSI > 70: 超买，可能回调
 * 
 * @author Peter Wang
 */
@Component
public class RSICalculator implements TechnicalIndicator<Double> {
    
    private static final int DEFAULT_PERIOD = 14;
    private final int period;
    
    public RSICalculator() {
        this.period = DEFAULT_PERIOD;
    }
    
    public RSICalculator(int period) {
        this.period = period;
    }
    
    @Override
    public Double calculate(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            return null;
        }
        
        // 🔥 P1修复-20260128: 使用Wilder's Smoothing（标准RSI算法）
        // Bug修复：之前使用简单平均，导致RSI滞后
        
        // 计算价格变化序列
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        for (int i = klines.size() - period; i < klines.size(); i++) {
            BigDecimal currentClose = klines.get(i).getClosePrice();
            BigDecimal previousClose = klines.get(i - 1).getClosePrice();
            BigDecimal change = currentClose.subtract(previousClose);
            
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gains.add(change.doubleValue());
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change.doubleValue()));
            }
        }
        
        // 使用Wilder's Smoothing计算平均涨跌幅
        double avgGain = calculateWildersSmoothing(gains);
        double avgLoss = calculateWildersSmoothing(losses);
        
        // 避免除零
        if (avgLoss == 0) {
            return 100.0;
        }
        
        // 计算RS和RSI
        double rs = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        
        return Math.round(rsi * 100.0) / 100.0;
    }
    
    /**
     * 🔥 新增方法：Wilder's Smoothing
     * 类似EMA，但使用1/period作为平滑因子
     */
    private double calculateWildersSmoothing(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        
        // 第一个值：简单平均
        double sum = 0.0;
        int count = Math.min(period, values.size());
        for (int i = 0; i < count; i++) {
            sum += values.get(i);
        }
        double smoothed = sum / period;
        
        // 后续值：Wilder's Smoothing
        // Smoothed = (prevSmoothed * (period-1) + currentValue) / period
        for (int i = period; i < values.size(); i++) {
            smoothed = (smoothed * (period - 1) + values.get(i)) / period;
        }
        
        return smoothed;
    }
    
    @Override
    public String getName() {
        return "RSI";
    }
    
    @Override
    public int getRequiredPeriods() {
        return period + 1; // 需要period+1根K线才能计算
    }
    
    @Override
    public String getDescription() {
        return "RSI(" + period + ") - Relative Strength Index";
    }
}
