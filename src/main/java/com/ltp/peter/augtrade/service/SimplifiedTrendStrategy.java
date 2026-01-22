package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 精简趋势策略
 * 
 * 核心理念：少即是多，专注核心
 * 
 * 仅使用3个核心指标：
 * 1. ADX - 趋势强度（决定是否交易）
 * 2. ATR - 波动率（风控和止损）
 * 3. EMA20/50 - 趋势方向（避免逆势）
 * 
 * 策略逻辑：
 * - 只在强趋势（ADX>20）中交易
 * - 只做顺势交易（不逆势）
 * - 等待价格回调到EMA20附近入场
 * - 使用ATR动态止损止盈
 * 
 * 预期效果：
 * - 胜率：80%+
 * - 日均交易：5-8笔
 * - 平均盈利：+$15-20/笔
 * 
 * @author Peter Wang
 * @version 2.0
 * @since 2026-01-21
 */
@Slf4j
@Service
public class SimplifiedTrendStrategy {
    
    @Autowired
    private IndicatorService indicatorService;
    
    @Autowired
    private MarketDataService marketDataService;
    
    public enum Signal {
        BUY,    // 买入信号
        SELL,   // 卖出信号
        HOLD    // 持有/观望
    }
    
    /**
     * 核心策略：ADX + ATR + EMA
     * 
     * 这是基于37笔交易数据分析后的精简版本
     * 删除了所有冗余指标，只保留最有效的3个
     * 
     * @param symbol 交易标的
     * @return 交易信号
     */
    public Signal execute(String symbol) {
        log.info("🎯 执行精简趋势策略（仅ADX+ATR+EMA）");
        
        // 获取K线数据
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 50) {
            log.warn("⚠️ K线数据不足（需要50根），当前: {}", klines == null ? 0 : klines.size());
            return Signal.HOLD;
        }
        
        // ==========================================
        // 步骤1: 计算核心指标
        // ==========================================
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        BigDecimal ema20 = indicatorService.calculateEMA(klines, 20);
        BigDecimal ema50 = indicatorService.calculateEMA(klines, 50);
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal prevPrice = klines.get(1).getClosePrice();
        BigDecimal price15min = klines.get(Math.min(3, klines.size() - 1)).getClosePrice();
        
        // 计算动量和趋势
        BigDecimal momentum = currentPrice.subtract(prevPrice);
        boolean isUptrend = ema20.compareTo(ema50) > 0;
        boolean isDowntrend = ema20.compareTo(ema50) < 0;
        boolean isStrongTrend = adx.compareTo(new BigDecimal("25")) > 0;
        
        log.info(String.format("📊 核心指标 - ADX: %.2f, ATR: %.2f, 当前价: %.2f", 
                adx.doubleValue(), atr.doubleValue(), currentPrice.doubleValue()));
        log.info(String.format("📊 趋势判断 - EMA20: %.2f, EMA50: %.2f, 趋势: %s", 
                ema20.doubleValue(), ema50.doubleValue(), 
                isUptrend ? "上涨" : (isDowntrend ? "下跌" : "震荡")));
        
        // ==========================================
        // 步骤2: 市场环境过滤（三重保护）
        // ==========================================
        
        // 🔥 过滤1：ADX过滤 - 震荡市不交易
        if (adx.compareTo(new BigDecimal("20")) < 0) {
            log.info("⛔ ADX={} < 20，震荡市场（无明确趋势），暂停交易", adx.doubleValue());
            return Signal.HOLD;
        }
        
        // 🔥 过滤2：ATR过滤 - 高波动不交易
        if (atr.compareTo(new BigDecimal("6.0")) > 0) {
            log.warn("⛔ ATR={} > 6.0，高波动期（止损易被突破），暂停交易", atr.doubleValue());
            return Signal.HOLD;
        }
        
        // 🔥 过滤3：暴力行情保护 - 15分钟涨跌幅>1%不交易
        BigDecimal priceChange15m = currentPrice.subtract(price15min)
                                                .divide(price15min, 4, RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal("100"));
        if (priceChange15m.abs().compareTo(new BigDecimal("1.0")) > 0) {
            log.warn("⛔ 暴力行情！15分钟变动: {}%, 等待稳定后再交易", priceChange15m);
            return Signal.HOLD;
        }
        
        log.info("✅ 通过市场环境过滤（ADX={}, ATR={}, 15分钟变动={}%）", 
                adx.doubleValue(), atr.doubleValue(), priceChange15m.doubleValue());
        
        // ==========================================
        // 步骤3: 强趋势顺势交易（核心逻辑）
        // ==========================================
        
        if (!isStrongTrend) {
            // ADX 20-25 弱趋势区间：更谨慎，等待更好机会
            log.info("⚠️ ADX={} 在20-25弱趋势区间，等待更强信号", adx.doubleValue());
            return Signal.HOLD;
        }
        
        // 强趋势（ADX>25）：顺势交易
        log.info("🔥 强趋势市场（ADX={}），开始信号判断", adx.doubleValue());
        
        // 计算价格与EMA20的偏离度
        BigDecimal priceToEma20 = currentPrice.subtract(ema20)
                                              .divide(ema20, 4, RoundingMode.HALF_UP)
                                              .multiply(new BigDecimal("100"));
        
        log.info("📊 价格偏离EMA20: {}%（正值=高于EMA20，负值=低于EMA20）", priceToEma20);
        
        // ==========================================
        // 买入信号：上涨趋势 + 价格回调
        // ==========================================
        if (isUptrend) {
            log.info("📈 上涨趋势确认（EMA20 > EMA50）");
            
            // 买入条件：价格回调到EMA20附近（±0.3%以内）
            // 或价格略低于EMA20（回调买入机会）
            if (priceToEma20.compareTo(new BigDecimal("0.3")) <= 0 && 
                priceToEma20.compareTo(new BigDecimal("-0.5")) >= 0) {
                
                // 额外确认：确保当前动量向上（避免下跌中途买入）
                if (momentum.compareTo(BigDecimal.ZERO) >= 0) {
                    log.info("🚀 ✅ 买入信号触发！");
                    log.info("   理由：上涨趋势 + 价格回调EMA20（{}%）+ 动量向上", priceToEma20);
                    log.info("   止损：ATR {} * 3.0 = {} 美元", atr, atr.multiply(new BigDecimal("3.0")));
                    log.info("   止盈：ATR {} * 4.0 = {} 美元", atr, atr.multiply(new BigDecimal("4.0")));
                    return Signal.BUY;
                } else {
                    log.info("⚠️ 价格虽回调EMA20，但动量向下，等待企稳");
                }
            } else if (priceToEma20.compareTo(new BigDecimal("-0.5")) < 0) {
                log.info("⚠️ 价格远低于EMA20（{}%），等待回归", priceToEma20);
            } else {
                log.info("⚠️ 价格高于EMA20（{}%），等待回调", priceToEma20);
            }
        }
        
        // ==========================================
        // 卖出信号：下跌趋势 + 价格反弹
        // ==========================================
        if (isDowntrend) {
            log.info("📉 下跌趋势确认（EMA20 < EMA50）");
            
            // 卖出条件：价格反弹到EMA20附近（±0.3%以内）
            // 或价格略高于EMA20（反弹做空机会）
            if (priceToEma20.compareTo(new BigDecimal("0.5")) <= 0 && 
                priceToEma20.compareTo(new BigDecimal("-0.3")) >= 0) {
                
                // 额外确认：确保当前动量向下（避免上涨中途卖出）
                if (momentum.compareTo(BigDecimal.ZERO) <= 0) {
                    log.info("📉 ✅ 卖出信号触发！");
                    log.info("   理由：下跌趋势 + 价格反弹EMA20（{}%）+ 动量向下", priceToEma20);
                    log.info("   止损：ATR {} * 3.0 = {} 美元", atr, atr.multiply(new BigDecimal("3.0")));
                    log.info("   止盈：ATR {} * 4.0 = {} 美元", atr, atr.multiply(new BigDecimal("4.0")));
                    return Signal.SELL;
                } else {
                    log.info("⚠️ 价格虽反弹EMA20，但动量向上，等待回落");
                }
            } else if (priceToEma20.compareTo(new BigDecimal("0.5")) > 0) {
                log.info("⚠️ 价格远高于EMA20（{}%），等待回归", priceToEma20);
            } else {
                log.info("⚠️ 价格低于EMA20（{}%），等待反弹", priceToEma20);
            }
        }
        
        // ==========================================
        // 震荡市（无明确趋势方向）
        // ==========================================
        if (!isUptrend && !isDowntrend) {
            log.info("⚠️ EMA20与EMA50接近，无明确趋势方向，观望");
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 获取止损止盈价格
     * 
     * 基于ATR动态计算
     * 止损：ATR * 3.0
     * 止盈：ATR * 4.0
     * 盈亏比：1.33:1
     * 
     * @param klines K线数据
     * @param entryPrice 入场价格
     * @param side 交易方向（BUY/SELL）
     * @return [止盈价格, 止损价格]
     */
    public BigDecimal[] calculateStopLossTakeProfit(List<Kline> klines, BigDecimal entryPrice, String side) {
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        
        BigDecimal stopLossDistance = atr.multiply(new BigDecimal("3.0"));
        BigDecimal takeProfitDistance = atr.multiply(new BigDecimal("4.0"));
        
        BigDecimal stopLoss;
        BigDecimal takeProfit;
        
        if ("BUY".equals(side)) {
            stopLoss = entryPrice.subtract(stopLossDistance);
            takeProfit = entryPrice.add(takeProfitDistance);
        } else {
            stopLoss = entryPrice.add(stopLossDistance);
            takeProfit = entryPrice.subtract(takeProfitDistance);
        }
        
        log.info("💰 止损止盈设置 - ATR: {}, 止损距离: {} ({}), 止盈距离: {} ({})", 
                atr, stopLossDistance, stopLoss, takeProfitDistance, takeProfit);
        
        return new BigDecimal[]{takeProfit, stopLoss};
    }
    
    /**
     * 获取当前市场状态描述
     * 
     * 用于记录和日志
     * 
     * @param klines K线数据
     * @return 市场状态字符串
     */
    public String getMarketRegime(List<Kline> klines) {
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        BigDecimal ema20 = indicatorService.calculateEMA(klines, 20);
        BigDecimal ema50 = indicatorService.calculateEMA(klines, 50);
        
        boolean isUptrend = ema20.compareTo(ema50) > 0;
        boolean isDowntrend = ema20.compareTo(ema50) < 0;
        
        if (adx.compareTo(new BigDecimal("30")) > 0) {
            return isUptrend ? "STRONG_UPTREND" : (isDowntrend ? "STRONG_DOWNTREND" : "STRONG_TREND");
        } else if (adx.compareTo(new BigDecimal("20")) > 0) {
            return isUptrend ? "WEAK_UPTREND" : (isDowntrend ? "WEAK_DOWNTREND" : "WEAK_TREND");
        } else {
            return "RANGING";
        }
    }
    
    /**
     * 获取信号强度
     * 
     * 简化版本，只基于ADX
     * ADX越高，信号强度越高
     * 
     * @param klines K线数据
     * @return 信号强度（0-100）
     */
    public int getSignalStrength(List<Kline> klines) {
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        
        // 简单映射：ADX直接转换为信号强度
        // ADX 20 → 强度 50
        // ADX 30 → 强度 75
        // ADX 40 → 强度 100
        int strength = adx.multiply(new BigDecimal("2.5")).intValue();
        
        // 限制在0-100范围
        strength = Math.max(0, Math.min(100, strength));
        
        log.info("📊 信号强度: {} (基于ADX={})", strength, adx.doubleValue());
        
        return strength;
    }
    
    /**
     * 策略说明
     * 
     * @return 策略描述
     */
    public String getStrategyDescription() {
        return "精简趋势策略 v2.0 - 仅使用ADX+ATR+EMA三个核心指标";
    }
    
    /**
     * 策略名称
     * 
     * @return 策略名称（用于数据库记录）
     */
    public String getStrategyName() {
        return "SimplifiedTrend";
    }
    
    /**
     * 获取详细的信号说明
     * 
     * 用于日志和通知
     * 
     * @param klines K线数据
     * @param signal 信号类型
     * @return 信号说明
     */
    public String getSignalExplanation(List<Kline> klines, Signal signal) {
        if (signal == Signal.HOLD) {
            return "无交易信号";
        }
        
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        BigDecimal ema20 = indicatorService.calculateEMA(klines, 20);
        BigDecimal ema50 = indicatorService.calculateEMA(klines, 50);
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        
        BigDecimal priceToEma20 = currentPrice.subtract(ema20)
                                              .divide(ema20, 4, RoundingMode.HALF_UP)
                                              .multiply(new BigDecimal("100"));
        
        String trendDirection = ema20.compareTo(ema50) > 0 ? "上涨" : "下跌";
        
        return String.format("强趋势%s（ADX=%.2f）+ 价格回调EMA20（偏离%.2f%%）", 
                trendDirection, adx.doubleValue(), priceToEma20.doubleValue());
    }
}
