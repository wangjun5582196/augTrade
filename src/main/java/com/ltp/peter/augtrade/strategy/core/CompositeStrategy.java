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
                
                // 🔥 额外验证：做空需要超强信号（因为历史表现差）
                int strengthThreshold = 80;
                if (sellScore < strengthThreshold) {
                    log.warn("[{}] 🚫 做空信号强度{}不足（需要≥{}），数据显示做空整体表现差", 
                            STRATEGY_NAME, sellScore, strengthThreshold);
                    return createHoldSignal(String.format("做空需要超强信号≥%d分", strengthThreshold), 
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
     * 🔥 P0修复-20260209: 验证价格位置是否合理（严格布林带过滤）
     * 
     * 核心改进:
     * - 做多: 价格不能高于布林上轨（从5%溢价改为0%，数据显示13/16笔突破上轨追多大多亏损）
     * - 做多: 理想入场区间在中轨附近（中轨±1σ范围内）
     * - 做空: 价格不能低于布林下轨
     * - 如果无布林带,使用EMA判断(价格偏离EMA20不超过0.3%)
     */
    private boolean validatePricePosition(MarketContext context, TradingSignal signal) {
        if (signal.getType() == TradingSignal.SignalType.HOLD) {
            return true;
        }
        
        BollingerBands bb = context.getIndicator("BollingerBands");
        BigDecimal price = context.getCurrentPrice();
        
        // 如果没有布林带,使用EMA判断
        if (bb == null) {
            EMACalculator.EMATrend trend = context.getIndicator("EMATrend");
            if (trend == null) {
                log.warn("[{}] 无布林带和EMA数据,跳过价格位置检查", STRATEGY_NAME);
                return true;
            }
            
            double priceVal = price.doubleValue();
            double emaShort = trend.getEmaShort();  // 使用emaShort (EMA20)
            
            if (signal.getType() == TradingSignal.SignalType.BUY) {
                // 🔥 20260209: 做多时价格不能高于EMA20超过0.3%（从0.5%收紧）
                if (priceVal > emaShort * 1.003) {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{}高于EMA20 {}超过0.3%（追高保护）", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), String.format("%.2f", emaShort));
                    return false;
                }
            } else if (signal.getType() == TradingSignal.SignalType.SELL) {
                if (priceVal < emaShort * 0.997) {
                    log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于EMA20 {}超过0.3%", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), String.format("%.2f", emaShort));
                    return false;
                }
            }
            return true;
        }
        
        // 有布林带,严格检查
        Double upper = bb.getUpper();
        Double lower = bb.getLower();
        Double middle = bb.getMiddle();
        double priceVal = price.doubleValue();
        
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            // 🔥 P0修复-20260209：禁止在布林带上轨上方做多！
            // 数据分析：今日13/16笔在突破上轨后做多，属于追高行为
            // 仅3笔在中轨附近入场（#366中轨上方、#379/#380中轨下方）表现最好
            // 修改：做多时价格必须低于布林带上轨（0%溢价，从5%改为0%）
            if (priceVal > upper) {
                double exceedsPercent = (priceVal - upper) / upper * 100;
                log.warn("[{}] ⛔ BUY信号被过滤: 价格{} > 布林上轨{}（超出{:.2f}%），禁止追高！", 
                        STRATEGY_NAME, String.format("%.2f", priceVal), 
                        String.format("%.2f", upper), exceedsPercent);
                return false;
            }
            
            // 🔥 P0修复-20260209：价格远离中轨时降低信号（理想入场在中轨附近）
            double distFromMiddle = (priceVal - middle) / (upper - middle);
            if (distFromMiddle > 0.8) {
                // 价格在上轨80%以上区间，虽未突破但已偏高
                log.warn("[{}] ⚠️ BUY信号警告: 价格{}偏近上轨（中轨距离{:.0f}%），信号质量降低", 
                        STRATEGY_NAME, String.format("%.2f", priceVal), distFromMiddle * 100);
                // 不阻止但记录警告，由信号强度调整处理
            }
            
        } else if (signal.getType() == TradingSignal.SignalType.SELL) {
            // 做空: 价格不能低于布林下轨
            if (priceVal < lower) {
                double below = lower - priceVal;
                log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于布林下轨{} (-{} USD)", 
                        STRATEGY_NAME, 
                        String.format("%.2f", priceVal), 
                        String.format("%.2f", lower), 
                        String.format("%.2f", below));
                return false;
            }
        }
        
        log.debug("[{}] ✅ 价格位置检查通过: 价格{} 在布林带[{}, {}]内", 
                STRATEGY_NAME, 
                String.format("%.2f", priceVal), 
                String.format("%.2f", lower), 
                String.format("%.2f", upper));
        return true;
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
