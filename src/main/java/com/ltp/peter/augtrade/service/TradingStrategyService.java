package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易策略服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class TradingStrategyService {
    
    @Autowired
    private IndicatorService indicatorService;
    
    @Autowired
    private MarketDataService marketDataService;
    
    /**
     * 交易信号枚举
     */
    public enum Signal {
        BUY,    // 买入信号
        SELL,   // 卖出信号
        HOLD    // 持有/观望
    }
    
    /**
     * 短线趋势跟踪策略
     * 结合均线、RSI、MACD多个指标综合判断
     */
    public Signal executeShortTermStrategy(String symbol) {
        log.info("执行短线策略分析: {}", symbol);
        
        // 获取最新的K线数据（5分钟周期）
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 100);
        
        if (klines == null || klines.size() < 50) {
            log.warn("K线数据不足，无法分析");
            return Signal.HOLD;
        }
        
        // 1. 计算移动平均线
        BigDecimal sma5 = indicatorService.calculateSMA(klines, 5);
        BigDecimal sma10 = indicatorService.calculateSMA(klines, 10);
        BigDecimal sma20 = indicatorService.calculateSMA(klines, 20);
        
        // 2. 计算RSI
        BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
        
        // 3. 计算MACD
        BigDecimal[] macd = indicatorService.calculateMACD(klines, 12, 26, 9);
        BigDecimal macdValue = macd[0];
        BigDecimal macdSignal = macd[1];
        BigDecimal macdHistogram = macd[2];
        
        // 4. 获取当前价格
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        
        log.info("技术指标 - 价格: {}, SMA5: {}, SMA10: {}, SMA20: {}, RSI: {}, MACD: {}", 
                currentPrice, sma5, sma10, sma20, rsi, macdValue);
        
        // 买入信号判断
        int buySignals = 0;
        
        // 均线多头排列 (SMA5 > SMA10 > SMA20)
        if (sma5.compareTo(sma10) > 0 && sma10.compareTo(sma20) > 0) {
            buySignals++;
            log.info("✓ 均线多头排列");
        }
        
        // 金叉
        if (indicatorService.isGoldenCross(klines, 5, 10)) {
            buySignals++;
            log.info("✓ 检测到金叉");
        }
        
        // RSI超卖后反弹
        if (rsi.compareTo(BigDecimal.valueOf(30)) < 0 || 
           (rsi.compareTo(BigDecimal.valueOf(40)) < 0 && rsi.compareTo(BigDecimal.valueOf(30)) > 0)) {
            buySignals++;
            log.info("✓ RSI超卖反弹区域");
        }
        
        // MACD金叉
        if (macdHistogram.compareTo(BigDecimal.ZERO) > 0 && macdValue.compareTo(macdSignal) > 0) {
            buySignals++;
            log.info("✓ MACD金叉");
        }
        
        // 卖出信号判断
        int sellSignals = 0;
        
        // 均线空头排列
        if (sma5.compareTo(sma10) < 0 && sma10.compareTo(sma20) < 0) {
            sellSignals++;
            log.info("✗ 均线空头排列");
        }
        
        // 死叉
        if (indicatorService.isDeathCross(klines, 5, 10)) {
            sellSignals++;
            log.info("✗ 检测到死叉");
        }
        
        // RSI超买
        if (rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
            sellSignals++;
            log.info("✗ RSI超买区域");
        }
        
        // MACD死叉
        if (macdHistogram.compareTo(BigDecimal.ZERO) < 0 && macdValue.compareTo(macdSignal) < 0) {
            sellSignals++;
            log.info("✗ MACD死叉");
        }
        
        // 综合判断（需要至少2个信号确认）
        if (buySignals >= 2 && sellSignals == 0) {
            log.info("==> 生成买入信号 (买入信号数: {})", buySignals);
            return Signal.BUY;
        } else if (sellSignals >= 2 && buySignals == 0) {
            log.info("==> 生成卖出信号 (卖出信号数: {})", sellSignals);
            return Signal.SELL;
        } else {
            log.info("==> 保持观望 (买入信号: {}, 卖出信号: {})", buySignals, sellSignals);
            return Signal.HOLD;
        }
    }
    
    /**
     * 突破策略
     * 基于布林带突破
     */
    public Signal executeBreakoutStrategy(String symbol) {
        log.info("执行突破策略分析: {}", symbol);
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        // 计算布林带
        BigDecimal[] bollingerBands = indicatorService.calculateBollingerBands(klines, 20, 2.0);
        BigDecimal upperBand = bollingerBands[0];
        BigDecimal middleBand = bollingerBands[1];
        BigDecimal lowerBand = bollingerBands[2];
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal prevPrice = klines.get(1).getClosePrice();
        
        log.info("布林带 - 上轨: {}, 中轨: {}, 下轨: {}, 当前价格: {}", 
                upperBand, middleBand, lowerBand, currentPrice);
        
        // 向上突破上轨
        if (prevPrice.compareTo(upperBand) <= 0 && currentPrice.compareTo(upperBand) > 0) {
            log.info("==> 突破上轨，生成买入信号");
            return Signal.BUY;
        }
        
        // 向下突破下轨
        if (prevPrice.compareTo(lowerBand) >= 0 && currentPrice.compareTo(lowerBand) < 0) {
            log.info("==> 跌破下轨，生成卖出信号");
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 计算止盈止损价格
     * @param entryPrice 入场价格
     * @param isBuy 是否买入
     * @return [止盈价格, 止损价格]
     */
    public BigDecimal[] calculateStopLevels(BigDecimal entryPrice, boolean isBuy) {
        // 黄金短线交易：止盈2%，止损1%
        BigDecimal takeProfitPercent = new BigDecimal("0.02");
        BigDecimal stopLossPercent = new BigDecimal("0.01");
        
        BigDecimal takeProfit;
        BigDecimal stopLoss;
        
        if (isBuy) {
            takeProfit = entryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));
            stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
        } else {
            takeProfit = entryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent));
            stopLoss = entryPrice.multiply(BigDecimal.ONE.add(stopLossPercent));
        }
        
        log.info("止盈止损 - 入场价: {}, 止盈: {}, 止损: {}", entryPrice, takeProfit, stopLoss);
        return new BigDecimal[]{takeProfit, stopLoss};
    }
}
