package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.indicator.BollingerBands;
import com.ltp.peter.augtrade.indicator.CandlePattern;
import com.ltp.peter.augtrade.indicator.EMACalculator;
import com.ltp.peter.augtrade.ml.MLPrediction;
import com.ltp.peter.augtrade.ml.MLPredictionEnhancedService;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 组合策略
 * 
 * 整合多个子策略的信号，通过加权投票生成最终交易信号：
 * - 收集所有子策略的信号
 * - 根据策略权重计算做多/做空得分
 * - 得分最高且达到阈值时生成信号
 * 
 * 决策规则：
 * - 做多得分 >= 15 且 > 做空得分：生成做多信号
 * - 做空得分 >= 15 且 > 做多得分：生成做空信号
 * - 其他情况：观望
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class CompositeStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "Composite";
    private static final int STRATEGY_WEIGHT = 10;
    private static final int SIGNAL_THRESHOLD = 6; // 🔥 降低阈值：从15→6，允许单个策略（如BollingerBreakout权重6）触发信号
    
    @Autowired(required = false)
    private List<Strategy> strategies;
    
    @Autowired(required = false)
    private MLPredictionEnhancedService mlPredictionService;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足", 0, 0);
        }
        
        if (strategies == null || strategies.isEmpty()) {
            log.warn("[{}] 没有可用的子策略", STRATEGY_NAME);
            return createHoldSignal("无子策略", 0, 0);
        }
        
        try {
            int buyScore = 0;
            int sellScore = 0;
            List<String> buyReasons = new ArrayList<>();
            List<String> sellReasons = new ArrayList<>();
            
            // 过滤掉自己，避免递归调用
            List<Strategy> activeStrategies = strategies.stream()
                    .filter(s -> !s.getName().equals(STRATEGY_NAME))
                    .filter(Strategy::isEnabled)
                    .collect(Collectors.toList());
            
            log.debug("[{}] 共有 {} 个活跃策略", STRATEGY_NAME, activeStrategies.size());
            
            // 收集所有子策略的信号
            for (Strategy strategy : activeStrategies) {
                try {
                    TradingSignal signal = strategy.generateSignal(context);
                    
                    if (signal == null || signal.isHold()) {
                        log.debug("[{}] {} 策略返回观望信号", STRATEGY_NAME, strategy.getName());
                        continue;
                    }
                    
                    int weight = strategy.getWeight();
                    
                    if (signal.isBuy()) {
                        buyScore += weight;
                        buyReasons.add(String.format("%s(权重%d)", strategy.getName(), weight));
                        log.debug("[{}] {} 策略建议做多，权重: {}", STRATEGY_NAME, strategy.getName(), weight);
                    } else if (signal.isSell()) {
                        sellScore += weight;
                        sellReasons.add(String.format("%s(权重%d)", strategy.getName(), weight));
                        log.debug("[{}] {} 策略建议做空，权重: {}", STRATEGY_NAME, strategy.getName(), weight);
                    }
                    
                } catch (Exception e) {
                    log.error("[{}] {} 策略执行失败", STRATEGY_NAME, strategy.getName(), e);
                }
            }
            
            log.info("[{}] 综合评分 - 做多: {}, 做空: {}", STRATEGY_NAME, buyScore, sellScore);
            
            // 🔥 优化顺序-20260126: 先加权K线形态，再进行ADX过滤
            Double adx = context.getIndicator("ADX");
            CandlePattern pattern = context.getIndicator("CandlePattern");
            
            // 🔥 Step 1：K线形态加权（实时价格行为优先）
            if (pattern != null && pattern.hasPattern()) {
                int patternScore = pattern.getStrength(); // 强度8-10
                
                if (pattern.getDirection() == CandlePattern.Direction.BULLISH) {
                    buyScore += patternScore;
                    buyReasons.add(String.format("K线形态:%s(强度%d)", 
                            pattern.getType().name(), patternScore));
                    log.info("[{}] 🎯 K线看涨形态：{}，权重：{}, 新评分：{}", 
                            STRATEGY_NAME, pattern.getDescription(), patternScore, buyScore);
                } else if (pattern.getDirection() == CandlePattern.Direction.BEARISH) {
                    sellScore += patternScore;
                    sellReasons.add(String.format("K线形态:%s(强度%d)", 
                            pattern.getType().name(), patternScore));
                    log.info("[{}] 🎯 K线看跌形态：{}，权重：{}, 新评分：{}", 
                            STRATEGY_NAME, pattern.getDescription(), patternScore, sellScore);
                }
                
                log.info("[{}] 📊 K线形态加权后 - 做多: {}, 做空: {}", STRATEGY_NAME, buyScore, sellScore);
            }
            
            // 🔥 Step 2：ADX过滤（2026-01-28调整）
            // ⚠️ 重要：历史数据的ADX可能是错误的（用DX而非ADX计算），所以之前的ADX≥30门槛过于严格
            // 调整策略：
            // - 正常情况：ADX≥18（真实ADX的合理门槛）
            // - 强K线形态：ADX≥12（给予更多灵活性）
            if (adx != null) {
                // 🎯 K线形态强烈时，降低ADX门槛
                boolean hasStrongPattern = pattern != null && pattern.getStrength() >= 8;
                double adxThreshold = hasStrongPattern ? 12.0 : 18.0;
                
                if (adx < adxThreshold) {
                    if (hasStrongPattern) {
                        log.warn("[{}] ⚠️ 虽有强烈K线形态，但ADX={} < 12（趋势过弱），需观望", 
                                STRATEGY_NAME, String.format("%.2f", adx));
                    } else {
                        log.error("[{}] ❌ ADX过滤！ADX={} < 18（弱趋势/震荡期，避免无效交易）", 
                                STRATEGY_NAME, String.format("%.2f", adx));
                    }
                    log.error("[{}] 📊 当前评分 - 做多:{}, 做空:{} 被拒绝", 
                            STRATEGY_NAME, buyScore, sellScore);
                    return createHoldSignal(String.format("❌ ADX=%.2f < %.0f（需要明确趋势）", adx, adxThreshold), 
                            buyScore, sellScore);
                }
                
                log.info("[{}] ✅ 趋势确认(ADX={}≥{}),使用正常阈值(做多:{}, 做空:{})", 
                        STRATEGY_NAME, String.format("%.2f", adx), 
                        hasStrongPattern ? "12" : "18", buyScore, sellScore);
            } else {
                log.warn("[{}] ⚠️ ADX数据缺失，暂停交易以确保安全", STRATEGY_NAME);
                return createHoldSignal("ADX数据缺失，暂停交易", buyScore, sellScore);
            }
            
            // 根据得分生成信号
            if (buyScore >= SIGNAL_THRESHOLD && buyScore > sellScore) {
                // 🔥 ML震荡过滤器 - 临时禁用 (2026-01-29)
                // 原因：ML模型98.74%置信度将ADX=56.4的强趋势误判为震荡，导致无法开单
                // TODO: 需要重新评估ML模型或调整过滤逻辑
                /*
                if (mlPredictionService != null) {
                    try {
                        MLPrediction mlPred = mlPredictionService.predict(context);
                        
                        if (mlPred != null) {
                            // 场景1: ML高置信度识别为震荡（精确率94%！）
                            if (mlPred.isHighConfidenceRanging()) {
                                log.warn("[{}] ❌ ML识别为震荡期 (置信度:{}%，精确率94%)，拒绝开仓", 
                                        STRATEGY_NAME, String.format("%.2f", mlPred.getProbHold() * 100));
                                return createHoldSignal(
                                        String.format("ML识别为震荡期(%.1f%%)，避免无效交易", 
                                                mlPred.getProbHold() * 100),
                                        buyScore, sellScore);
                            }
                            
                            // 场景2: ML预测下跌
                            if (mlPred.isHighConfidenceDown()) {
                                log.warn("[{}] ⚠️ ML预测下跌 (概率:{}%)，拒绝做多", 
                                        STRATEGY_NAME, String.format("%.2f", mlPred.getProbDown() * 100));
                                return createHoldSignal(
                                        String.format("ML预测下跌(%.1f%%)，避免逆势", 
                                                mlPred.getProbDown() * 100),
                                        buyScore, sellScore);
                            }
                            
                            // 场景3: ML倾向震荡但不确定，降低仓位
                            if (mlPred.isTrendingRanging()) {
                                log.info("[{}] ⚠️ ML倾向震荡 (概率:{}%)，保守处理", 
                                        STRATEGY_NAME, String.format("%.2f", mlPred.getProbHold() * 100));
                                buyScore = (int)(buyScore * 0.7);  // 降低评分
                                log.info("[{}] 📊 ML调整后评分: {}", STRATEGY_NAME, buyScore);
                            }
                            
                            // 场景4: ML确认上涨（虽然精确率只有14%，但可以作为参考）
                            if (mlPred.isHighConfidenceUp()) {
                                log.info("[{}] ✅ ML高置信度支持做多 (概率:{}%)", 
                                        STRATEGY_NAME, String.format("%.2f", mlPred.getProbUp() * 100));
                            }
                        }
                    } catch (Exception e) {
                        log.error("[{}] ML预测异常，继续使用传统策略", STRATEGY_NAME, e);
                    }
                }
                */
                log.info("[{}] ℹ️ ML过滤器已禁用，使用传统技术指标策略", STRATEGY_NAME);
                
                TradingSignal buySignal = TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(calculateSignalStrength(buyScore, sellScore))
                        .score(buyScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做多 (得分:%d, ADX:%.1f) [%s]", 
                                buyScore, adx, String.join(", ", buyReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
                
                // 价格位置过滤
                if (!validatePricePosition(context, buySignal)) {
                    return createHoldSignal("价格位置不合理,做多信号被过滤", buyScore, sellScore);
                }
                
                // K线形态检查
                if (!validateCandlePattern(context, TradingSignal.SignalType.BUY)) {
                    return createHoldSignal("K线形态不支持做多", buyScore, sellScore);
                }
                
                Double williamsR = context.getIndicator("WilliamsR");
                log.info("[{}] 🚀 生成做多信号 - ADX:{}, Williams R:{}, 强度:{}", 
                        STRATEGY_NAME, String.format("%.2f", adx), 
                        williamsR != null ? String.format("%.2f", williamsR) : "N/A",
                        buySignal.getStrength());
                
                return buySignal;
            }
            
            if (sellScore >= SIGNAL_THRESHOLD && sellScore > buyScore) {
                Double williamsR = context.getIndicator("WilliamsR");
                
                // 🔥 P0修复-20260126: 做空严格限制（数据显示做空整体亏损）
                // 数据：做多+$1,342 vs 做空-$555，差距$1,897
                // 只在WR -60~-20区间 + 超强信号时做空
                if (williamsR != null) {
                    if (williamsR < -60.0) {
                        log.warn("[{}] 🚫 做空被拒绝：WR={}过于超卖（数据显示做空在超卖时平均亏损）", 
                                STRATEGY_NAME, String.format("%.2f", williamsR));
                        return createHoldSignal(String.format("WR=%.2f不适合做空", williamsR), 
                                buyScore, sellScore);
                    } else if (williamsR > -20.0) {
                        log.warn("[{}] 🚫 做空被拒绝：WR={}不够超买（需要-60~-20区间）", 
                                STRATEGY_NAME, String.format("%.2f", williamsR));
                        return createHoldSignal(String.format("WR=%.2f做空需在-60~-20区间", williamsR), 
                                buyScore, sellScore);
                    }
                    log.info("[{}] ⚡ WR={}在做空安全区间-60~-20", 
                            STRATEGY_NAME, String.format("%.2f", williamsR));
                }
                
                // 🔥 P2修复-20260213: 做空门槛从25降至12（需至少2个策略共识）
                // 数据分析: ADX≥30+EMA死叉+SELL = 100%胜率, ADX≥40+SELL = 100%胜率
                // 做空策略得分可能:
                //   Supertrend(8) + VWAP(5) = 13 ✅
                //   Supertrend(8) + TrendFilter(12) = 20 ✅
                //   TrendFilter(12) + VWAP(5) = 17 ✅
                // 单策略不能触发做空(Supertrend=8<12, VWAP=5<12)，保证安全
                int shortStrengthThreshold = 12;
                if (sellScore < shortStrengthThreshold) {
                    log.warn("[{}] 🚫 做空信号强度{}不足（需要≥{}），做空需要多策略共识", 
                            STRATEGY_NAME, sellScore, shortStrengthThreshold);
                    return createHoldSignal(String.format("做空需要多策略共识≥%d分（当前%d分）", shortStrengthThreshold, sellScore), 
                            buyScore, sellScore);
                }
                
                TradingSignal sellSignal = TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(calculateSignalStrength(sellScore, buyScore))
                        .score(sellScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做空 (得分:%d, ADX:%.1f) [%s]", 
                                sellScore, adx, String.join(", ", sellReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
                
                // 价格位置过滤
                if (!validatePricePosition(context, sellSignal)) {
                    return createHoldSignal("价格位置不合理,做空信号被过滤", buyScore, sellScore);
                }
                
                // K线形态检查
                if (!validateCandlePattern(context, TradingSignal.SignalType.SELL)) {
                    return createHoldSignal("K线形态不支持做空", buyScore, sellScore);
                }
                
                log.info("[{}] 📉 生成做空信号 - ADX:{}, Williams R:{}, 强度:{}", 
                        STRATEGY_NAME, String.format("%.2f", adx), 
                        williamsR != null ? String.format("%.2f", williamsR) : "N/A",
                        sellSignal.getStrength());
                
                return sellSignal;
            }
            
            // 得分不足或冲突，观望
            String reason;
            if (buyScore == 0 && sellScore == 0) {
                reason = "所有策略均为中性";
            } else if (buyScore < SIGNAL_THRESHOLD && sellScore < SIGNAL_THRESHOLD) {
                reason = String.format("信号强度不足 (做多:%d, 做空:%d, 阈值:%d)", 
                        buyScore, sellScore, SIGNAL_THRESHOLD);
            } else {
                reason = String.format("信号冲突 (做多:%d, 做空:%d)", buyScore, sellScore);
            }
            
            return createHoldSignal(reason, buyScore, sellScore);
            
        } catch (Exception e) {
            log.error("[{}] 生成交易信号时发生错误", STRATEGY_NAME, e);
            return createHoldSignal("策略执行异常", 0, 0);
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
        return "组合策略 - 整合多个子策略信号的加权投票系统";
    }
    
    /**
     * 计算信号强度
     * 🔥 P0修复-20260209: 重新校准信号强度计算
     * 
     * 问题：原算法 dominantScore * 3 导致信号强度与盈利负相关
     *   - 信号强度100的订单平均亏$50.5
     *   - 信号强度24的订单平均赚$118.25
     * 
     * 修复：
     *   - 降低倍数（×3→×2），避免高分策略单独就产生过强信号
     *   - 降低上限（80→60），让信号强度更加分散
     *   - 增加得分差距奖励，鼓励多策略一致性
     */
    private int calculateSignalStrength(int dominantScore, int oppositeScore) {
        // 得分差距
        int scoreDiff = dominantScore - oppositeScore;
        
        // 🔥 基础强度降低：从×3改为×2，从80改为60
        // 这确保单个高权重策略（如TrendFilter=12）不会产生过强信号(12*2=24而非12*3=36)
        int baseStrength = Math.min(dominantScore * 2, 60);
        
        // 🔥 增加一致性奖励：多策略同时给出信号时，额外加分
        // 得分差距越大说明方向越一致
        int bonusStrength = Math.min(scoreDiff * 2, 40);
        
        return Math.min(baseStrength + bonusStrength, 100);
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason, int buyScore, int sellScore) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
    
    /**
     * 获取活跃的子策略列表
     */
    public List<Strategy> getActiveStrategies() {
        if (strategies == null) {
            return new ArrayList<>();
        }
        
        return strategies.stream()
                .filter(s -> !s.getName().equals(STRATEGY_NAME))
                .filter(Strategy::isEnabled)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取子策略总权重
     */
    public int getTotalWeight() {
        return getActiveStrategies().stream()
                .mapToInt(Strategy::getWeight)
                .sum();
    }
    
    /**
     * 🔥 P1修复-20260213: 验证价格位置是否合理（动态布林带过滤）
     * 
     * 问题回顾(20260209): 0%容忍度导致强趋势行情中全天无法开单
     * - 2月13日黄金价格$4950~4963，布林上轨$4920~4954，价格全天高于上轨
     * - 布林带基于20根K线(100分钟)计算，严重滞后于突破性上涨
     * - 策略有做多评分12~19分（远超阈值6分），但全部被价格位置过滤拦截
     * 
     * 修复策略：根据ADX和EMA趋势动态调整布林带上轨容忍度
     * - 强趋势(ADX≥30) + EMA金叉确认: 允许突破上轨1.0%（趋势延续概率高）
     * - 中等趋势(ADX≥25) + EMA金叉确认: 允许突破上轨0.5%
     * - 弱趋势/震荡: 保持0%容忍度（原逻辑，防止追高）
     * 
     * 核心原则：布林带滞后于趋势 → 用ADX+EMA判断趋势强度 → 动态放宽容忍度
     */
    private boolean validatePricePosition(MarketContext context, TradingSignal signal) {
        if (signal.getType() == TradingSignal.SignalType.HOLD) {
            return true;
        }
        
        BollingerBands bb = context.getIndicator("BollingerBands");
        BigDecimal price = context.getCurrentPrice();
        Double adx = context.getIndicator("ADX");
        EMACalculator.EMATrend emaTrend = context.getIndicator("EMATrend");
        
        // 如果没有布林带,使用EMA判断
        if (bb == null) {
            if (emaTrend == null) {
                log.warn("[{}] 无布林带和EMA数据,跳过价格位置检查", STRATEGY_NAME);
                return true;
            }
            
            double priceVal = price.doubleValue();
            double emaShort = emaTrend.getEmaShort();  // 使用emaShort (EMA20)
            
            // 🔥 20260213: EMA容忍度也根据ADX动态调整
            double emaTolerance = calculateEmaTolerance(adx, emaTrend);
            
            if (signal.getType() == TradingSignal.SignalType.BUY) {
                if (priceVal > emaShort * (1 + emaTolerance)) {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{}高于EMA20 {}超过{}%（追高保护）", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), 
                            String.format("%.2f", emaShort),
                            String.format("%.1f", emaTolerance * 100));
                    return false;
                }
            } else if (signal.getType() == TradingSignal.SignalType.SELL) {
                if (priceVal < emaShort * (1 - emaTolerance)) {
                    log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于EMA20 {}超过{}%", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), 
                            String.format("%.2f", emaShort),
                            String.format("%.1f", emaTolerance * 100));
                    return false;
                }
            }
            return true;
        }
        
        // 有布林带,根据趋势强度动态检查
        Double upper = bb.getUpper();
        Double lower = bb.getLower();
        Double middle = bb.getMiddle();
        double priceVal = price.doubleValue();
        
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            // 🔥 P1修复-20260213：动态布林带上轨容忍度
            double bbTolerance = calculateBBTolerance(adx, emaTrend, true);
            double adjustedUpper = upper * (1 + bbTolerance);
            
            if (priceVal > adjustedUpper) {
                double exceedsPercent = (priceVal - upper) / upper * 100;
                if (bbTolerance > 0) {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{} > 调整后上轨{}（原上轨{}, 容忍度{}%, 实际超出{:.2f}%）", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), 
                            String.format("%.2f", adjustedUpper),
                            String.format("%.2f", upper), 
                            String.format("%.1f", bbTolerance * 100),
                            exceedsPercent);
                } else {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{} > 布林上轨{}（超出{:.2f}%），禁止追高！", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), 
                            String.format("%.2f", upper), exceedsPercent);
                }
                return false;
            }
            
            // 如果价格在原始上轨之上但在容忍范围内，记录趋势突破日志
            if (priceVal > upper && priceVal <= adjustedUpper) {
                double exceedsPercent = (priceVal - upper) / upper * 100;
                log.info("[{}] 🔥 趋势突破放行: 价格{} > 原上轨{}（超出{:.2f}%），ADX={}, EMA趋势={}，容忍度{}%内放行", 
                        STRATEGY_NAME, String.format("%.2f", priceVal), 
                        String.format("%.2f", upper), exceedsPercent,
                        adx != null ? String.format("%.1f", adx) : "N/A",
                        emaTrend != null ? emaTrend.getTrendDescription() : "N/A",
                        String.format("%.1f", bbTolerance * 100));
            }
            
            // 价格远离中轨时记录警告（仅在布林带内时检查）
            if (priceVal <= upper) {
                double distFromMiddle = (priceVal - middle) / (upper - middle);
                if (distFromMiddle > 0.8) {
                    log.warn("[{}] ⚠️ BUY信号警告: 价格{}偏近上轨（中轨距离{:.0f}%），信号质量降低", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), distFromMiddle * 100);
                }
            }
            
        } else if (signal.getType() == TradingSignal.SignalType.SELL) {
            // 做空: 动态布林带下轨容忍度（对称逻辑）
            double bbTolerance = calculateBBTolerance(adx, emaTrend, false);
            double adjustedLower = lower * (1 - bbTolerance);
            
            if (priceVal < adjustedLower) {
                double below = lower - priceVal;
                log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于调整后下轨{} (-{} USD)", 
                        STRATEGY_NAME, 
                        String.format("%.2f", priceVal), 
                        String.format("%.2f", adjustedLower), 
                        String.format("%.2f", below));
                return false;
            }
            
            if (priceVal < lower && priceVal >= adjustedLower) {
                log.info("[{}] 🔥 趋势突破放行(空): 价格{} < 原下轨{}，ADX={}, 容忍度{}%内放行", 
                        STRATEGY_NAME, String.format("%.2f", priceVal), 
                        String.format("%.2f", lower),
                        adx != null ? String.format("%.1f", adx) : "N/A",
                        String.format("%.1f", bbTolerance * 100));
            }
        }
        
        log.debug("[{}] ✅ 价格位置检查通过: 价格{} 在布林带[{}, {}]内（ADX={}, 趋势={}）", 
                STRATEGY_NAME, 
                String.format("%.2f", priceVal), 
                String.format("%.2f", lower), 
                String.format("%.2f", upper),
                adx != null ? String.format("%.1f", adx) : "N/A",
                emaTrend != null ? emaTrend.getTrendDescription() : "N/A");
        return true;
    }
    
    /**
     * 🔥 新增-20260213: 计算布林带容忍度
     * 
     * 根据ADX强度和EMA趋势方向，动态计算允许突破布林带的百分比
     * 核心思想：强趋势行情中布林带会滞后，需要给予更多空间
     * 
     * @param adx ADX值
     * @param emaTrend EMA趋势
     * @param isBuy 是否做多（做多检查上轨，做空检查下轨）
     * @return 容忍百分比（0.0 = 0%, 0.01 = 1%）
     */
    private double calculateBBTolerance(Double adx, EMACalculator.EMATrend emaTrend, boolean isBuy) {
        if (adx == null) {
            return 0.0; // 无ADX数据，不放宽
        }
        
        // 检查EMA趋势是否与交易方向一致
        boolean emaConfirmed = false;
        if (emaTrend != null) {
            if (isBuy && emaTrend.isUpTrend()) {
                emaConfirmed = true; // 做多 + EMA金叉 = 趋势确认
            } else if (!isBuy && emaTrend.isDownTrend()) {
                emaConfirmed = true; // 做空 + EMA死叉 = 趋势确认
            }
        }
        
        if (!emaConfirmed) {
            // EMA不确认趋势方向，不放宽（保持原始0%容忍度）
            log.debug("[{}] BB容忍度: 0%（EMA趋势未确认{}方向）", 
                    STRATEGY_NAME, isBuy ? "做多" : "做空");
            return 0.0;
        }
        
        // ADX >= 30: 强趋势，布林带滞后严重，容忍1.0%
        if (adx >= 30) {
            log.info("[{}] 📈 BB容忍度: 1.0%（强趋势ADX={}, EMA确认{}）", 
                    STRATEGY_NAME, String.format("%.1f", adx), isBuy ? "上涨" : "下跌");
            return 0.010;
        }
        
        // ADX >= 25: 中等趋势，容忍0.5%
        if (adx >= 25) {
            log.info("[{}] 📊 BB容忍度: 0.5%（中等趋势ADX={}, EMA确认{}）", 
                    STRATEGY_NAME, String.format("%.1f", adx), isBuy ? "上涨" : "下跌");
            return 0.005;
        }
        
        // ADX < 25: 弱趋势/震荡，保持严格（0%容忍度）
        log.debug("[{}] BB容忍度: 0%（弱趋势ADX={}）", STRATEGY_NAME, String.format("%.1f", adx));
        return 0.0;
    }
    
    /**
     * 🔥 新增-20260213: 计算EMA容忍度
     * 当无布林带时使用，逻辑与BB容忍度类似
     */
    private double calculateEmaTolerance(Double adx, EMACalculator.EMATrend emaTrend) {
        // 基础容忍度0.3%
        double baseTolerance = 0.003;
        
        if (adx == null || emaTrend == null) {
            return baseTolerance;
        }
        
        // 强趋势 + EMA确认: 放宽到0.8%
        if (adx >= 30 && (emaTrend.isUpTrend() || emaTrend.isDownTrend())) {
            return 0.008;
        }
        
        // 中等趋势 + EMA确认: 放宽到0.5%
        if (adx >= 25 && (emaTrend.isUpTrend() || emaTrend.isDownTrend())) {
            return 0.005;
        }
        
        return baseTolerance;
    }
    
    /**
     * 🔥 P0修复: 验证K线形态是否支持交易
     * 
     * 规则:
     * - DOJI等不确定形态: 不交易
     * - 形态方向与信号方向相反: 不交易
     */
    private boolean validateCandlePattern(MarketContext context, TradingSignal.SignalType signalType) {
        CandlePattern pattern = context.getIndicator("CandlePattern");
        if (pattern == null || !pattern.hasPattern()) {
            return true; // 无形态,不限制
        }
        
        CandlePattern.Direction pDir = pattern.getDirection();
        
        // DOJI等不确定形态: 不交易
        if (pDir == CandlePattern.Direction.NEUTRAL) {
            log.warn("[{}] ⏸️ 不确定K线形态{},暂停交易", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        // 方向相反: 不交易
        if (signalType == TradingSignal.SignalType.BUY && 
            pDir == CandlePattern.Direction.BEARISH) {
            log.warn("[{}] ⛔ BUY信号与看跌形态{}矛盾", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        if (signalType == TradingSignal.SignalType.SELL && 
            pDir == CandlePattern.Direction.BULLISH) {
            log.warn("[{}] ⛔ SELL信号与看涨形态{}矛盾", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        log.debug("[{}] ✅ K线形态检查通过: {}方向{}", 
                STRATEGY_NAME, pattern.getType().getDescription(), pDir.getDescription());
        return true;
    }
}
