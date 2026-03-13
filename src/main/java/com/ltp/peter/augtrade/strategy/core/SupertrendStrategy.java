package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.indicator.OBVCalculator;
import com.ltp.peter.augtrade.indicator.SupertrendCalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Supertrend策略 - 日内短线趋势确认策略
 * 
 * 基于Supertrend（ATR趋势线）的趋势跟踪策略：
 * - Supertrend翻转看涨 + 量能确认 = 强力做多信号
 * - Supertrend持续看涨 = 趋势延续做多
 * - Supertrend翻转看跌 = 平仓/做空信号
 * 
 * 核心优势（对比EMA交叉）：
 * 1. Supertrend基于ATR，自动适应波动率变化
 * 2. Supertrend线本身就是动态止损位（不需要额外计算止损）
 * 3. 信号更少但更可靠（减少震荡期假信号）
 * 4. 特别适合5分钟级别的日内短线
 * 
 * 数据支撑：
 * - ADX≥40时做多平均盈利$77.83/笔
 * - 持仓<15分钟胜率90.3%
 * - Supertrend的翻转信号恰好捕捉这些高ADX转折点
 * 
 * 权重：8（较高，趋势确认的核心信号）
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class SupertrendStrategy implements Strategy {

    private static final String STRATEGY_NAME = "Supertrend";
    private static final int STRATEGY_WEIGHT = 8;

    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().size() < 15) {
            return createHoldSignal("数据不足");
        }

        try {
            SupertrendCalculator.SupertrendResult st = context.getIndicator("Supertrend");
            if (st == null) {
                log.debug("[{}] Supertrend数据缺失", STRATEGY_NAME);
                return createHoldSignal("Supertrend数据缺失");
            }

            OBVCalculator.OBVResult obv = context.getIndicator("OBV");
            Double adx = context.getIndicator("ADX");

            log.debug("[{}] Supertrend={}, 趋势={}, 翻转={}, 距离={}%",
                    STRATEGY_NAME,
                    String.format("%.2f", st.getSupertrendValue()),
                    st.getTrendDescription(),
                    st.isTrendChanged(),
                    String.format("%.3f", st.getDistancePercent()));

            // ========== 做多信号 ==========

            // 信号1（最强）: Supertrend刚翻转看涨 = 趋势起点，最佳入场时机
            if (st.isJustTurnedBullish()) {
                boolean volumeConfirm = obv != null && (obv.isVolumeConfirmed() || obv.isObvAboveEma());
                boolean strongTrend = adx != null && adx >= 25;

                if (volumeConfirm || strongTrend) {
                    int strength = 85;
                    if (volumeConfirm && strongTrend) strength = 95; // 三重确认

                    log.info("[{}] 🔥 Supertrend翻转做多！ADX={}, 量能={}, 止损位={}",
                            STRATEGY_NAME,
                            adx != null ? String.format("%.1f", adx) : "N/A",
                            obv != null ? obv.getVolumeDescription() : "N/A",
                            String.format("%.2f", st.getDynamicStopLoss()));

                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(strength)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("Supertrend翻转做多(ST=%.2f, ADX=%.1f, %s)",
                                    st.getSupertrendValue(),
                                    adx != null ? adx : 0,
                                    volumeConfirm ? "量能确认" : "趋势确认"))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .suggestedStopLoss(BigDecimal.valueOf(st.getDynamicStopLoss()))
                            .build();
                } else {
                    log.info("[{}] ⚠️ Supertrend翻转但缺少确认(ADX={}, 量能={})",
                            STRATEGY_NAME,
                            adx != null ? String.format("%.1f", adx) : "N/A",
                            obv != null ? obv.getVolumeDescription() : "N/A");
                    // 仍然给出信号，但强度较低
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(60)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("Supertrend翻转做多(未确认, ST=%.2f)",
                                    st.getSupertrendValue()))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .suggestedStopLoss(BigDecimal.valueOf(st.getDynamicStopLoss()))
                            .build();
                }
            }

            // 信号2: Supertrend持续看涨 + 价格距离Supertrend线不远（0.1%~0.5%）
            // = 回踩Supertrend支撑线的好入场点
            if (st.isUpTrend() && st.getDistancePercent() >= 0.1 && st.getDistancePercent() <= 0.5) {
                boolean noBearishDivergence = obv == null || !obv.isBearishDivergence();
                if (noBearishDivergence) {
                    log.info("[{}] 📈 Supertrend上升回踩支撑(距离{}%)",
                            STRATEGY_NAME, String.format("%.3f", st.getDistancePercent()));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(65)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("Supertrend上升回踩(距离%.3f%%, ST=%.2f)",
                                    st.getDistancePercent(), st.getSupertrendValue()))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .suggestedStopLoss(BigDecimal.valueOf(st.getDynamicStopLoss()))
                            .build();
                } else {
                    log.warn("[{}] ⚠️ Supertrend上升但OBV看跌背离，观望", STRATEGY_NAME);
                    return createHoldSignal("Supertrend上升但量价背离");
                }
            }

            // 信号3: Supertrend持续看涨 + 距离较远（>0.5%）= 趋势延续但不是最佳入场
            if (st.isUpTrend() && st.getDistancePercent() > 0.5) {
                // 只在ADX极强时才给信号
                if (adx != null && adx >= 40) {
                    log.info("[{}] 📊 Supertrend强趋势延续(距离{}%, ADX={})",
                            STRATEGY_NAME, String.format("%.3f", st.getDistancePercent()), String.format("%.1f", adx));
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(50)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("Supertrend强趋势(距离%.3f%%, ADX=%.1f)",
                                    st.getDistancePercent(), adx))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .suggestedStopLoss(BigDecimal.valueOf(st.getDynamicStopLoss()))
                            .build();
                }
                return createHoldSignal(String.format("Supertrend上升但距离过远(%.3f%%)", st.getDistancePercent()));
            }

            // ========== 做空信号 ==========
            // 数据支撑: ADX≥30+EMA死叉+SELL = 100%胜率, +$45.50/笔
            // 条件更严格：需要Supertrend翻转 + ADX强趋势 + 量能确认

            // 信号4: Supertrend刚翻转看跌 + 强趋势 = 做空信号
            if (st.isJustTurnedBearish()) {
                boolean strongDownTrend = adx != null && adx >= 30;
                boolean volumeConfirm = obv != null && (obv.isVolumeConfirmed() || !obv.isObvAboveEma());

                if (strongDownTrend && volumeConfirm) {
                    log.info("[{}] 🔴 Supertrend翻转做空！ADX={}, 量能={}, 止损位={}",
                            STRATEGY_NAME,
                            String.format("%.1f", adx),
                            obv != null ? obv.getVolumeDescription() : "N/A",
                            String.format("%.2f", st.getDynamicStopLoss()));

                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(80)
                            .strategyName(STRATEGY_NAME)
                            .reason(String.format("Supertrend翻转做空(ST=%.2f, ADX=%.1f, 量能确认)",
                                    st.getSupertrendValue(), adx))
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .suggestedStopLoss(BigDecimal.valueOf(st.getDynamicStopLoss()))
                            .build();
                } else {
                    log.warn("[{}] ⚠️ Supertrend翻转看跌但缺少确认(ADX={}, 需≥30)，观望",
                            STRATEGY_NAME, adx != null ? String.format("%.1f", adx) : "N/A");
                    return createHoldSignal("Supertrend翻转看跌但趋势不够强");
                }
            }

            // Supertrend下降趋势：不做多
            if (st.isDownTrend()) {
                return createHoldSignal(String.format("Supertrend下降趋势(ST=%.2f)", st.getSupertrendValue()));
            }

            return createHoldSignal("Supertrend无明确信号");

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
        return "Supertrend策略 - 基于ATR的趋势跟踪策略，兼具趋势判断与动态止损";
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
