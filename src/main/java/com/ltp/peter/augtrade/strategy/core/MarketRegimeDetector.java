package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.ADXCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 市场环境检测器
 * 根据ADX、波动率等指标判断当前市场环境
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class MarketRegimeDetector {
    
    @Autowired
    private ADXCalculator adxCalculator;
    
    /**
     * 市场环境类型
     */
    public enum MarketRegime {
        STRONG_TREND("强趋势", 4, 900, 1.5),      // 强趋势：低门槛，长持仓，高止盈
        WEAK_TREND("弱趋势", 5, 600, 1.2),        // 弱趋势：中门槛，中持仓，中止盈
        CONSOLIDATION("盘整", 7, 300, 1.0),      // 盘整：高门槛，短持仓，标准止盈
        CHOPPY("震荡", 8, 180, 0.8);              // 震荡：最高门槛，最短持仓，降低止盈
        
        private final String description;
        private final int requiredScore;          // 建议的信号评分门槛
        private final int minHoldingSeconds;      // 建议的最小持仓时间（秒）
        private final double takeProfitMultiplier; // 止盈倍数调整
        
        MarketRegime(String description, int requiredScore, int minHoldingSeconds, double takeProfitMultiplier) {
            this.description = description;
            this.requiredScore = requiredScore;
            this.minHoldingSeconds = minHoldingSeconds;
            this.takeProfitMultiplier = takeProfitMultiplier;
        }
        
        public String getDescription() { return description; }
        public int getRequiredScore() { return requiredScore; }
        public int getMinHoldingSeconds() { return minHoldingSeconds; }
        public double getTakeProfitMultiplier() { return takeProfitMultiplier; }
    }
    
    /**
     * 检测当前市场环境
     * 
     * @param klines K线数据
     * @return 市场环境类型
     */
    public MarketRegime detectRegime(List<Kline> klines) {
        if (klines == null || klines.size() < 14) {
            log.warn("K线数据不足，返回默认盘整模式");
            return MarketRegime.CONSOLIDATION;
        }
        
        try {
            // 计算ADX
            double adx = adxCalculator.calculate(klines);
            
            // 计算波动率（最近10根K线的价格标准差/均价）
            double volatility = calculateVolatility(klines, 10);
            
            // 计算趋势一致性（最近5根K线的方向一致程度）
            double trendConsistency = calculateTrendConsistency(klines, 5);
            
            log.debug("市场环境指标 - ADX: {}, 波动率: {}, 趋势一致性: {}", 
                    String.format("%.2f", adx), String.format("%.4f", volatility), String.format("%.2f", trendConsistency));
            
            // 根据指标判断市场环境
            MarketRegime regime = classifyRegime(adx, volatility, trendConsistency);
            
            log.info("📊 市场环境: {} (ADX={}, 波动率={}%, 一致性={}%)", 
                    regime.getDescription(), String.format("%.1f", adx), String.format("%.2f", volatility * 100), String.format("%.0f", trendConsistency * 100));
            
            return regime;
            
        } catch (Exception e) {
            log.error("市场环境检测失败", e);
            return MarketRegime.CONSOLIDATION; // 默认返回盘整模式
        }
    }
    
    /**
     * 根据指标对市场环境进行分类
     * 
     * 🔥 修复-20260129：优先基于ADX判断，避免将高ADX误判为盘整
     * 核心逻辑：ADX是最可靠的趋势指标，应该作为主要判断依据
     */
    private MarketRegime classifyRegime(double adx, double volatility, double trendConsistency) {
        // 强趋势：ADX > 35（无论波动率高低）
        // 修复前：要求 ADX>35 AND 波动率>1.5% AND 一致性>60%
        // 问题：平稳上涨趋势（低波动+高ADX）会被误判为盘整
        // 修复后：只要ADX>35就是强趋势，波动率只用于进一步分类
        if (adx > 35) {
            if (volatility > 0.020) {
                log.debug("🔥 强趋势-高波动型（激烈行情）");
            } else {
                log.debug("📈 强趋势-平稳型（稳步单边）");
            }
            return MarketRegime.STRONG_TREND;
        }
        
        // 中等趋势：ADX 20-35
        // ADX>20表示有明确的方向性运动，应该识别为趋势
        if (adx > 20) {
            return MarketRegime.WEAK_TREND;
        }
        
        // 无明确趋势：ADX < 20
        // 此时根据波动率区分震荡和盘整
        if (volatility > 0.015) {
            return MarketRegime.CHOPPY;        // 高波动无方向 = 震荡
        } else {
            return MarketRegime.CONSOLIDATION;  // 低波动无方向 = 盘整
        }
    }
    
    /**
     * 计算波动率（标准差/均价）
     * 
     * @param klines K线数据
     * @param period 计算周期
     * @return 波动率（百分比）
     */
    private double calculateVolatility(List<Kline> klines, int period) {
        if (klines.size() < period) {
            period = klines.size();
        }
        
        // 获取最近N根K线的收盘价
        List<BigDecimal> closePrices = klines.stream()
                .skip(Math.max(0, klines.size() - period))
                .map(Kline::getClosePrice)
                .toList();
        
        // 计算均价
        BigDecimal sum = closePrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(closePrices.size()), 8, RoundingMode.HALF_UP);
        
        // 计算标准差
        BigDecimal variance = closePrices.stream()
                .map(price -> price.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(closePrices.size()), 8, RoundingMode.HALF_UP);
        
        double stdDev = Math.sqrt(variance.doubleValue());
        
        // 波动率 = 标准差 / 均价
        return stdDev / mean.doubleValue();
    }
    
    /**
     * 计算趋势一致性
     * 检查最近N根K线的涨跌方向是否一致
     * 
     * @param klines K线数据
     * @param period 计算周期
     * @return 趋势一致性（0-1之间，1表示完全一致）
     */
    private double calculateTrendConsistency(List<Kline> klines, int period) {
        if (klines.size() < period + 1) {
            return 0.5; // 数据不足，返回中性值
        }
        
        // 获取最近N根K线
        List<Kline> recentKlines = klines.subList(
                Math.max(0, klines.size() - period - 1), 
                klines.size()
        );
        
        int upCount = 0;
        int downCount = 0;
        
        // 统计涨跌次数
        for (int i = 1; i < recentKlines.size(); i++) {
            BigDecimal prevClose = recentKlines.get(i - 1).getClosePrice();
            BigDecimal currClose = recentKlines.get(i).getClosePrice();
            
            if (currClose.compareTo(prevClose) > 0) {
                upCount++;
            } else if (currClose.compareTo(prevClose) < 0) {
                downCount++;
            }
        }
        
        // 一致性 = 主导方向的占比
        int totalMoves = upCount + downCount;
        if (totalMoves == 0) {
            return 0.5; // 完全横盘
        }
        
        int dominantCount = Math.max(upCount, downCount);
        return (double) dominantCount / totalMoves;
    }
    
    /**
     * 获取市场环境的策略参数建议
     * 
     * @param regime 市场环境
     * @return 参数建议说明
     */
    public String getStrategyAdvice(MarketRegime regime) {
        return String.format(
                "【%s市场策略建议】\n" +
                "- 信号评分门槛: %d分\n" +
                "- 最小持仓时间: %d秒（%d分钟）\n" +
                "- 止盈目标调整: %.1f倍\n" +
                "- 建议: %s",
                regime.getDescription(),
                regime.getRequiredScore(),
                regime.getMinHoldingSeconds(),
                regime.getMinHoldingSeconds() / 60,
                regime.getTakeProfitMultiplier(),
                getRegimeAdvice(regime)
        );
    }
    
    /**
     * 获取具体的交易建议
     */
    private String getRegimeAdvice(MarketRegime regime) {
        return switch (regime) {
            case STRONG_TREND -> "积极跟随趋势，延长持仓时间，扩大止盈目标";
            case WEAK_TREND -> "谨慎跟随趋势，保持正常参数";
            case CONSOLIDATION -> "提高信号质量要求，快进快出";
            case CHOPPY -> "大幅提高门槛，减少交易次数，降低止盈预期";
        };
    }
}
