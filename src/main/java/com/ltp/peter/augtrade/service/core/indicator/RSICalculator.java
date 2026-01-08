package com.ltp.peter.augtrade.service.core.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        
        // 计算价格变化
        double gainSum = 0;
        double lossSum = 0;
        
        for (int i = klines.size() - period; i < klines.size(); i++) {
            BigDecimal currentClose = klines.get(i).getClosePrice();
            BigDecimal previousClose = klines.get(i - 1).getClosePrice();
            BigDecimal change = currentClose.subtract(previousClose);
            
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gainSum += change.doubleValue();
            } else {
                lossSum += Math.abs(change.doubleValue());
            }
        }
        
        // 计算平均涨跌幅
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        
        // 避免除零
        if (avgLoss == 0) {
            return 100.0;
        }
        
        // 计算RS和RSI
        double rs = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        
        return Math.round(rsi * 100.0) / 100.0;
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
