package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.core.indicator.BollingerBandsCalculator;
import com.ltp.peter.augtrade.service.core.indicator.BollingerBands;
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 震荡市专用策略
 * 
 * 核心逻辑：均值回归 - 低买高卖
 * - 价格触及布林带下轨 + RSI超卖 → 做多（买入支撑）
 * - 价格触及布林带上轨 + RSI超买 → 做空（卖出阻力）
 * 
 * 适用场景：ADX < 20的震荡市和盘整市
 * 
 * 特点：
 * 1. 只在震荡市启用（由MarketRegimeDetector判断）
 * 2. 采用均值回归策略，而非趋势跟踪
 * 3. 快进快出，目标小但胜率高
 * 4. 避免追涨杀跌
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class RangingMarketStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "RangingMarket";
    private static final int STRATEGY_WEIGHT = 15;  // 震荡市中的主导策略
    
    @Autowired
    private BollingerBandsCalculator bollingerCalculator;
    
    @Autowired
    private MarketRegimeDetector regimeDetector;
    
    private boolean enabled = true;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        try {
            // 1. 检测市场环境 - 只在震荡市/盘整市启用
            MarketRegimeDetector.MarketRegime regime = regimeDetector.detectRegime(context.getKlines());
            
            if (regime != MarketRegimeDetector.MarketRegime.CHOPPY && 
                regime != MarketRegimeDetector.MarketRegime.CONSOLIDATION) {
                log.debug("[{}] {}市场，震荡市策略不适用", STRATEGY_NAME, regime.getDescription());
                return createHoldSignal(String.format("%s市场，使用趋势策略", regime.getDescription()));
            }
            
            log.info("[{}] ✅ {}市场，启用震荡市均值回归策略", STRATEGY_NAME, regime.getDescription());
            
            // 2. 获取技术指标（只需要布林带和Williams %R，不需要RSI）
            BollingerBands bb = context.getIndicator("BollingerBands", BollingerBands.class);
            Double williamsR = context.getIndicatorAsDouble("WilliamsR");
            
            if (bb == null || williamsR == null) {
                log.warn("[{}] 指标数据不足 - BB={}, WilliamsR={}", STRATEGY_NAME, 
                        bb != null ? "有" : "无", williamsR != null ? "有" : "无");
                return createHoldSignal("指标计算失败");
            }
            
            BigDecimal currentPrice = context.getCurrentPrice();
            double upperBand = bb.getUpper();
            double middleBand = bb.getMiddle();
            double lowerBand = bb.getLower();
            double price = currentPrice.doubleValue();
            
            // 计算价格在布林带中的位置（0-1之间，0.5为中轨）
            double bandRange = upperBand - lowerBand;
            if (bandRange == 0) {
                return createHoldSignal("布林带收窄至0");
            }
            
            double pricePosition = (price - lowerBand) / bandRange;
            double pricePositionPercent = pricePosition * 100;
            
            log.info("[{}] 价格位置: {}% (0%=下轨, 50%=中轨, 100%=上轨), Williams: {}", 
                    STRATEGY_NAME, String.format("%.1f", pricePositionPercent), 
                    williamsR != null ? String.format("%.1f", williamsR) : "N/A");
            
            // 3. 震荡市做多信号：低买（价格触及下轨 + 超卖）
            if (pricePositionPercent <= 20) {  // 价格在布林带下方20%位置
                int buyScore = 0;
                String buyReason = "";
                
                // Williams %R超卖（替代RSI）- 加重权重
                if (williamsR < -80) {
                    buyScore += 8;  // 极度超卖
                    buyReason += "Williams极度超卖(" + String.format("%.1f", williamsR) + "), ";
                } else if (williamsR < -70) {
                    buyScore += 5;  // 超卖
                    buyReason += "Williams超卖(" + String.format("%.1f", williamsR) + "), ";
                } else if (williamsR < -60) {
                    buyScore += 3;  // 偏低
                    buyReason += "Williams偏低(" + String.format("%.1f", williamsR) + "), ";
                }
                
                // 价格位置（越接近下轨分数越高）
                if (pricePositionPercent <= 5) {
                    buyScore += 5;
                    buyReason += "价格触及下轨, ";
                } else if (pricePositionPercent <= 10) {
                    buyScore += 3;
                    buyReason += "价格接近下轨, ";
                } else {
                    buyScore += 1;
                    buyReason += "价格偏低, ";
                }
                
                // 震荡市需要较高确认度（≥12分，提高阈值减少交易频率）
                if (buyScore >= 12) {
                    int strength = Math.min(60 + buyScore * 3, 90);
                    log.info("[{}] 🚀 震荡市做多信号（得分{}）: {}", STRATEGY_NAME, buyScore, buyReason);
                    
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("震荡市低买 - %s(得分:%d)", buyReason, buyScore))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                } else {
                    log.debug("[{}] 做多信号不足（得分{}），需要≥8分", STRATEGY_NAME, buyScore);
                }
            }
            
            // 4. 震荡市做空信号：高卖（价格触及上轨 + 超买）
            if (pricePositionPercent >= 80) {  // 价格在布林带上方80%位置
                int sellScore = 0;
                String sellReason = "";
                
                // Williams %R超买（替代RSI）- 加重权重
                if (williamsR > -20) {
                    sellScore += 8;  // 极度超买
                    sellReason += "Williams极度超买(" + String.format("%.1f", williamsR) + "), ";
                } else if (williamsR > -30) {
                    sellScore += 5;  // 超买
                    sellReason += "Williams超买(" + String.format("%.1f", williamsR) + "), ";
                } else if (williamsR > -40) {
                    sellScore += 3;  // 偏高
                    sellReason += "Williams偏高(" + String.format("%.1f", williamsR) + "), ";
                }
                
                // 价格位置（越接近上轨分数越高）
                if (pricePositionPercent >= 95) {
                    sellScore += 5;
                    sellReason += "价格触及上轨, ";
                } else if (pricePositionPercent >= 90) {
                    sellScore += 3;
                    sellReason += "价格接近上轨, ";
                } else {
                    sellScore += 1;
                    sellReason += "价格偏高, ";
                }
                
                // 震荡市需要较高确认度（≥12分，提高阈值减少交易频率）
                if (sellScore >= 12) {
                    int strength = Math.min(60 + sellScore * 3, 90);
                    log.info("[{}] 📉 震荡市做空信号（得分{}）: {}", STRATEGY_NAME, sellScore, sellReason);
                    
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("震荡市高卖 - %s(得分:%d)", sellReason, sellScore))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                } else {
                    log.debug("[{}] 做空信号不足（得分{}），需要≥8分", STRATEGY_NAME, sellScore);
                }
            }
            
            // 5. 价格在中间区域 - 观望
            log.debug("[{}] 价格在布林带中间区域（{}%），等待触及上下轨", 
                    STRATEGY_NAME, String.format("%.1f", pricePositionPercent));
            return createHoldSignal(String.format("震荡市，价格在中间区域（%.1f%%）", pricePositionPercent));
            
        } catch (Exception e) {
            log.error("[{}] 生成信号时发生错误", STRATEGY_NAME, e);
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
        return String.format("震荡市策略 - 布林带均值回归，低买高卖（权重:%d，仅震荡市启用）", STRATEGY_WEIGHT);
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 设置启用/禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[{}] 策略{}", STRATEGY_NAME, enabled ? "已启用" : "已禁用");
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}
