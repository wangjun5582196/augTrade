package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.BollingerBands;
import com.ltp.peter.augtrade.indicator.BollingerBandsCalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 布林带突破策略
 * 
 * 基于布林带的突破和回归进行交易：
 * - 价格触及下轨或从下轨反弹：做多
 * - 价格触及上轨或从上轨回落：做空
 * - 带宽收窄后的突破信号更强
 * 
 * 特点：
 * - 适合震荡后的趋势启动
 * - 简单有效的反转信号
 * - 交易频率：中（每天3-12次）
 * 
 * 权重：6（中等）
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "trading.strategy.bollinger-breakout.enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class BollingerBreakoutStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "BollingerBreakout";
    private static final int STRATEGY_WEIGHT = 6;
    
    @Autowired
    private BollingerBandsCalculator bollingerCalculator;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        List<Kline> klines = context.getKlines();
        if (klines.size() < 2) {
            log.warn("[{}] K线数量不足，需要至少2根", STRATEGY_NAME);
            return createHoldSignal("K线数量不足");
        }
        
        try {
            // 计算布林带
            BollingerBands bb = bollingerCalculator.calculate(klines);
            
            if (bb == null || bb.getUpper() == null || bb.getLower() == null) {
                log.warn("[{}] 布林带计算失败", STRATEGY_NAME);
                return createHoldSignal("布林带计算失败");
            }
            
            // 获取当前和前一根K线
            Kline current = klines.get(0);
            Kline previous = klines.get(1);
            
            BigDecimal currentPrice = current.getClosePrice();
            BigDecimal prevPrice = previous.getClosePrice();
            
            Double upperBand = bb.getUpper();
            Double middleBand = bb.getMiddle();
            Double lowerBand = bb.getLower();
            
            log.debug("[{}] 当前价: {}, 前价: {}, 上轨: {}, 中轨: {}, 下轨: {}", 
                    STRATEGY_NAME, currentPrice, prevPrice, upperBand, middleBand, lowerBand);
            
            // 计算带宽百分比
            double bandwidthPercent = bb.getBandwidthPercent();
            
            // 计算信号强度基础值
            int baseStrength = 70;
            
            // 如果带宽收窄（波动率降低），突破信号更强
            if (bandwidthPercent < 3.0) {
                baseStrength += 15;
                log.debug("[{}] 带宽收窄 ({}%), 提高信号强度", STRATEGY_NAME, String.format("%.2f", bandwidthPercent));
            }
            
            // 买入信号1：价格触及下轨
            if (bb.isPriceTouchingLower(currentPrice) || bb.isPriceBelowLower(currentPrice)) {
                int strength = baseStrength + (bb.isPriceBelowLower(currentPrice) ? 10 : 0);
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(strength)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("价格触及布林下轨 (%.2f, 下轨:%.2f)", 
                                currentPrice.doubleValue(), lowerBand))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .suggestedStopLoss(BigDecimal.valueOf(lowerBand * 0.998))  // 下轨下方0.2%
                        .suggestedTakeProfit(BigDecimal.valueOf(middleBand))  // 目标中轨
                        .build();
            }
            
            // 买入信号2：从下轨反弹
            if (prevPrice.doubleValue() <= lowerBand && currentPrice.compareTo(prevPrice) > 0) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(baseStrength + 5)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("价格从布林下轨反弹 (从%.2f涨至%.2f)", 
                                prevPrice.doubleValue(), currentPrice.doubleValue()))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .suggestedStopLoss(BigDecimal.valueOf(lowerBand * 0.998))
                        .suggestedTakeProfit(BigDecimal.valueOf(middleBand))
                        .build();
            }
            
            // 卖出信号1：价格触及上轨
            if (bb.isPriceTouchingUpper(currentPrice) || bb.isPriceAboveUpper(currentPrice)) {
                int strength = baseStrength + (bb.isPriceAboveUpper(currentPrice) ? 10 : 0);
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(strength)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("价格触及布林上轨 (%.2f, 上轨:%.2f)", 
                                currentPrice.doubleValue(), upperBand))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .suggestedStopLoss(BigDecimal.valueOf(upperBand * 1.002))  // 上轨上方0.2%
                        .suggestedTakeProfit(BigDecimal.valueOf(middleBand))  // 目标中轨
                        .build();
            }
            
            // 卖出信号2：从上轨回落
            if (prevPrice.doubleValue() >= upperBand && currentPrice.compareTo(prevPrice) < 0) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(baseStrength + 5)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("价格从布林上轨回落 (从%.2f跌至%.2f)", 
                                prevPrice.doubleValue(), currentPrice.doubleValue()))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .suggestedStopLoss(BigDecimal.valueOf(upperBand * 1.002))
                        .suggestedTakeProfit(BigDecimal.valueOf(middleBand))
                        .build();
            }
            
            // 额外观察：价格在中轨附近
            if (bb.isPriceNearMiddle(currentPrice)) {
                log.debug("[{}] 价格在中轨附近，观望", STRATEGY_NAME);
                return createHoldSignal("价格在中轨附近，等待突破");
            }
            
            // 没有触发信号
            String reason = String.format("价格在布林带中部 (%.2f, 带宽:%.2f%%)", 
                    currentPrice.doubleValue(), bandwidthPercent);
            return createHoldSignal(reason);
            
        } catch (Exception e) {
            log.error("[{}] 生成交易信号时发生错误", STRATEGY_NAME, e);
            return createHoldSignal("策略执行异常");
        }
    }
    
    @Override
    public String getName() {
        return STRATEGY_NAME;
    }
    
    @Override
    public int getWeight() {
        return STRATEGY_WEIGHT;
    }
    
    @Override
    public String getDescription() {
        return "布林带突破策略 - 基于价格触及或突破布林带上下轨的交易策略";
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}
