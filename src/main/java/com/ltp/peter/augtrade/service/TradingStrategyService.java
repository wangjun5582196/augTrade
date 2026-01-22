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
 * 🔥 2026-01-21 重构：使用精简策略（仅ADX+ATR+EMA）
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
    
    @Autowired
    private SimplifiedTrendStrategy simplifiedTrendStrategy;
    
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
     * 
     * 🔥 2026-01-21 重构：切换到精简策略（SimplifiedTrendStrategy）
     * 仅使用ADX、ATR、EMA三个核心指标
     * 
     * @param symbol 交易标的
     * @return 交易信号
     */
    public Signal executeShortTermStrategy(String symbol) {
        log.info("🎯 执行短线策略分析（SimplifiedTrend v2.0）: {}", symbol);
        
        // 🔥 使用新的精简策略
        SimplifiedTrendStrategy.Signal signal = simplifiedTrendStrategy.execute(symbol);
        
        // 转换信号类型
        if (signal == SimplifiedTrendStrategy.Signal.BUY) {
            return Signal.BUY;
        } else if (signal == SimplifiedTrendStrategy.Signal.SELL) {
            return Signal.SELL;
        } else {
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
     * 
     * 🔥 2026-01-21 重构：使用ATR动态止损止盈
     * 
     * @param entryPrice 入场价格
     * @param isBuy 是否买入
     * @return [止盈价格, 止损价格]
     */
    public BigDecimal[] calculateStopLevels(BigDecimal entryPrice, boolean isBuy) {
        // 🔥 使用新策略的ATR动态计算
        List<Kline> klines = marketDataService.getLatestKlines("XAUTUSDT", "5m", 50);
        if (klines == null || klines.size() < 14) {
            log.warn("⚠️ K线数据不足，使用默认止损止盈");
            // 回退到固定百分比
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
            
            return new BigDecimal[]{takeProfit, stopLoss};
        }
        
        String side = isBuy ? "BUY" : "SELL";
        BigDecimal[] levels = simplifiedTrendStrategy.calculateStopLossTakeProfit(klines, entryPrice, side);
        
        log.info("💰 止盈止损（ATR动态）- 入场价: {}, 止盈: {}, 止损: {}", 
                entryPrice, levels[0], levels[1]);
        
        return levels;
    }
}
