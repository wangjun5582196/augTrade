package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.indicator.OBVCalculator;
import com.ltp.peter.augtrade.indicator.VWAPCalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * VWAP策略 - 日内短线核心策略
 * 
 * 基于VWAP的日内短线策略：
 * - VWAP是机构交易员的基准价格，具有天然的支撑/阻力作用
 * - 价格从下方回到VWAP = 做多机会（VWAP作为支撑）
 * - 价格持续在VWAP上方 = 多头占优，顺势做多
 * - 价格偏离VWAP超过±2σ = 超买/超卖，可能反转
 * 
 * 数据支撑（308笔交易分析）：
 * - 只做多：BUY方向盈利$1,597 vs SELL方向亏损$739
 * - 最优入场：强趋势(ADX≥40) + 量价确认 + VWAP上方
 * 
 * 权重：5（中等偏低，作为入场时机优化器）
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class VWAPStrategy implements Strategy {

    private static final String STRATEGY_NAME = "VWAP";
    private static final int STRATEGY_WEIGHT = 5;

    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().size() < 10) {
            return createHoldSignal("数据不足");
        }

        try {
            VWAPCalculator.VWAPResult vwap = context.getIndicator("VWAP");
            if (vwap == null) {
                log.debug("[{}] VWAP数据缺失", STRATEGY_NAME);
                return createHoldSignal("VWAP数据缺失");
            }

            OBVCalculator.OBVResult obv = context.getIndicator("OBV");
            double deviation = vwap.getDeviationPercent();
            
            log.debug("[{}] VWAP={}, 价格={}, 偏离={}%, 位置={}", 
                    STRATEGY_NAME,
                    String.format("%.2f", vwap.getVwap()),
                    String.format("%.2f", vwap.getCurrentPrice()),
                    String.format("%.3f", deviation),
                    vwap.getPositionDescription());

            // ========== 做多信号 ==========
            
            // 信号1：价格从VWAP-1σ下方反弹到VWAP附近（强力反弹，均值回归做多）
            // 适合场景：价格短暂跌破VWAP后恢复，说明多头力量强
            if (vwap.isPriceAboveVWAP() && deviation < 0.1 && deviation > 0) {
                // 价格刚刚从下方穿越VWAP
                boolean volumeConfirm = obv != null && (obv.isVolumeConfirmed() || obv.isObvAboveEma());
                if (volumeConfirm) {
                    log.info("[{}] 🎯 VWAP突破做多：价格刚穿越VWAP上方(偏离{}%), 量能确认",
                            STRATEGY_NAME, String.format("%.3f", deviation));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(75)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("VWAP突破做多(偏离%.3f%%, VWAP=%.2f, 量能确认)", 
                                    deviation, vwap.getVwap()))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                }
            }

            // 信号2：价格在VWAP上方，偏离适中（0.05%~0.3%），趋势延续
            // 最适合日内短线：不太远不太近，趋势方向明确
            if (vwap.isPriceAboveVWAP() && deviation >= 0.05 && deviation <= 0.3) {
                boolean volumeOK = obv == null || !obv.isBearishDivergence(); // 无看跌背离即可
                if (volumeOK) {
                    log.info("[{}] 📈 VWAP上方顺势做多：偏离{}%在理想区间",
                            STRATEGY_NAME, String.format("%.3f", deviation));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(65)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("VWAP上方趋势做多(偏离%.3f%%, VWAP=%.2f)", 
                                    deviation, vwap.getVwap()))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                } else {
                    log.warn("[{}] ⚠️ 价格在VWAP上方但量价背离，不做多", STRATEGY_NAME);
                    return createHoldSignal("VWAP上方但量价背离");
                }
            }

            // 信号3：价格极度偏离VWAP+2σ上方 = 超买，不追多
            if (vwap.isAboveUpperBand2()) {
                log.warn("[{}] ⚠️ 价格超买(偏离VWAP+2σ: {}%)，不追多",
                        STRATEGY_NAME, String.format("%.3f", deviation));
                return createHoldSignal(String.format("超买区(偏离%.3f%%>+2σ)", deviation));
            }

            // ========== 做空信号 ==========
            // 数据支撑: ADX≥30+EMA死叉+SELL = 100%胜率, +$45.50/笔
            // VWAP做空条件更严格：需要价格在VWAP下方 + 强趋势 + 量价确认
            
            // 做空信号1：价格从VWAP上方跌破到下方 + 量能确认
            if (vwap.isPriceBelowVWAP() && deviation > -0.1 && deviation < 0) {
                boolean volumeConfirm = obv != null && (obv.isVolumeConfirmed() || !obv.isObvAboveEma());
                Double adx = context.getIndicatorAsDouble("ADX");
                boolean strongTrend = adx != null && adx >= 30;
                
                if (volumeConfirm && strongTrend) {
                    log.info("[{}] 🔴 VWAP跌破做空：价格刚穿越VWAP下方(偏离{}%), ADX={}, 量能确认",
                            STRATEGY_NAME, String.format("%.3f", deviation), String.format("%.1f", adx));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(70)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("VWAP跌破做空(偏离%.3f%%, VWAP=%.2f, ADX=%.1f)", 
                                    deviation, vwap.getVwap(), adx))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                }
            }
            
            // 做空信号2：价格持续在VWAP下方（-0.05%~-0.3%）+ 强趋势
            if (vwap.isPriceBelowVWAP() && deviation <= -0.05 && deviation >= -0.3) {
                Double adx = context.getIndicatorAsDouble("ADX");
                boolean strongTrend = adx != null && adx >= 30;
                boolean noBullishDivergence = obv == null || !obv.isBullishDivergence();
                
                if (strongTrend && noBullishDivergence) {
                    log.info("[{}] 📉 VWAP下方顺势做空：偏离{}%, ADX={}",
                            STRATEGY_NAME, String.format("%.3f", deviation), String.format("%.1f", adx));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(60)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("VWAP下方趋势做空(偏离%.3f%%, VWAP=%.2f, ADX=%.1f)", 
                                    deviation, vwap.getVwap(), adx))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                }
            }
            
            // 价格极度偏离VWAP-2σ下方 = 超卖，不追空
            if (vwap.isBelowLowerBand2()) {
                return createHoldSignal(String.format("超卖区(偏离%.3f%%<-2σ)", deviation));
            }
            
            // VWAP下方但无做空条件：观望
            if (vwap.isPriceBelowVWAP()) {
                if (obv != null && obv.isBullishDivergence()) {
                    return createHoldSignal("VWAP下方，OBV看涨背离，等待确认");
                }
                return createHoldSignal(String.format("VWAP下方(偏离%.3f%%)，条件不足", deviation));
            }

            return createHoldSignal(String.format("VWAP偏离%.3f%%不在交易区间", deviation));

        } catch (Exception e) {
            log.error("[{}] 策略执行异常", STRATEGY_NAME, e);
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
        return "VWAP策略 - 基于成交量加权平均价的日内短线核心策略";
    }

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
