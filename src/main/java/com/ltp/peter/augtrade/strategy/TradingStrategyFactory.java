package com.ltp.peter.augtrade.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 交易策略工厂
 * 
 * 统一管理所有交易策略，通过配置文件切换
 * 
 * 使用方式：
 * 1. 在application.yml中配置 trading.strategy.active
 * 2. 调用 generateSignal(symbol) 获取信号
 * 3. 工厂会自动使用配置的策略
 * 
 * 可选策略：
 * - simplified-trend       (精简趋势策略，仅ADX+ATR+EMA)
 * - balanced-aggressive    (平衡激进策略，多指标综合评分)
 * - composite              (组合策略，多个子策略投票)
 * 
 * @author Peter Wang
 * @since 2026-01-22
 */
@Slf4j
@Service
public class TradingStrategyFactory {
    
    @Value("${trading.strategy.active:balanced-aggressive}")
    private String activeStrategy;
    
    @Autowired
    private SimplifiedTrendStrategy simplifiedTrendStrategy;
    
    @Autowired
    private AggressiveScalpingStrategy aggressiveScalpingStrategy;
    
    @Autowired
    private com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator strategyOrchestrator;

    @Autowired
    private com.ltp.peter.augtrade.strategy.core.SRRejectionScalpingStrategy srRejectionScalpingStrategy;
    
    /**
     * 策略信号枚举（统一所有策略的返回类型）
     */
    public enum Signal {
        BUY,    // 买入信号
        SELL,   // 卖出信号
        HOLD    // 持有/观望
    }
    
    /**
     * 生成交易信号（主入口）
     * 
     * 根据配置文件中的 trading.strategy.active 自动选择策略
     * 
     * @param symbol 交易品种
     * @return 交易信号
     */
    public Signal generateSignal(String symbol) {
        log.info("========================================");
        log.info("🎯 策略工厂 - 当前激活策略: {}", activeStrategy);
        
        Signal signal = null;
        
        try {
            switch (activeStrategy.toLowerCase()) {
                case "simplified-trend":
                    signal = executeSimplifiedTrend(symbol);
                    break;
                
                case "balanced-aggressive":
                    signal = executeBalancedAggressive(symbol);
                    break;
                
                case "composite":
                    signal = executeComposite(symbol);
                    break;

                case "sr_rejection":
                    signal = executeSRRejection(symbol);
                    break;

                default:
                    log.warn("⚠️ 未知策略: {}, 使用默认策略 balanced-aggressive", activeStrategy);
                    signal = executeBalancedAggressive(symbol);
                    break;
            }
            
            log.info("📊 策略输出信号: {}", signal);
            log.info("========================================");
            
            return signal;
            
        } catch (Exception e) {
            log.error("❌ 策略执行失败: {}", activeStrategy, e);
            log.info("========================================");
            return Signal.HOLD;
        }
    }
    
    /**
     * 执行精简趋势策略
     * 
     * 特点：
     * - 仅使用ADX、ATR、EMA三个核心指标
     * - 适合强趋势市场
     * - 简单稳健，误信号少
     * 
     * @param symbol 交易品种
     * @return 交易信号
     */
    private Signal executeSimplifiedTrend(String symbol) {
        log.info("📈 执行【精简趋势策略】");
        
        SimplifiedTrendStrategy.Signal result = simplifiedTrendStrategy.execute(symbol);
        
        // 转换信号类型
        if (result == SimplifiedTrendStrategy.Signal.BUY) {
            log.info("✅ 精简趋势策略 → 买入信号");
            return Signal.BUY;
        } else if (result == SimplifiedTrendStrategy.Signal.SELL) {
            log.info("✅ 精简趋势策略 → 卖出信号");
            return Signal.SELL;
        } else {
            log.info("⏸️ 精简趋势策略 → 观望");
            return Signal.HOLD;
        }
    }
    
    /**
     * 执行平衡激进策略
     * 
     * 特点：
     * - 多指标综合评分（Williams、ADX、ATR、EMA、布林带）
     * - 适合中等趋势+震荡市场
     * - 灵活但参数多
     * 
     * @param symbol 交易品种
     * @return 交易信号
     */
    private Signal executeBalancedAggressive(String symbol) {
        log.info("⚡ 执行【平衡激进策略】");
        
        AggressiveScalpingStrategy.Signal result = aggressiveScalpingStrategy.balancedAggressiveStrategy(symbol);
        
        // 转换信号类型
        if (result == AggressiveScalpingStrategy.Signal.BUY) {
            log.info("✅ 平衡激进策略 → 买入信号");
            return Signal.BUY;
        } else if (result == AggressiveScalpingStrategy.Signal.SELL) {
            log.info("✅ 平衡激进策略 → 卖出信号");
            return Signal.SELL;
        } else {
            log.info("⏸️ 平衡激进策略 → 观望");
            return Signal.HOLD;
        }
    }
    
    /**
     * 执行组合策略
     * 
     * 特点：
     * - 多个子策略投票（RSI、Williams、布林带、震荡市）
     * - 适合所有市场
     * - 计算复杂但稳定
     * 
     * @param symbol 交易品种
     * @return 交易信号
     */
    private Signal executeComposite(String symbol) {
        log.info("🔀 执行【组合策略】");
        
        com.ltp.peter.augtrade.strategy.signal.TradingSignal result = 
                strategyOrchestrator.generateSignal(symbol);
        
        // 转换信号类型
        if (result.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
            log.info("✅ 组合策略 → 买入信号 (强度: {}, 得分: {})", 
                    result.getStrength(), result.getScore());
            return Signal.BUY;
        } else if (result.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
            log.info("✅ 组合策略 → 卖出信号 (强度: {}, 得分: {})", 
                    result.getStrength(), result.getScore());
            return Signal.SELL;
        } else {
            log.info("⏸️ 组合策略 → 观望");
            return Signal.HOLD;
        }
    }
    
    /**
     * 执行 SR 拒绝策略
     */
    private Signal executeSRRejection(String symbol) {
        log.info("🔷 执行【SR拒绝策略】");

        com.ltp.peter.augtrade.strategy.signal.TradingSignal result =
                strategyOrchestrator.generateSignalWithStrategy(symbol, srRejectionScalpingStrategy);

        if (result.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
            log.info("✅ SR拒绝策略 → 买入信号 (强度: {}, 得分: {})",
                    result.getStrength(), result.getScore());
            return Signal.BUY;
        } else if (result.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
            log.info("✅ SR拒绝策略 → 卖出信号 (强度: {}, 得分: {})",
                    result.getStrength(), result.getScore());
            return Signal.SELL;
        } else {
            log.info("⏸️ SR拒绝策略 → 观望");
            return Signal.HOLD;
        }
    }

    /**
     * 获取当前激活的策略名称
     * 
     * @return 策略名称
     */
    public String getActiveStrategyName() {
        return activeStrategy;
    }
    
    /**
     * 运行时切换策略（高级功能）
     * 
     * 注意：仅用于测试或紧急切换，生产环境建议通过配置文件修改
     * 
     * @param strategyName 策略名称
     */
    public void setActiveStrategy(String strategyName) {
        String oldStrategy = this.activeStrategy;
        this.activeStrategy = strategyName;
        log.warn("🔄 策略已切换: {} → {}", oldStrategy, strategyName);
    }
    
    /**
     * 获取策略描述
     * 
     * @return 策略描述信息
     */
    public String getStrategyDescription() {
        switch (activeStrategy.toLowerCase()) {
            case "simplified-trend":
                return "精简趋势策略 - 仅使用ADX+ATR+EMA，适合强趋势市场";
            
            case "balanced-aggressive":
                return "平衡激进策略 - 多指标综合评分，适合中等趋势+震荡市场";
            
            case "composite":
                return "组合策略 - 多个子策略投票，适合所有市场";

            case "sr_rejection":
                return "SR拒绝策略 - 支撑/阻力位价格拒绝 + StochRSI确认，适合震荡市场";

            default:
                return "未知策略: " + activeStrategy;
        }
    }
}
