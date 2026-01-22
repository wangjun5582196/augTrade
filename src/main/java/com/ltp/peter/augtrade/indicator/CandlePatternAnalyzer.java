package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * K线形态分析器
 * 
 * 识别常见的K线形态，提供更强的信号确认
 * 
 * 支持的形态：
 * 1. 十字星（Doji）- 犹豫不决
 * 2. 看涨吞没（Bullish Engulfing）- 强烈看涨
 * 3. 看跌吞没（Bearish Engulfing）- 强烈看跌
 * 4. 锤子线（Hammer）- 底部反转
 * 5. 射击之星（Shooting Star）- 顶部反转
 * 6. 早晨之星（Morning Star）- 三根K线看涨反转
 * 7. 黄昏之星（Evening Star）- 三根K线看跌反转
 * 8. 启明星（Piercing Pattern）- 看涨穿刺
 * 9. 乌云盖顶（Dark Cloud Cover）- 看跌覆盖
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class CandlePatternAnalyzer implements TechnicalIndicator<CandlePattern> {
    
    @Override
    public CandlePattern calculate(List<Kline> klines) {
        if (!hasEnoughData(klines)) {
            log.debug("K线数据不足，需要至少 {} 根K线，当前只有 {} 根", getRequiredPeriods(), klines.size());
            return CandlePattern.none();
        }
        
        try {
            Kline current = klines.get(0);  // 当前K线
            Kline prev1 = klines.get(1);    // 前1根K线
            Kline prev2 = klines.get(2);    // 前2根K线
            
            // 按优先级检测形态
            CandlePattern pattern;
            
            // 1. 三根K线形态（优先级最高）
            pattern = detectMorningStar(current, prev1, prev2);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectEveningStar(current, prev1, prev2);
            if (pattern.hasPattern()) return pattern;
            
            // 2. 两根K线形态
            pattern = detectBullishEngulfing(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectBearishEngulfing(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectPiercing(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectDarkCloud(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            // 3. 单根K线形态
            pattern = detectDoji(current);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectHammer(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            pattern = detectShootingStar(current, prev1);
            if (pattern.hasPattern()) return pattern;
            
            return CandlePattern.none();
            
        } catch (Exception e) {
            log.error("分析K线形态时发生错误", e);
            return CandlePattern.none();
        }
    }
    
    /**
     * 检测十字星（Doji）
     * 实体很小，表示犹豫不决
     */
    private CandlePattern detectDoji(Kline kline) {
        BigDecimal body = getBodySize(kline);
        BigDecimal range = getRange(kline);
        
        // 实体 < 全部范围的10%
        if (body.compareTo(range.multiply(new BigDecimal("0.1"))) < 0) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.DOJI)
                    .direction(CandlePattern.Direction.NEUTRAL)
                    .strength(5)
                    .description("十字星 - 市场犹豫不决，等待方向确认")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测看涨吞没（Bullish Engulfing）
     * 强烈看涨信号
     */
    private CandlePattern detectBullishEngulfing(Kline current, Kline prev) {
        boolean prevIsBearish = prev.getClosePrice().compareTo(prev.getOpenPrice()) < 0;
        boolean currentIsBullish = current.getClosePrice().compareTo(current.getOpenPrice()) > 0;
        boolean engulfs = current.getOpenPrice().compareTo(prev.getClosePrice()) <= 0 &&
                         current.getClosePrice().compareTo(prev.getOpenPrice()) >= 0;
        
        if (prevIsBearish && currentIsBullish && engulfs) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.BULLISH_ENGULFING)
                    .direction(CandlePattern.Direction.BULLISH)
                    .strength(9)
                    .description("看涨吞没 - 强烈的底部反转信号")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测看跌吞没（Bearish Engulfing）
     * 强烈看跌信号
     */
    private CandlePattern detectBearishEngulfing(Kline current, Kline prev) {
        boolean prevIsBullish = prev.getClosePrice().compareTo(prev.getOpenPrice()) > 0;
        boolean currentIsBearish = current.getClosePrice().compareTo(current.getOpenPrice()) < 0;
        boolean engulfs = current.getOpenPrice().compareTo(prev.getClosePrice()) >= 0 &&
                         current.getClosePrice().compareTo(prev.getOpenPrice()) <= 0;
        
        if (prevIsBullish && currentIsBearish && engulfs) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.BEARISH_ENGULFING)
                    .direction(CandlePattern.Direction.BEARISH)
                    .strength(9)
                    .description("看跌吞没 - 强烈的顶部反转信号")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测锤子线（Hammer）
     * 底部反转信号
     */
    private CandlePattern detectHammer(Kline current, Kline prev) {
        boolean currentIsBullish = current.getClosePrice().compareTo(current.getOpenPrice()) > 0;
        boolean prevIsBearish = prev.getClosePrice().compareTo(prev.getOpenPrice()) < 0;
        
        BigDecimal body = getBodySize(current);
        BigDecimal lowerShadow = getLowerShadow(current);
        BigDecimal upperShadow = getUpperShadow(current);
        
        // 下影线 > 实体2倍，上影线 < 实体30%
        boolean isHammer = lowerShadow.compareTo(body.multiply(new BigDecimal("2"))) > 0 &&
                          upperShadow.compareTo(body.multiply(new BigDecimal("0.3"))) < 0;
        
        if (currentIsBullish && prevIsBearish && isHammer) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.HAMMER)
                    .direction(CandlePattern.Direction.BULLISH)
                    .strength(8)
                    .description("锤子线 - 底部反转信号，下跌趋势可能结束")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测射击之星（Shooting Star）
     * 顶部反转信号
     */
    private CandlePattern detectShootingStar(Kline current, Kline prev) {
        boolean currentIsBearish = current.getClosePrice().compareTo(current.getOpenPrice()) < 0;
        boolean prevIsBullish = prev.getClosePrice().compareTo(prev.getOpenPrice()) > 0;
        
        BigDecimal body = getBodySize(current);
        BigDecimal lowerShadow = getLowerShadow(current);
        BigDecimal upperShadow = getUpperShadow(current);
        
        // 上影线 > 实体2倍，下影线 < 实体30%
        boolean isShootingStar = upperShadow.compareTo(body.multiply(new BigDecimal("2"))) > 0 &&
                                lowerShadow.compareTo(body.multiply(new BigDecimal("0.3"))) < 0;
        
        if (currentIsBearish && prevIsBullish && isShootingStar) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.SHOOTING_STAR)
                    .direction(CandlePattern.Direction.BEARISH)
                    .strength(8)
                    .description("射击之星 - 顶部反转信号，上涨趋势可能结束")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测早晨之星（Morning Star）
     * 三根K线看涨反转
     */
    private CandlePattern detectMorningStar(Kline current, Kline prev1, Kline prev2) {
        boolean prev2IsBearish = prev2.getClosePrice().compareTo(prev2.getOpenPrice()) < 0;
        boolean currentIsBullish = current.getClosePrice().compareTo(current.getOpenPrice()) > 0;
        
        BigDecimal prev2Body = getBodySize(prev2);
        BigDecimal prev1Body = getBodySize(prev1);
        
        // 第1根：大阴线
        // 第2根：小实体（实体 < 第1根实体的30%）
        // 第3根：阳线，收盘在第1根中部以上
        boolean isSmallBody = prev1Body.compareTo(prev2Body.multiply(new BigDecimal("0.3"))) < 0;
        BigDecimal prev2Middle = prev2.getOpenPrice().add(prev2.getClosePrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        boolean closesAboveMiddle = current.getClosePrice().compareTo(prev2Middle) > 0;
        
        if (prev2IsBearish && isSmallBody && currentIsBullish && closesAboveMiddle) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.MORNING_STAR)
                    .direction(CandlePattern.Direction.BULLISH)
                    .strength(10)
                    .description("早晨之星 - 强烈的底部反转信号（三根K线）")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测黄昏之星（Evening Star）
     * 三根K线看跌反转
     */
    private CandlePattern detectEveningStar(Kline current, Kline prev1, Kline prev2) {
        boolean prev2IsBullish = prev2.getClosePrice().compareTo(prev2.getOpenPrice()) > 0;
        boolean currentIsBearish = current.getClosePrice().compareTo(current.getOpenPrice()) < 0;
        
        BigDecimal prev2Body = getBodySize(prev2);
        BigDecimal prev1Body = getBodySize(prev1);
        
        // 第1根：大阳线
        // 第2根：小实体（实体 < 第1根实体的30%）
        // 第3根：阴线，收盘在第1根中部以下
        boolean isSmallBody = prev1Body.compareTo(prev2Body.multiply(new BigDecimal("0.3"))) < 0;
        BigDecimal prev2Middle = prev2.getOpenPrice().add(prev2.getClosePrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        boolean closesBelowMiddle = current.getClosePrice().compareTo(prev2Middle) < 0;
        
        if (prev2IsBullish && isSmallBody && currentIsBearish && closesBelowMiddle) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.EVENING_STAR)
                    .direction(CandlePattern.Direction.BEARISH)
                    .strength(10)
                    .description("黄昏之星 - 强烈的顶部反转信号（三根K线）")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测启明星（Piercing Pattern）
     * 看涨穿刺
     */
    private CandlePattern detectPiercing(Kline current, Kline prev) {
        boolean prevIsBearish = prev.getClosePrice().compareTo(prev.getOpenPrice()) < 0;
        boolean currentIsBullish = current.getClosePrice().compareTo(current.getOpenPrice()) > 0;
        boolean gapDown = current.getOpenPrice().compareTo(prev.getLowPrice()) < 0;
        
        BigDecimal prevMiddle = prev.getOpenPrice().add(prev.getClosePrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        boolean closesAboveMiddle = current.getClosePrice().compareTo(prevMiddle) > 0;
        boolean notFullyEngulfs = current.getClosePrice().compareTo(prev.getOpenPrice()) < 0;
        
        if (prevIsBearish && currentIsBullish && gapDown && closesAboveMiddle && notFullyEngulfs) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.PIERCING)
                    .direction(CandlePattern.Direction.BULLISH)
                    .strength(7)
                    .description("启明星 - 看涨穿刺形态")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 检测乌云盖顶（Dark Cloud Cover）
     * 看跌覆盖
     */
    private CandlePattern detectDarkCloud(Kline current, Kline prev) {
        boolean prevIsBullish = prev.getClosePrice().compareTo(prev.getOpenPrice()) > 0;
        boolean currentIsBearish = current.getClosePrice().compareTo(current.getOpenPrice()) < 0;
        boolean gapUp = current.getOpenPrice().compareTo(prev.getHighPrice()) > 0;
        
        BigDecimal prevMiddle = prev.getOpenPrice().add(prev.getClosePrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        boolean closesBelowMiddle = current.getClosePrice().compareTo(prevMiddle) < 0;
        boolean notFullyEngulfs = current.getClosePrice().compareTo(prev.getOpenPrice()) > 0;
        
        if (prevIsBullish && currentIsBearish && gapUp && closesBelowMiddle && notFullyEngulfs) {
            return CandlePattern.builder()
                    .type(CandlePattern.PatternType.DARK_CLOUD)
                    .direction(CandlePattern.Direction.BEARISH)
                    .strength(7)
                    .description("乌云盖顶 - 看跌覆盖形态")
                    .build();
        }
        
        return CandlePattern.none();
    }
    
    /**
     * 计算实体大小
     */
    private BigDecimal getBodySize(Kline kline) {
        return kline.getClosePrice().subtract(kline.getOpenPrice()).abs();
    }
    
    /**
     * 计算K线全部范围
     */
    private BigDecimal getRange(Kline kline) {
        return kline.getHighPrice().subtract(kline.getLowPrice());
    }
    
    /**
     * 计算下影线长度
     */
    private BigDecimal getLowerShadow(Kline kline) {
        BigDecimal low = kline.getOpenPrice().min(kline.getClosePrice());
        return low.subtract(kline.getLowPrice());
    }
    
    /**
     * 计算上影线长度
     */
    private BigDecimal getUpperShadow(Kline kline) {
        BigDecimal high = kline.getOpenPrice().max(kline.getClosePrice());
        return kline.getHighPrice().subtract(high);
    }
    
    @Override
    public String getName() {
        return "CandlePattern";
    }
    
    @Override
    public int getRequiredPeriods() {
        // 需要至少3根K线来识别所有形态
        return 3;
    }
    
    @Override
    public String getDescription() {
        return "K线形态识别器 - 识别9种常见的K线反转形态";
    }
}
