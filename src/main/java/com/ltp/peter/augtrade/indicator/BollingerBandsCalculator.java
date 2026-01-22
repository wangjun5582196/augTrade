package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 布林带 (Bollinger Bands) 计算器
 * 
 * 布林带是用于衡量价格波动性的技术指标：
 * - 中轨 = N期简单移动平均线(SMA)
 * - 上轨 = 中轨 + (K × N期标准差)
 * - 下轨 = 中轨 - (K × N期标准差)
 * 
 * 默认参数：N=20, K=2
 * 
 * 交易信号：
 * - 价格触及下轨：超卖，考虑做多
 * - 价格触及上轨：超买，考虑做空
 * - 价格突破上轨后回归：卖出信号
 * - 价格突破下轨后回归：买入信号
 * - 带宽收窄（Squeeze）：波动率降低，可能出现突破
 * - 带宽扩张（Expansion）：波动率升高，趋势确认
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class BollingerBandsCalculator implements TechnicalIndicator<BollingerBands> {
    
    private static final int DEFAULT_PERIOD = 20;
    private static final double DEFAULT_STD_DEV_MULTIPLIER = 2.0;
    
    private final int period;
    private final double stdDevMultiplier;
    
    public BollingerBandsCalculator() {
        this.period = DEFAULT_PERIOD;
        this.stdDevMultiplier = DEFAULT_STD_DEV_MULTIPLIER;
    }
    
    public BollingerBandsCalculator(int period, double stdDevMultiplier) {
        this.period = period;
        this.stdDevMultiplier = stdDevMultiplier;
    }
    
    @Override
    public BollingerBands calculate(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            log.warn("K线数据不足，需要至少 {} 根K线，当前只有 {} 根", getRequiredPeriods(), klines.size());
            return null;
        }
        
        try {
            // 获取最近N期的收盘价
            int startIndex = klines.size() - period;
            double sum = 0.0;
            
            for (int i = startIndex; i < klines.size(); i++) {
                sum += klines.get(i).getClosePrice().doubleValue();
            }
            
            // 计算中轨（SMA）
            double middle = sum / period;
            
            // 计算标准差
            double variance = 0.0;
            for (int i = startIndex; i < klines.size(); i++) {
                double price = klines.get(i).getClosePrice().doubleValue();
                variance += Math.pow(price - middle, 2);
            }
            double stdDev = Math.sqrt(variance / period);
            
            // 计算上轨和下轨
            double upper = middle + (stdDevMultiplier * stdDev);
            double lower = middle - (stdDevMultiplier * stdDev);
            
            // 计算带宽
            double bandwidth = upper - lower;
            
            // 计算%B（当前价格在布林带中的相对位置）
            double currentPrice = klines.get(klines.size() - 1).getClosePrice().doubleValue();
            double percentB = (currentPrice - lower) / bandwidth;
            
            return BollingerBands.builder()
                    .upper(upper)
                    .middle(middle)
                    .lower(lower)
                    .bandwidth(bandwidth)
                    .percentB(percentB)
                    .build();
                    
        } catch (Exception e) {
            log.error("计算布林带时发生错误", e);
            return null;
        }
    }
    
    @Override
    public String getName() {
        return "BollingerBands";
    }
    
    @Override
    public int getRequiredPeriods() {
        return period;
    }
    
    @Override
    public String getDescription() {
        return String.format("Bollinger Bands (%d,%.1f) - 价格波动性指标", 
                period, stdDevMultiplier);
    }
    
    /**
     * 计算简单移动平均线
     */
    private double calculateSMA(List<Kline> klines, int period) {
        if (klines.size() < period) {
            return 0.0;
        }
        
        double sum = 0.0;
        int startIndex = klines.size() - period;
        
        for (int i = startIndex; i < klines.size(); i++) {
            sum += klines.get(i).getClosePrice().doubleValue();
        }
        
        return sum / period;
    }
    
    /**
     * 计算标准差
     */
    private double calculateStdDev(List<Kline> klines, int period, double mean) {
        if (klines.size() < period) {
            return 0.0;
        }
        
        double variance = 0.0;
        int startIndex = klines.size() - period;
        
        for (int i = startIndex; i < klines.size(); i++) {
            double price = klines.get(i).getClosePrice().doubleValue();
            variance += Math.pow(price - mean, 2);
        }
        
        return Math.sqrt(variance / period);
    }
    
    /**
     * 判断是否出现布林带收窄（Bollinger Squeeze）
     * 带宽是过去N期中的最小值
     */
    public boolean isSqueezing(List<Kline> klines, int lookbackPeriod) {
        if (klines.size() < period + lookbackPeriod) {
            return false;
        }
        
        try {
            // 计算当前带宽
            BollingerBands current = calculate(klines);
            if (current == null || current.getBandwidth() == null) {
                return false;
            }
            
            double currentBandwidth = current.getBandwidth();
            
            // 查看过去N期的带宽，看当前是否是最小的
            double minBandwidth = currentBandwidth;
            for (int i = 1; i <= lookbackPeriod; i++) {
                if (klines.size() - i < period) {
                    break;
                }
                List<Kline> subList = klines.subList(0, klines.size() - i);
                BollingerBands bb = calculate(subList);
                if (bb != null && bb.getBandwidth() != null) {
                    minBandwidth = Math.min(minBandwidth, bb.getBandwidth());
                }
            }
            
            // 当前带宽是最小的，说明出现收窄
            return Math.abs(currentBandwidth - minBandwidth) < 0.0001;
            
        } catch (Exception e) {
            log.error("判断布林带收窄时发生错误", e);
            return false;
        }
    }
}
