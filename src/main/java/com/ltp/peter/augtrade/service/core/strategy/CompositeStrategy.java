package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private static final int SIGNAL_THRESHOLD = 15; // 最小信号阈值
    
    @Autowired(required = false)
    private List<Strategy> strategies;
    
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
            
            // 根据得分生成信号
            if (buyScore >= SIGNAL_THRESHOLD && buyScore > sellScore) {
                int strength = calculateSignalStrength(buyScore, sellScore);
                String reason = String.format("综合策略做多 (得分:%d) [%s]", 
                        buyScore, String.join(", ", buyReasons));
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(strength)
                        .score(buyScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(reason)
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            if (sellScore >= SIGNAL_THRESHOLD && sellScore > buyScore) {
                int strength = calculateSignalStrength(sellScore, buyScore);
                String reason = String.format("综合策略做空 (得分:%d) [%s]", 
                        sellScore, String.join(", ", sellReasons));
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(strength)
                        .score(sellScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(reason)
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
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
     * 基于主导得分和得分差距
     */
    private int calculateSignalStrength(int dominantScore, int oppositeScore) {
        // 得分差距
        int scoreDiff = dominantScore - oppositeScore;
        
        // 基础强度（根据主导得分）
        int baseStrength = Math.min(dominantScore * 2, 70);
        
        // 额外强度（根据得分差距）
        int bonusStrength = Math.min(scoreDiff, 30);
        
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
}
