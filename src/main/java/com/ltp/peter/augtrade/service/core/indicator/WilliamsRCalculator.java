package com.ltp.peter.augtrade.service.core.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Williams %R（威廉指标）计算器
 * 
 * Williams %R值范围：-100 到 0
 * - Williams %R < -80: 超卖，可能反弹
 * - Williams %R > -20: 超买，可能回调
 * - Williams %R < -60: 做多信号
 * - Williams %R > -40: 做空信号
 * 
 * @author Peter Wang
 */
@Component
public class WilliamsRCalculator implements TechnicalIndicator<Double> {
    
    private static final int DEFAULT_PERIOD = 14;
    private final int period;
    
    public WilliamsRCalculator() {
        this.period = DEFAULT_PERIOD;
    }
    
    public WilliamsRCalculator(int period) {
        this.period = period;
    }
    
    @Override
    public Double calculate(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            return null;
        }
        
        // 获取最近period根K线
        List<Kline> recentKlines = klines.subList(klines.size() - period, klines.size());
        
        // 找出最高价和最低价
        BigDecimal highestHigh = recentKlines.get(0).getHighPrice();
        BigDecimal lowestLow = recentKlines.get(0).getLowPrice();
        
        for (Kline kline : recentKlines) {
            if (kline.getHighPrice().compareTo(highestHigh) > 0) {
                highestHigh = kline.getHighPrice();
            }
            if (kline.getLowPrice().compareTo(lowestLow) < 0) {
                lowestLow = kline.getLowPrice();
            }
        }
        
        // 获取最新收盘价
        BigDecimal currentClose = recentKlines.get(recentKlines.size() - 1).getClosePrice();
        
        // 计算Williams %R
        // Williams %R = (最高价 - 收盘价) / (最高价 - 最低价) * -100
        BigDecimal range = highestHigh.subtract(lowestLow);
        
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return -50.0; // 避免除零，返回中间值
        }
        
        BigDecimal williamsR = highestHigh.subtract(currentClose)
                .divide(range, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("-100"));
        
        return Math.round(williamsR.doubleValue() * 100.0) / 100.0;
    }
    
    @Override
    public String getName() {
        return "Williams %R";
    }
    
    @Override
    public int getRequiredPeriods() {
        return period;
    }
    
    @Override
    public String getDescription() {
        return "Williams %R(" + period + ") - Williams Percent Range";
    }
}
