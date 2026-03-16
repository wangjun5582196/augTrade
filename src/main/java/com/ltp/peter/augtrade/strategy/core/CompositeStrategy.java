package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.*;
import com.ltp.peter.augtrade.ml.MLPrediction;
import com.ltp.peter.augtrade.ml.MLPredictionEnhancedService;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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
    private static final int SIGNAL_THRESHOLD = 10; // 修复：从6提高到10，防止单个低权重策略独立触发信号（需至少2个策略共识）
    
    @Autowired(required = false)
    private List<Strategy> strategies;
    
    @Autowired(required = false)
    private MLPredictionEnhancedService mlPredictionService;
    
    // 🔥 新增-20260309: 4个新指标计算器
    @Autowired(required = false)
    private MomentumCalculator momentumCalculator;
    
    @Autowired(required = false)
    private VolumeBreakoutCalculator volumeBreakoutCalculator;
    
    @Autowired(required = false)
    private SwingPointCalculator swingPointCalculator;
    
    @Autowired(required = false)
    private HMACalculator hmaCalculator;
    
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
                    log.info("[{}] 🔥 K线看跌形态：{}，权重：{}, 新评分：{}", 
                            STRATEGY_NAME, pattern.getDescription(), patternScore, sellScore);
                }
                
                log.info("[{}] 📊 K线形态加权后 - 做多: {}, 做空: {}", STRATEGY_NAME, buyScore, sellScore);
            }
            
            // 🔥 新增-20260309: Step 1.5：新指标加权（优化版方案C）
            IndicatorScoreResult indicatorScore = calculateNewIndicatorsScore(context);
            if (indicatorScore != null) {
                buyScore += indicatorScore.getBuyScore();
                sellScore += indicatorScore.getSellScore();
                buyReasons.addAll(indicatorScore.getBuyReasons());
                sellReasons.addAll(indicatorScore.getSellReasons());
                
                log.info("[{}] 📊 新指标加权后 - 做多: {} (+{}), 做空: {} (+{})", 
                        STRATEGY_NAME, buyScore, indicatorScore.getBuyScore(), 
                        sellScore, indicatorScore.getSellScore());
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
                // TODO: ML震荡过滤器待重新接入（模型需重训以修复强趋势误判问题）
                // 相关类：MLPredictionEnhancedService，重训后取消此TODO并恢复过滤逻辑

                // 🔥 P1新增-20260316: 宏观趋势过滤器（做多信号检查）
                // 当多时间框架显示明确下跌趋势时，拒绝做多（防止在大跌中抄底）
                String macroTrend = context.getIndicator("MacroTrend");
                Double macroPriceChange = context.getIndicator("MacroPriceChange");
                if ("MACRO_DOWN".equals(macroTrend) && macroPriceChange != null && macroPriceChange < -0.5) {
                    log.warn("[{}] ⚠️ 宏观下跌趋势，做多信号被否决 (5小时变化: {}%)",
                            STRATEGY_NAME, String.format("%.2f", macroPriceChange));
                    return createHoldSignal(String.format("宏观下跌趋势(%.2f%%)，拒绝做多", macroPriceChange),
                            buyScore, sellScore);
                }

                // HMA趋势过滤器（做多信号检查）
                HMACalculator.HMAResult hma = context.getIndicator("HMA");
                if (hma != null && "DOWN".equals(hma.getTrend())) {
                    log.warn("[{}] ⚠️ HMA下跌趋势，做多信号被否决 (HMA={}, 斜率={}%)",
                            STRATEGY_NAME, hma.getHma20(), String.format("%.4f", hma.getSlope() * 100));
                    return createHoldSignal("HMA趋势相反，拒绝做多", buyScore, sellScore);
                }
                
                // 🔥 新增-20260309: 填充新指标数据到TradingSignal
                TradingSignal.TradingSignalBuilder builder = TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(calculateSignalStrength(buyScore, sellScore))
                        .score(buyScore)
                        .buyScore(buyScore)
                        .sellScore(sellScore)
                        .buyReasons(buyReasons)
                        .sellReasons(sellReasons)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做多 (得分:%d, ADX:%.1f) [%s]", 
                                buyScore, adx, String.join(", ", buyReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .signalGenerateTime(LocalDateTime.now());
                
                // 填充新指标数据
                fillNewIndicatorData(builder, context);
                
                TradingSignal buySignal = builder.build();
                
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
                
                // 🔥 P1新增-20260316: 宏观趋势过滤器（做空信号检查）
                String macroTrendSell = context.getIndicator("MacroTrend");
                Double macroPriceChangeSell = context.getIndicator("MacroPriceChange");
                if ("MACRO_UP".equals(macroTrendSell) && macroPriceChangeSell != null && macroPriceChangeSell > 0.5) {
                    log.warn("[{}] ⚠️ 宏观上涨趋势，做空信号被否决 (5小时变化: +{}%)",
                            STRATEGY_NAME, String.format("%.2f", macroPriceChangeSell));
                    return createHoldSignal(String.format("宏观上涨趋势(+%.2f%%)，拒绝做空", macroPriceChangeSell),
                            buyScore, sellScore);
                }

                // 🔥 P1优化-20260310: HMA趋势过滤器（做空信号检查）
                HMACalculator.HMAResult hma = context.getIndicator("HMA");
                if (hma != null && "UP".equals(hma.getTrend())) {
                    log.warn("[{}] ⚠️ HMA上涨趋势，做空信号被否决 (HMA={}, 斜率={}%)",
                            STRATEGY_NAME, hma.getHma20(), String.format("%.4f", hma.getSlope() * 100));
                    return createHoldSignal("HMA趋势相反，拒绝做空", buyScore, sellScore);
                }
                
                // 🔥 新增-20260309: 填充新指标数据到TradingSignal
                TradingSignal.TradingSignalBuilder sellBuilder = TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(calculateSignalStrength(sellScore, buyScore))
                        .score(sellScore)
                        .buyScore(buyScore)
                        .sellScore(sellScore)
                        .buyReasons(buyReasons)
                        .sellReasons(sellReasons)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做空 (得分:%d, ADX:%.1f) [%s]", 
                                sellScore, adx, String.join(", ", sellReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .signalGenerateTime(LocalDateTime.now());
                
                // 填充新指标数据
                fillNewIndicatorData(sellBuilder, context);
                
                TradingSignal sellSignal = sellBuilder.build();
                
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
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{} > 调整后上轨{}（原上轨{}, 容忍度{}%, 实际超出{}%）",
                            STRATEGY_NAME, String.format("%.2f", priceVal),
                            String.format("%.2f", adjustedUpper),
                            String.format("%.2f", upper),
                            String.format("%.1f", bbTolerance * 100),
                            String.format("%.2f", exceedsPercent));
                } else {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{} > 布林上轨{}（超出{}%），禁止追高！",
                            STRATEGY_NAME, String.format("%.2f", priceVal),
                            String.format("%.2f", upper), String.format("%.2f", exceedsPercent));
                }
                return false;
            }
            
            // 如果价格在原始上轨之上但在容忍范围内，记录趋势突破日志
            if (priceVal > upper && priceVal <= adjustedUpper) {
                double exceedsPercent = (priceVal - upper) / upper * 100;
                log.info("[{}] 🔥 趋势突破放行: 价格{} > 原上轨{}（超出{}%），ADX={}, EMA趋势={}，容忍度{}%内放行",
                        STRATEGY_NAME, String.format("%.2f", priceVal),
                        String.format("%.2f", upper), String.format("%.2f", exceedsPercent),
                        adx != null ? String.format("%.1f", adx) : "N/A",
                        emaTrend != null ? emaTrend.getTrendDescription() : "N/A",
                        String.format("%.1f", bbTolerance * 100));
            }
            
            // 价格远离中轨时记录警告（仅在布林带内时检查）
            if (priceVal <= upper) {
                double distFromMiddle = (priceVal - middle) / (upper - middle);
                if (distFromMiddle > 0.8) {
                    log.warn("[{}] ⚠️ BUY信号警告: 价格{}偏近上轨（中轨距离{}%），信号质量降低",
                            STRATEGY_NAME, String.format("%.2f", priceVal), String.format("%.0f", distFromMiddle * 100));
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
    
    /**
     * 🔥 新增-20260309 + 优化-20260310: 计算新指标评分（优化版方案C + P0/P1/P2优化）
     * 
     * 集成4个新指标：动量、成交量突破、摆动点、HMA
     * 
     * 🔥 P0修复-20260310:
     * - 动量增加0.2%阈值
     * - 摆动点增加时效性检查（15分钟内有效）
     * - 增加指标冲突检测
     * 
     * 🔥 P1优化-20260310:
     * - 成交量改用当前K线方向判断（更实时）
     * - 摆动点平衡做多/做空权重（各7分）
     * - HMA改为过滤器（在主逻辑中使用）
     * 
     * 🔥 P2优化-20260310:
     * - 信号质量分级（A/B/C/D级）
     * - 一致性奖励
     * 
     * 权重分配（优化后）：
     * - 价格动量(Momentum): 做多/做空各4分（需>0.2%）
     * - 成交量突破(Volume): 5分（放量+K线方向一致）
     * - 摆动点(SwingPoint): 做多7分（突破高点）/做空7分（跌破低点）
     * - HMA趋势: 作为过滤器，不直接评分
     * - 一致性奖励: 3-5分（3-4个指标一致时）
     */
    private IndicatorScoreResult calculateNewIndicatorsScore(MarketContext context) {
        int buyScore = 0;
        int sellScore = 0;
        List<String> buyReasons = new ArrayList<>();
        List<String> sellReasons = new ArrayList<>();
        
        // 🔥 P2优化-20260310: 统计指标方向，用于冲突检测和一致性奖励
        int bullishCount = 0;   // 看涨指标数量
        int bearishCount = 0;   // 看跌指标数量
        
        try {
            // 1. 价格动量评分 (权重: 4分) - 🔥 P0优化：已增加0.2%阈值
            if (momentumCalculator != null) {
                MomentumCalculator.MomentumResult momentum = momentumCalculator.calculate(context.getKlines());
                if (momentum != null) {
                    if (momentum.isStrongUp()) {
                        buyScore += 4;
                        bullishCount++;  // 🔥 P2: 统计看涨指标
                        buyReasons.add(String.format("强劲上涨动量(4分,M2=%.2f%%,M5=%.2f%%)", 
                                momentum.getMomentum2Ratio() * 100, momentum.getMomentum5Ratio() * 100));
                        log.info("[{}] 📈 动量指标: 强劲上涨 (M2={} +{}%, M5={} +{}%)",
                                STRATEGY_NAME, momentum.getMomentum2(),
                                String.format("%.2f", momentum.getMomentum2Ratio() * 100),
                                momentum.getMomentum5(),
                                String.format("%.2f", momentum.getMomentum5Ratio() * 100));
                    } else if (momentum.isStrongDown()) {
                        sellScore += 4;
                        bearishCount++;  // 🔥 P2: 统计看跌指标
                        sellReasons.add(String.format("强劲下跌动量(4分,M2=%.2f%%,M5=%.2f%%)", 
                                momentum.getMomentum2Ratio() * 100, momentum.getMomentum5Ratio() * 100));
                        log.info("[{}] 📉 动量指标: 强劲下跌 (M2={} {}%, M5={} {}%)",
                                STRATEGY_NAME, momentum.getMomentum2(),
                                String.format("%.2f", momentum.getMomentum2Ratio() * 100),
                                momentum.getMomentum5(),
                                String.format("%.2f", momentum.getMomentum5Ratio() * 100));
                    }
                    
                    // 将动量数据存入context供后续使用
                    context.addIndicator("Momentum", momentum);
                }
            }
            
            // 2. 成交量突破评分 (权重: 5分) - 🔥 P1优化：改用当前K线方向判断
            if (volumeBreakoutCalculator != null) {
                VolumeBreakoutCalculator.VolumeBreakoutResult volume = 
                        volumeBreakoutCalculator.calculate(context.getKlines());
                if (volume != null && volume.isBreakout()) {
                    // 🔥 P1优化-20260310: 使用当前K线方向判断（更实时）
                    Kline currentKline = context.getKlines().get(0);
                    BigDecimal klineChange = currentKline.getClosePrice().subtract(currentKline.getOpenPrice());
                    
                    if (klineChange.compareTo(BigDecimal.ZERO) > 0) {
                        buyScore += 5;
                        bullishCount++;  // 🔥 P2: 统计看涨指标
                        buyReasons.add(String.format("放量阳线(5分,比率%.2f)", volume.getVolumeRatio()));
                        log.info("[{}] 📊 成交量突破: 放量阳线 (比率={}, K线涨幅={})",
                                STRATEGY_NAME, String.format("%.2f", volume.getVolumeRatio()), klineChange);
                    } else if (klineChange.compareTo(BigDecimal.ZERO) < 0) {
                        sellScore += 5;
                        bearishCount++;  // 🔥 P2: 统计看跌指标
                        sellReasons.add(String.format("放量阴线(5分,比率%.2f)", volume.getVolumeRatio()));
                        log.info("[{}] 📊 成交量突破: 放量阴线 (比率={}, K线跌幅={})",
                                STRATEGY_NAME, String.format("%.2f", volume.getVolumeRatio()), klineChange);
                    }
                    
                    // 将成交量数据存入context
                    context.addIndicator("VolumeBreakout", volume);
                }
            }
            
            // 3. 摆动点评分 - 🔥 P0优化：时效性检查；P1优化：平衡做多/做空权重
            if (swingPointCalculator != null) {
                SwingPointCalculator.SwingPointResult swingPoint = 
                        swingPointCalculator.calculate(context.getKlines());
                if (swingPoint != null) {
                    // 🔥 P0+P1优化：突破摆动高点 = 强烈做多信号（已增加15分钟时效性和0.3%幅度检查）
                    if (swingPoint.isBreakingHigh()) {
                        buyScore += 7;
                        bullishCount++;  // 🔥 P2: 统计看涨指标
                        buyReasons.add("突破摆动高点(7分)");
                        log.info("[{}] 🚀 摆动点: 有效突破高点 (高点={})", 
                                STRATEGY_NAME, 
                                swingPoint.getLastSwingHigh() != null ? 
                                        swingPoint.getLastSwingHigh().getPrice() : "N/A");
                    }
                    
                    // 🔥 P1新增-20260310: 跌破摆动低点 = 强烈做空信号（权重平衡）
                    if (swingPoint.isBreakingLow()) {
                        sellScore += 7;
                        bearishCount++;  // 🔥 P2: 统计看跌指标
                        sellReasons.add("跌破摆动低点(7分)");
                        log.info("[{}] 📉 摆动点: 有效跌破低点 (低点={})", 
                                STRATEGY_NAME, 
                                swingPoint.getLastSwingLow() != null ? 
                                        swingPoint.getLastSwingLow().getPrice() : "N/A");
                    }
                    
                    // 接近摆动低点支撑（仅记录，不评分）
                    if (swingPoint.isNearSupport()) {
                        log.info("[{}] 📍 摆动点: 接近支撑位 (低点={})", 
                                STRATEGY_NAME, 
                                swingPoint.getLastSwingLow() != null ? 
                                        swingPoint.getLastSwingLow().getPrice() : "N/A");
                    }
                    
                    // 将摆动点数据存入context
                    context.addIndicator("SwingPoint", swingPoint);
                }
            }
            
            // 4. HMA趋势 - 🔥 P1优化：移除评分，改为在主逻辑中作为过滤器使用
            if (hmaCalculator != null) {
                HMACalculator.HMAResult hma = hmaCalculator.calculate(context.getKlines());
                if (hma != null) {
                    log.info("[{}] 📐 HMA: 趋势={}, 斜率={}%, 价格{}HMA",
                            STRATEGY_NAME, hma.getTrend(),
                            String.format("%.4f", hma.getSlope()),
                            hma.isPriceAboveHMA() ? "在上方" : "在下方");
                    
                    // 🔥 P1优化-20260310: HMA不直接评分，但统计方向用于冲突检测
                    if ("UP".equals(hma.getTrend()) && hma.isPriceAboveHMA()) {
                        bullishCount++;  // 统计但不评分
                    } else if ("DOWN".equals(hma.getTrend()) && !hma.isPriceAboveHMA()) {
                        bearishCount++;  // 统计但不评分
                    }
                    
                    // 将HMA数据存入context（供主逻辑使用作为过滤器）
                    context.addIndicator("HMA", hma);
                }
            }
            
            // 🔥 P0修复-20260310: 指标冲突检测
            int totalSignals = bullishCount + bearishCount;
            if (bullishCount > 0 && bearishCount > 0) {
                log.warn("[{}] ⚠️ 新指标信号冲突: {}个看涨 vs {}个看跌，拒绝评分", 
                        STRATEGY_NAME, bullishCount, bearishCount);
                return IndicatorScoreResult.builder()
                        .buyScore(0)
                        .sellScore(0)
                        .buyReasons(List.of("指标方向冲突，拒绝信号"))
                        .sellReasons(List.of("指标方向冲突，拒绝信号"))
                        .signalQuality("D")  // D级：冲突信号
                        .build();
            }
            
            // 🔥 P2优化-20260310: 信号质量分级和一致性奖励
            String signalQuality = "C";  // 默认C级
            int consistencyBonus = 0;
            
            if (totalSignals >= 4) {
                // A级：4个指标一致（最高质量）
                signalQuality = "A";
                consistencyBonus = 5;
                if (bullishCount == totalSignals) {
                    buyScore += consistencyBonus;
                    buyReasons.add("4指标高度一致(+5分)");
                    log.info("[{}] 🌟 信号质量A级: 4个指标完全一致(做多)", STRATEGY_NAME);
                } else {
                    sellScore += consistencyBonus;
                    sellReasons.add("4指标高度一致(+5分)");
                    log.info("[{}] 🌟 信号质量A级: 4个指标完全一致(做空)", STRATEGY_NAME);
                }
            } else if (totalSignals == 3) {
                // B级：3个指标一致（高质量）
                signalQuality = "B";
                consistencyBonus = 3;
                if (bullishCount == 3) {
                    buyScore += consistencyBonus;
                    buyReasons.add("3指标一致(+3分)");
                    log.info("[{}] ⭐ 信号质量B级: 3个指标一致(做多)", STRATEGY_NAME);
                } else {
                    sellScore += consistencyBonus;
                    sellReasons.add("3指标一致(+3分)");
                    log.info("[{}] ⭐ 信号质量B级: 3个指标一致(做空)", STRATEGY_NAME);
                }
            } else if (totalSignals == 2) {
                // C级：2个指标（中等质量，正常评分）
                signalQuality = "C";
                log.info("[{}] 📊 信号质量C级: 2个指标支持", STRATEGY_NAME);
            } else if (totalSignals == 1) {
                // C-级：单个指标（较低质量）
                signalQuality = "C-";
                log.info("[{}] ⚠️ 信号质量C-级: 仅1个指标支持", STRATEGY_NAME);
            }
            
            return IndicatorScoreResult.builder()
                    .buyScore(buyScore)
                    .sellScore(sellScore)
                    .buyReasons(buyReasons)
                    .sellReasons(sellReasons)
                    .signalQuality(signalQuality)
                    .indicatorCount(totalSignals)
                    .bullishCount(bullishCount)
                    .bearishCount(bearishCount)
                    .build();
            
        } catch (Exception e) {
            log.error("[{}] 计算新指标评分失败", STRATEGY_NAME, e);
            return null;
        }
    }
    
    /**
     * 新指标评分结果
     * 
     * 🔥 P2优化-20260310: 增加信号质量等级
     */
    @lombok.Data
    @lombok.Builder
    private static class IndicatorScoreResult {
        private int buyScore;           // 做多评分
        private int sellScore;          // 做空评分
        private List<String> buyReasons;   // 做多理由
        private List<String> sellReasons;  // 做空理由
        private String signalQuality;   // 🔥 P2新增：信号质量(A/B/C/C-/D)
        private int indicatorCount;     // 🔥 P2新增：有信号的指标总数
        private int bullishCount;       // 🔥 P2新增：看涨指标数量
        private int bearishCount;       // 🔥 P2新增：看跌指标数量
    }
    
    /**
     * 🔥 新增-20260309: 填充新指标数据到TradingSignal
     * 
     * 从MarketContext中提取新指标数据并填充到TradingSignal.Builder中
     * 
     * @param builder TradingSignal构建器
     * @param context 市场上下文
     */
    private void fillNewIndicatorData(TradingSignal.TradingSignalBuilder builder, MarketContext context) {
        try {
            // 1. 填充动量指标数据
            MomentumCalculator.MomentumResult momentum = context.getIndicator("Momentum");
            if (momentum != null) {
                builder.momentum2(momentum.getMomentum2());
                builder.momentum5(momentum.getMomentum5());
            }
            
            // 2. 填充成交量指标数据
            VolumeBreakoutCalculator.VolumeBreakoutResult volume = context.getIndicator("VolumeBreakout");
            if (volume != null) {
                builder.volumeRatio(volume.getVolumeRatio());
            }
            
            // 3. 填充摆动点指标数据
            SwingPointCalculator.SwingPointResult swingPoint = context.getIndicator("SwingPoint");
            if (swingPoint != null) {
                if (swingPoint.getLastSwingHigh() != null) {
                    builder.lastSwingHigh(swingPoint.getLastSwingHigh().getPrice());
                }
                if (swingPoint.getLastSwingLow() != null) {
                    builder.lastSwingLow(swingPoint.getLastSwingLow().getPrice());
                }
                
                // 价格位置判断
                BigDecimal currentPrice = context.getCurrentPrice();
                String pricePosition = "BETWEEN";
                if (swingPoint.getLastSwingHigh() != null && swingPoint.getLastSwingLow() != null) {
                    if (currentPrice.compareTo(swingPoint.getLastSwingHigh().getPrice()) > 0) {
                        pricePosition = "ABOVE_SWING_HIGH";
                    } else if (currentPrice.compareTo(swingPoint.getLastSwingLow().getPrice()) < 0) {
                        pricePosition = "BELOW_SWING_LOW";
                    }
                }
                builder.pricePosition(pricePosition);
            }
            
            // 4. 填充HMA指标数据
            HMACalculator.HMAResult hma = context.getIndicator("HMA");
            if (hma != null) {
                builder.hma20(hma.getHma20());
                builder.hma20Slope(hma.getSlope());
            }
            
            // 5. 趋势确认判断
            Double adx = context.getIndicator("ADX");
            if (hma != null && adx != null) {
                // 趋势确认条件：HMA趋势明确 && ADX >= 18
                boolean trendConfirmed = ("UP".equals(hma.getTrend()) || "DOWN".equals(hma.getTrend())) 
                                        && adx >= 18.0;
                builder.trendConfirmed(trendConfirmed);
            }
            
            log.debug("[{}] 新指标数据已填充到TradingSignal", STRATEGY_NAME);
            
        } catch (Exception e) {
            log.error("[{}] 填充新指标数据失败", STRATEGY_NAME, e);
        }
    }
}
