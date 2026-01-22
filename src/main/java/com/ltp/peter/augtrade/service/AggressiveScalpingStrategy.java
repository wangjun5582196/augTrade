package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 激进短线剥头皮策略
 * 专为高频交易设计，更宽松的入场条件
 * 
 * 特点：
 * 1. 降低入场阈值，增加交易频率
 * 2. 使用动量突破
 * 3. 快进快出
 * 4. 小止损小止盈
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class AggressiveScalpingStrategy {
    
    @Autowired
    private IndicatorService indicatorService;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private MLPredictionService mlPredictionService;
    
    public enum Signal {
        BUY, SELL, HOLD
    }
    
    /**
     * 策略1: 动量突破策略（最激进）
     * 
     * 核心逻辑：只要价格有明显动量就入场
     * 适合：震荡和趋势市场
     * 交易频率：高（每天5-20次）
     */
    public Signal momentumBreakoutStrategy(String symbol) {
        log.info("🔥 执行激进动量突破策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal price1 = klines.get(1).getClosePrice();
        BigDecimal price5 = klines.get(5).getClosePrice();
        BigDecimal price10 = klines.get(10).getClosePrice();
        
        // 计算短期动量
        BigDecimal momentum1 = currentPrice.subtract(price1);
        BigDecimal momentum5 = currentPrice.subtract(price5);
        BigDecimal momentum10 = currentPrice.subtract(price10);
        
        // 计算ATR作为参考
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        
        log.info("📊 动量 - 1K: {}, 5K: {}, 10K: {}, ATR: {}", 
                momentum1, momentum5, momentum10, atr);
        
        // 买入信号：连续上涨动量
        // 条件宽松：5分钟涨幅 > ATR * 0.3
        if (momentum5.compareTo(atr.multiply(new BigDecimal("0.3"))) > 0 &&
            momentum1.compareTo(BigDecimal.ZERO) > 0) {
            log.info("🚀 买入信号：价格突破向上，动量: {}", momentum5);
            return Signal.BUY;
        }
        
        // 卖出信号：连续下跌动量
        if (momentum5.compareTo(atr.multiply(new BigDecimal("-0.3"))) < 0 &&
            momentum1.compareTo(BigDecimal.ZERO) < 0) {
            log.info("📉 卖出信号：价格突破向下，动量: {}", momentum5);
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略2: RSI快速反转策略
     * 
     * 核心逻辑：RSI快速冲高或跌深时反向交易
     * 适合：震荡市场
     * 交易频率：中高（每天3-10次）
     */
    public Signal rsiReversalStrategy(String symbol) {
        log.info("🔥 执行RSI快速反转策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        // 计算RSI
        BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        
        log.info("📊 RSI: {}, Williams: {}", rsi, williamsR);
        
        // 买入：RSI < 40（放宽从30到40）
        if (rsi.compareTo(new BigDecimal("40")) < 0) {
            log.info("🚀 买入信号：RSI超卖 ({})", rsi);
            return Signal.BUY;
        }
        
        // 卖出：RSI > 60（放宽从70到60）
        if (rsi.compareTo(new BigDecimal("60")) > 0) {
            log.info("📉 卖出信号：RSI超买 ({})", rsi);
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略3: 宽松版Williams策略
     * 
     * 核心逻辑：放宽Williams阈值，增加交易频率
     * 适合：初期测试和数据积累
     * 交易频率：高（每天10-30次）
     */
    public Signal relaxedWilliamsStrategy(String symbol) {
        log.info("🔥 执行宽松Williams策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        
        // ML预测作为辅助（阈值放宽）
        double mlPrediction = mlPredictionService.predictMarketDirection(klines);
        
        log.info("📊 Williams: {}, ADX: {}, ML: {}", williamsR, adx, String.format("%.2f", mlPrediction));
        
        // 买入条件（大幅放宽）：
        // 1. Williams < -50（从-70放宽到-50）
        // 2. ML > 0.45（从0.75放宽到0.45，只要稍微看涨就行）
        if (williamsR.compareTo(new BigDecimal("-50")) < 0 &&
            mlPrediction > 0.45) {
            log.info("🚀 买入信号：Williams={}, ML={}", williamsR, String.format("%.2f", mlPrediction));
            return Signal.BUY;
        }
        
        // 卖出条件（大幅放宽）：
        // 1. Williams > -50（从-30放宽到-50）
        // 2. ML < 0.55（从0.25放宽到0.55，只要稍微看跌就行）
        if (williamsR.compareTo(new BigDecimal("-50")) > 0 &&
            mlPrediction < 0.55) {
            log.info("📉 卖出信号：Williams={}, ML={}", williamsR, String.format("%.2f", mlPrediction));
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略4: 价格通道突破策略
     * 
     * 核心逻辑：突破近期高低点就入场
     * 适合：趋势市场
     * 交易频率：中（每天5-15次）
     */
    public Signal channelBreakoutStrategy(String symbol) {
        log.info("🔥 执行价格通道突破策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        
        // 计算近20根K线的高低点
        BigDecimal highest = klines.stream()
            .limit(20)
            .map(Kline::getHighPrice)
            .max(BigDecimal::compareTo)
            .orElse(currentPrice);
            
        BigDecimal lowest = klines.stream()
            .limit(20)
            .map(Kline::getLowPrice)
            .min(BigDecimal::compareTo)
            .orElse(currentPrice);
        
        // 计算通道宽度
        BigDecimal channelWidth = highest.subtract(lowest);
        BigDecimal upperBreak = highest.subtract(channelWidth.multiply(new BigDecimal("0.1"))); // 突破上轨90%
        BigDecimal lowerBreak = lowest.add(channelWidth.multiply(new BigDecimal("0.1")));       // 突破下轨10%
        
        log.info("📊 价格: {}, 通道: {}-{}, 突破线: {}/{}", 
                currentPrice, lowest, highest, upperBreak, lowerBreak);
        
        // 买入：价格接近或突破通道下轨（支撑）
        if (currentPrice.compareTo(lowerBreak) <= 0) {
            log.info("🚀 买入信号：价格触及支撑位 {}", lowerBreak);
            return Signal.BUY;
        }
        
        // 卖出：价格接近或突破通道上轨（阻力）
        if (currentPrice.compareTo(upperBreak) >= 0) {
            log.info("📉 卖出信号：价格触及阻力位 {}", upperBreak);
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略5: 简单移动平均交叉策略
     * 
     * 核心逻辑：快线上穿慢线做多，下穿做空
     * 适合：新手和趋势市场
     * 交易频率：低-中（每天2-8次）
     */
    public Signal macdCrossStrategy(String symbol) {
        log.info("🔥 执行MACD交叉策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 30) {
            return Signal.HOLD;
        }
        
        // 计算MACD
        BigDecimal[] macd = indicatorService.calculateMACD(klines, 12, 26, 9);
        BigDecimal macdLine = macd[0];
        BigDecimal signalLine = macd[1];
        BigDecimal histogram = macd[2];
        
        // 获取前一根K线的MACD
        List<Kline> prevKlines = klines.subList(1, klines.size());
        BigDecimal[] prevMacd = indicatorService.calculateMACD(prevKlines, 12, 26, 9);
        BigDecimal prevHistogram = prevMacd[2];
        
        log.info("📊 MACD: {}, Signal: {}, Histogram: {}", macdLine, signalLine, histogram);
        
        // 买入：MACD柱状图从负转正（金叉）
        if (histogram.compareTo(BigDecimal.ZERO) > 0 &&
            prevHistogram.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("🚀 买入信号：MACD金叉");
            return Signal.BUY;
        }
        
        // 卖出：MACD柱状图从正转负（死叉）
        if (histogram.compareTo(BigDecimal.ZERO) < 0 &&
            prevHistogram.compareTo(BigDecimal.ZERO) >= 0) {
            log.info("📉 卖出信号：MACD死叉");
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略6: 超级激进策略（仅用于测试）
     * 
     * 核心逻辑：几乎任何小波动都交易
     * 适合：快速积累测试数据
     * 交易频率：极高（每天30-100次）
     * 
     * ⚠️ 注意：此策略仅用于模拟测试，不建议真实交易
     */
    public Signal superAggressiveStrategy(String symbol) {
        log.info("⚡ 执行超级激进策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 30);
        if (klines == null || klines.size() < 10) {
            return Signal.HOLD;
        }
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal price2 = klines.get(2).getClosePrice();
        BigDecimal price5 = klines.get(5).getClosePrice();
        
        // 短期动量
        BigDecimal shortMomentum = currentPrice.subtract(price2);
        BigDecimal mediumMomentum = currentPrice.subtract(price5);
        
        // Williams %R
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        
        // ML预测
        double mlPrediction = mlPredictionService.predictMarketDirection(klines);
        
        log.info("📊 动量: 2K={}, 5K={}, Williams: {}, ML: {}", 
                shortMomentum, mediumMomentum, williamsR, String.format("%.2f", mlPrediction));
        
        // 买入条件（超级宽松）：
        // 任何一个满足即可：
        // 1. 短期动量向上
        // 2. Williams < -40（从-70放宽到-40）
        // 3. ML > 0.52（只要稍微大于0.5就行）
        if (shortMomentum.compareTo(BigDecimal.ZERO) > 0 ||
            williamsR.compareTo(new BigDecimal("-40")) < 0 ||
            mlPrediction > 0.52) {
            log.info("🚀 买入信号：动量={}, Williams={}, ML={}", 
                    shortMomentum, williamsR, String.format("%.2f", mlPrediction));
            return Signal.BUY;
        }
        
        // 卖出条件（超级宽松）：
        // 任何一个满足即可：
        // 1. 短期动量向下
        // 2. Williams > -60（从-30放宽到-60）
        // 3. ML < 0.48（只要稍微小于0.5就行）
        if (shortMomentum.compareTo(BigDecimal.ZERO) < 0 ||
            williamsR.compareTo(new BigDecimal("-60")) > 0 ||
            mlPrediction < 0.48) {
            log.info("📉 卖出信号：动量={}, Williams={}, ML={}", 
                    shortMomentum, williamsR, String.format("%.2f", mlPrediction));
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略7: 布林带挤压突破（推荐）
     * 
     * 核心逻辑：布林带收窄后的突破
     * 适合：震荡后的趋势启动
     * 交易频率：中（每天3-12次）
     */
    public Signal bollingerBreakoutStrategy(String symbol) {
        log.info("🔥 执行布林带突破策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        // 计算布林带
        BigDecimal[] bollinger = indicatorService.calculateBollingerBands(klines, 20, 2);
        BigDecimal upperBand = bollinger[0];
        BigDecimal middleBand = bollinger[1];
        BigDecimal lowerBand = bollinger[2];
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal prevPrice = klines.get(1).getClosePrice();
        
        // 计算带宽（波动率）
        BigDecimal bandwidth = upperBand.subtract(lowerBand)
                .divide(middleBand, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        log.info("📊 布林带 - 上:{}, 中:{}, 下:{}, 带宽:{}", 
                upperBand, middleBand, lowerBand, bandwidth);
        
        // 买入：价格触及下轨或从下轨反弹
        if (currentPrice.compareTo(lowerBand) <= 0 ||
            (prevPrice.compareTo(lowerBand) <= 0 && 
             currentPrice.compareTo(prevPrice) > 0)) {
            log.info("🚀 买入信号：价格触及布林下轨反弹");
            return Signal.BUY;
        }
        
        // 卖出：价格触及上轨或从上轨回落
        if (currentPrice.compareTo(upperBand) >= 0 ||
            (prevPrice.compareTo(upperBand) >= 0 && 
             currentPrice.compareTo(prevPrice) < 0)) {
            log.info("📉 卖出信号：价格触及布林上轨回落");
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略8: 简化版ML策略（降低阈值）
     * 
     * 核心逻辑：只看ML预测，阈值大幅降低
     * 适合：相信AI预测的交易者
     * 交易频率：高（每天8-20次）
     */
    public Signal simplifiedMLStrategy(String symbol) {
        log.info("🔥 执行简化ML策略（降低阈值）");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        double mlPrediction = mlPredictionService.predictMarketDirection(klines);
        double confidence = mlPredictionService.getConfidence(klines);
        
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        
        log.info("📊 ML: {}, 置信度: {}, Williams: {}", 
                String.format("%.2f", mlPrediction), String.format("%.2f", confidence), williamsR);
        
        // 买入条件（大幅降低阈值）：
        // ML > 0.55（从0.75降到0.55）
        // 置信度 > 0.5（从0.7降到0.5）
        if (mlPrediction > 0.55 && confidence > 0.5) {
            log.info("🚀 买入信号：ML看涨 {}", String.format("%.2f", mlPrediction));
            return Signal.BUY;
        }
        
        // 卖出条件（大幅降低阈值）：
        // ML < 0.45（从0.25升到0.45）
        // 置信度 > 0.5
        if (mlPrediction < 0.45 && confidence > 0.5) {
            log.info("📉 卖出信号：ML看跌 {}", String.format("%.2f", mlPrediction));
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * 策略9: 综合简化版（推荐用于快速测试）
     * 
     * 结合多个指标但降低所有阈值
     * 平衡交易频率和信号质量
     * 交易频率：中高（每天5-15次）
     */
    public Signal balancedAggressiveStrategy(String symbol) {
        log.info("🔥 执行综合简化策略");
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        if (klines == null || klines.size() < 20) {
            return Signal.HOLD;
        }
        
        // 获取所有指标
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        double mlPrediction = mlPredictionService.predictMarketDirection(klines);
        
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal price5 = klines.get(5).getClosePrice();
        BigDecimal price15 = klines.get(Math.min(3, klines.size() - 1)).getClosePrice();
        BigDecimal momentum = currentPrice.subtract(price5);
        
        // 计算EMA判断趋势方向
        BigDecimal ema20 = indicatorService.calculateEMA(klines, 20);
        BigDecimal ema50 = indicatorService.calculateEMA(klines, 50);
        boolean isUptrend = ema20.compareTo(ema50) > 0;
        boolean isDowntrend = ema20.compareTo(ema50) < 0;
        
        log.info(String.format("📊 Williams: %.2f, RSI: %.2f, ML: %.2f, 动量: %s, ADX: %.2f, ATR: %.2f", 
                williamsR.doubleValue(), rsi.doubleValue(), mlPrediction, momentum, adx.doubleValue(), atr.doubleValue()));
        log.info(String.format("📊 趋势: EMA20=%.2f, EMA50=%.2f, %s", 
                ema20.doubleValue(), ema50.doubleValue(), isUptrend ? "上涨趋势" : (isDowntrend ? "下跌趋势" : "震荡")));
        
        // 🔥 P0修复1：ADX过滤 - ADX < 20时不交易
        if (adx.compareTo(new BigDecimal("20")) < 0) {
            log.info("⛔ ADX={} < 20，震荡市场，暂停交易", adx.doubleValue());
            return Signal.HOLD;
        }
        
        // 🔥 P0修复2：高波动保护 - ATR > 6.0时停止交易
        if (atr.compareTo(new BigDecimal("6.0")) > 0) {
            log.warn("⛔ ATR={} > 6.0，高波动期，暂停交易", atr.doubleValue());
            return Signal.HOLD;
        }
        
        // 🔥 P0修复3：暴力行情识别 - 15分钟涨跌幅 > 0.8%时停止交易（从1.0%降低到0.8%）
        BigDecimal priceChange = currentPrice.subtract(price15)
                                            .divide(price15, 4, RoundingMode.HALF_UP)
                                            .multiply(new BigDecimal("100"));
        if (priceChange.abs().compareTo(new BigDecimal("0.8")) > 0) {
            log.warn("⛔ 暴力行情！15分钟变动: {}%, 暂停交易", priceChange);
            return Signal.HOLD;
        }
        
        // 🔥 P0修复4：强趋势中必须先判断趋势方向，禁用逆向信号
        if (adx.compareTo(new BigDecimal("25")) > 0) {
            if (isUptrend) {
                log.info("🔥 强趋势上涨（ADX={}，EMA20 {} > EMA50 {}），禁用SELL信号", 
                        adx.doubleValue(), ema20.doubleValue(), ema50.doubleValue());
                // 在上涨强趋势中，只允许BUY信号
                // 继续评分，但SELL评分会在后面被清零
            }
            if (isDowntrend) {
                log.info("🔥 强趋势下跌（ADX={}，EMA20 {} < EMA50 {}），禁用BUY信号", 
                        adx.doubleValue(), ema20.doubleValue(), ema50.doubleValue());
                // 在下跌强趋势中，只允许SELL信号
                // 继续评分，但BUY评分会在后面被清零
            }
        }
        
        int buyScore = 0;
        int sellScore = 0;
        
        // ADX趋势判断
        int requiredScore = 5;  // 默认门槛5分
        if (adx.compareTo(new BigDecimal("20")) >= 0 && adx.compareTo(new BigDecimal("25")) < 0) {
            // 弱趋势（ADX 20-25）：提高门槛到8分
            requiredScore = 8;
            log.info("⚠️ ADX={}, 弱趋势区间，提高评分要求至8分", adx.doubleValue());
        } else if (adx.compareTo(new BigDecimal("30")) > 0) {
            // 强趋势市场（ADX>30）：但要注意趋势方向！
            // 🔥 修复：只在顺势时加分
            if (isUptrend && momentum.compareTo(BigDecimal.ZERO) > 0) {
                buyScore += 2;
                log.info("🔥 ADX={}, 强上涨趋势 + 上涨动量 → BUY +2分", adx.doubleValue());
            }
            if (isDowntrend && momentum.compareTo(BigDecimal.ZERO) < 0) {
                sellScore += 2;
                log.info("🔥 ADX={}, 强下跌趋势 + 下跌动量 → SELL +2分", adx.doubleValue());
            }
        }
        
        // Williams评分（提高权重）
        if (williamsR.compareTo(new BigDecimal("-60")) < 0) buyScore += 3;  // 从2改为3
        if (williamsR.compareTo(new BigDecimal("-40")) > 0) sellScore += 3; // 从2改为3
        
        // RSI评分
        if (rsi.compareTo(new BigDecimal("45")) < 0) buyScore += 2;
        if (rsi.compareTo(new BigDecimal("55")) > 0) sellScore += 2;
        
        // ML评分（降低权重）
        if (mlPrediction > 0.52) buyScore += 2;  // 从3改为2
        if (mlPrediction < 0.48) sellScore += 2; // 从3改为2
        
        // 动量评分
        if (momentum.compareTo(BigDecimal.ZERO) > 0) buyScore += 1;
        if (momentum.compareTo(BigDecimal.ZERO) < 0) sellScore += 1;
        
        // ✨ K线形态评分（新增）
        String candlePattern = analyzeCandlePattern(klines);
        if ("BULLISH_ENGULFING".equals(candlePattern) || "HAMMER".equals(candlePattern) || "MORNING_STAR".equals(candlePattern)) {
            buyScore += 3;
            log.info("📊 K线形态: {} → 看涨信号 +3分", candlePattern);
        }
        if ("BEARISH_ENGULFING".equals(candlePattern) || "SHOOTING_STAR".equals(candlePattern) || "EVENING_STAR".equals(candlePattern)) {
            sellScore += 3;
            log.info("📊 K线形态: {} → 看跌信号 +3分", candlePattern);
        }
        if ("DOJI".equals(candlePattern)) {
            log.info("📊 K线形态: 十字星 → 观望");
        }
        
        log.info("📊 初步评分 - 买入: {}, 卖出: {}, 需要: {}分", buyScore, sellScore, requiredScore);
        
        // 🔥 P0修复：强趋势中禁用逆向信号（关键修复！）
        if (adx.compareTo(new BigDecimal("25")) > 0) {
            if (isUptrend) {
                if (sellScore > 0) {
                    log.warn("🚫 强上涨趋势（ADX={}），SELL评分{}被清零", adx.doubleValue(), sellScore);
                }
                sellScore = 0;  // 清零SELL评分
            }
            if (isDowntrend) {
                if (buyScore > 0) {
                    log.warn("🚫 强下跌趋势（ADX={}），BUY评分{}被清零", adx.doubleValue(), buyScore);
                }
                buyScore = 0;  // 清零BUY评分
            }
        }
        
        log.info("📊 最终评分 - 买入: {}, 卖出: {}, 需要: {}分", buyScore, sellScore, requiredScore);
        
        // 使用动态门槛
        if (buyScore >= requiredScore && buyScore > sellScore) {
            log.info("🚀 买入信号：综合评分{}", buyScore);
            return Signal.BUY;
        }
        
        if (sellScore >= requiredScore && sellScore > buyScore) {
            log.info("📉 卖出信号：综合评分{}", sellScore);
            return Signal.SELL;
        }
        
        return Signal.HOLD;
    }
    
    /**
     * ✨ K线形态分析（新增）
     * 
     * 识别常见的K线形态，提供更强的信号确认
     * 
     * @param klines K线列表（至少3根）
     * @return 形态名称
     */
    private String analyzeCandlePattern(List<Kline> klines) {
        if (klines == null || klines.size() < 3) {
            return "NONE";
        }
        
        Kline current = klines.get(0);  // 当前K线
        Kline prev1 = klines.get(1);    // 前1根K线
        Kline prev2 = klines.get(2);    // 前2根K线
        
        BigDecimal currentOpen = current.getOpenPrice();
        BigDecimal currentClose = current.getClosePrice();
        BigDecimal currentHigh = current.getHighPrice();
        BigDecimal currentLow = current.getLowPrice();
        
        BigDecimal prev1Open = prev1.getOpenPrice();
        BigDecimal prev1Close = prev1.getClosePrice();
        BigDecimal prev1High = prev1.getHighPrice();
        BigDecimal prev1Low = prev1.getLowPrice();
        
        BigDecimal prev2Open = prev2.getOpenPrice();
        BigDecimal prev2Close = prev2.getClosePrice();
        
        // 计算实体和影线
        BigDecimal currentBody = currentClose.subtract(currentOpen).abs();
        BigDecimal currentRange = currentHigh.subtract(currentLow);
        BigDecimal prev1Body = prev1Close.subtract(prev1Open).abs();
        
        // 1. 十字星（Doji）- 实体很小，表示犹豫不决
        if (currentBody.compareTo(currentRange.multiply(new BigDecimal("0.1"))) < 0) {
            return "DOJI";
        }
        
        // 2. 看涨吞没（Bullish Engulfing）- 强烈看涨信号
        boolean isBullishEngulfing = 
            prev1Close.compareTo(prev1Open) < 0 &&  // 前一根是阴线
            currentClose.compareTo(currentOpen) > 0 &&  // 当前是阳线
            currentOpen.compareTo(prev1Close) <= 0 &&  // 当前开盘≤前收盘
            currentClose.compareTo(prev1Open) >= 0;    // 当前收盘≥前开盘
        
        if (isBullishEngulfing) {
            return "BULLISH_ENGULFING";
        }
        
        // 3. 看跌吞没（Bearish Engulfing）- 强烈看跌信号
        boolean isBearishEngulfing = 
            prev1Close.compareTo(prev1Open) > 0 &&  // 前一根是阳线
            currentClose.compareTo(currentOpen) < 0 &&  // 当前是阴线
            currentOpen.compareTo(prev1Close) >= 0 &&  // 当前开盘≥前收盘
            currentClose.compareTo(prev1Open) <= 0;    // 当前收盘≤前开盘
        
        if (isBearishEngulfing) {
            return "BEARISH_ENGULFING";
        }
        
        // 4. 锤子线（Hammer）- 底部反转信号
        BigDecimal lowerShadow = currentOpen.min(currentClose).subtract(currentLow);
        BigDecimal upperShadow = currentHigh.subtract(currentOpen.max(currentClose));
        
        boolean isHammer = 
            currentClose.compareTo(currentOpen) > 0 &&  // 阳线
            lowerShadow.compareTo(currentBody.multiply(new BigDecimal("2"))) > 0 &&  // 下影线>实体2倍
            upperShadow.compareTo(currentBody.multiply(new BigDecimal("0.3"))) < 0;  // 上影线<实体30%
        
        if (isHammer && prev1Close.compareTo(prev1Open) < 0) {  // 前一根是阴线
            return "HAMMER";
        }
        
        // 5. 射击之星（Shooting Star）- 顶部反转信号
        boolean isShootingStar = 
            currentClose.compareTo(currentOpen) < 0 &&  // 阴线
            upperShadow.compareTo(currentBody.multiply(new BigDecimal("2"))) > 0 &&  // 上影线>实体2倍
            lowerShadow.compareTo(currentBody.multiply(new BigDecimal("0.3"))) < 0;  // 下影线<实体30%
        
        if (isShootingStar && prev1Close.compareTo(prev1Open) > 0) {  // 前一根是阳线
            return "SHOOTING_STAR";
        }
        
        // 6. 早晨之星（Morning Star）- 三根K线看涨反转
        boolean isMorningStar = 
            prev2Close.compareTo(prev2Open) < 0 &&  // 第1根：大阴线
            prev1Body.compareTo(prev2Close.subtract(prev2Open).abs().multiply(new BigDecimal("0.3"))) < 0 &&  // 第2根：小实体
            currentClose.compareTo(currentOpen) > 0 &&  // 第3根：阳线
            currentClose.compareTo(prev2Open.add(prev2Close).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP)) > 0;  // 收盘在第1根中部以上
        
        if (isMorningStar) {
            return "MORNING_STAR";
        }
        
        // 7. 黄昏之星（Evening Star）- 三根K线看跌反转
        boolean isEveningStar = 
            prev2Close.compareTo(prev2Open) > 0 &&  // 第1根：大阳线
            prev1Body.compareTo(prev2Close.subtract(prev2Open).abs().multiply(new BigDecimal("0.3"))) < 0 &&  // 第2根：小实体
            currentClose.compareTo(currentOpen) < 0 &&  // 第3根：阴线
            currentClose.compareTo(prev2Open.add(prev2Close).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP)) < 0;  // 收盘在第1根中部以下
        
        if (isEveningStar) {
            return "EVENING_STAR";
        }
        
        // 8. 启明星（Piercing Pattern）- 看涨穿刺
        boolean isPiercing = 
            prev1Close.compareTo(prev1Open) < 0 &&  // 前一根是阴线
            currentClose.compareTo(currentOpen) > 0 &&  // 当前是阳线
            currentOpen.compareTo(prev1Low) < 0 &&  // 跳空低开
            currentClose.compareTo(prev1Open.add(prev1Close).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP)) > 0 &&  // 收盘在前一根中部以上
            currentClose.compareTo(prev1Open) < 0;  // 但未完全吞没
        
        if (isPiercing) {
            return "HAMMER";  // 归类为锤子线
        }
        
        // 9. 乌云盖顶（Dark Cloud Cover）- 看跌覆盖
        boolean isDarkCloud = 
            prev1Close.compareTo(prev1Open) > 0 &&  // 前一根是阳线
            currentClose.compareTo(currentOpen) < 0 &&  // 当前是阴线
            currentOpen.compareTo(prev1High) > 0 &&  // 跳空高开
            currentClose.compareTo(prev1Open.add(prev1Close).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP)) < 0 &&  // 收盘在前一根中部以下
            currentClose.compareTo(prev1Open) > 0;  // 但未完全吞没
        
        if (isDarkCloud) {
            return "SHOOTING_STAR";  // 归类为射击之星
        }
        
        return "NONE";
    }
}
