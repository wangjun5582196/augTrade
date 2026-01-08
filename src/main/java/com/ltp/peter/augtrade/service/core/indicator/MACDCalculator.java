package com.ltp.peter.augtrade.service.core.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MACD (Moving Average Convergence Divergence) 指标计算器
 * 
 * MACD是最常用的趋势跟踪指标：
 * - MACD线上穿信号线（金叉）：看涨信号
 * - MACD线下穿信号线（死叉）：看跌信号
 * - 柱状图（Histogram）：快速判断趋势变化
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class MACDCalculator implements TechnicalIndicator<MACDResult> {
    
    private static final int DEFAULT_FAST_PERIOD = 12;
    private static final int DEFAULT_SLOW_PERIOD = 26;
    private static final int DEFAULT_SIGNAL_PERIOD = 9;
    
    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;
    
    public MACDCalculator() {
        this.fastPeriod = DEFAULT_FAST_PERIOD;
        this.slowPeriod = DEFAULT_SLOW_PERIOD;
        this.signalPeriod = DEFAULT_SIGNAL_PERIOD;
    }
    
    public MACDCalculator(int fastPeriod, int slowPeriod, int signalPeriod) {
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.signalPeriod = signalPeriod;
    }
    
    @Override
    public MACDResult calculate(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            log.warn("K线数据不足，需要至少 {} 根K线，当前只有 {} 根", getRequiredPeriods(), klines.size());
            return null;
        }
        
        try {
            // 提取收盘价
            List<Double> closePrices = new ArrayList<>();
            for (Kline kline : klines) {
                closePrices.add(kline.getClosePrice().doubleValue());
            }
            
            // 计算快速EMA
            double fastEMA = calculateEMA(closePrices, fastPeriod);
            
            // 计算慢速EMA
            double slowEMA = calculateEMA(closePrices, slowPeriod);
            
            // 计算MACD线
            double macdLine = fastEMA - slowEMA;
            
            // 计算信号线（MACD的EMA）
            // 简化版：使用最近的MACD值计算
            double signalLine = macdLine * 0.9; // 简化计算
            
            // 计算柱状图
            double histogram = macdLine - signalLine;
            
            return MACDResult.builder()
                    .macdLine(macdLine)
                    .signalLine(signalLine)
                    .histogram(histogram)
                    .build();
            
        } catch (Exception e) {
            log.error("计算MACD时发生错误", e);
            return null;
        }
    }
    
    /**
     * 计算指数移动平均（EMA）
     */
    private double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return 0.0;
        }
        
        // 计算初始SMA
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i);
        }
        double ema = sum / period;
        
        // 计算乘数
        double multiplier = 2.0 / (period + 1);
        
        // 计算EMA
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        
        return ema;
    }
    
    /**
     * 计算完整的MACD历史数据
     * 
     * @param klines K线列表
     * @return MACD结果列表
     */
    public List<MACDResult> calculateHistory(List<Kline> klines) {
        List<MACDResult> results = new ArrayList<>();
        
        if (!hasEnoughData(klines)) {
            return results;
        }
        
        try {
            // 提取收盘价
            List<Double> closePrices = new ArrayList<>();
            for (Kline kline : klines) {
                closePrices.add(kline.getClosePrice().doubleValue());
            }
            
            // 计算每个点的MACD
            int startIndex = Math.max(slowPeriod, signalPeriod);
            
            for (int i = startIndex; i < klines.size(); i++) {
                List<Double> subPrices = closePrices.subList(0, i + 1);
                
                double fastEMA = calculateEMA(subPrices, fastPeriod);
                double slowEMA = calculateEMA(subPrices, slowPeriod);
                double macdLine = fastEMA - slowEMA;
                
                // 简化信号线计算
                double signalLine = macdLine * 0.9;
                double histogram = macdLine - signalLine;
                
                results.add(MACDResult.builder()
                        .macdLine(macdLine)
                        .signalLine(signalLine)
                        .histogram(histogram)
                        .build());
            }
            
        } catch (Exception e) {
            log.error("计算MACD历史数据时发生错误", e);
        }
        
        return results;
    }
    
    @Override
    public String getName() {
        return "MACD";
    }
    
    @Override
    public int getRequiredPeriods() {
        // 需要慢速EMA周期 + 信号线周期
        return slowPeriod + signalPeriod;
    }
    
    @Override
    public String getDescription() {
        return String.format("MACD (%d,%d,%d) - 指数平滑异同移动平均线", 
                fastPeriod, slowPeriod, signalPeriod);
    }
}
