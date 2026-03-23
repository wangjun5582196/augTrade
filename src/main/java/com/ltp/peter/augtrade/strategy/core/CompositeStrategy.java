package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.*;
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
 * 组合策略 — 三层串联入场过滤器
 *
 * 重构自原加权投票体系（v2 → v3）。
 *
 * 核心逻辑（三层必须全部通过才开仓）：
 *
 *   Layer 1 【价格结构】：价格是否在关键支撑/阻力区域？
 *     → 距支撑或阻力 ≤ 0.35%，否则 HOLD
 *     → 依赖 KeyLevelCalculator 计算的多层次 S/R 位
 *
 *   Layer 2 【趋势方向】：大方向是否一致且足够强？
 *     → ADX ≥ 30
 *     → Supertrend 方向 = EMA 方向（两者一致）
 *     → HMA 不得与方向冲突（HMA 冲突 → 直接 HOLD）
 *
 *   Layer 3 【入场触发】：当前是否有低风险入场机会？
 *     → 做多：WR < -70（超卖确认）+ 无看跌 K 线形态
 *     → 做空：WR > -30（超买确认）+ 无看涨 K 线形态
 *
 *   TP/SL 规则：
 *     → SL = 关键位 ± 1.5 × ATR（止损在支撑/阻力另一侧）
 *     → TP = 下一个关键位（确保 TP:SL ≥ 2:1）
 *     → 不满足 2:1 则降为 HOLD（赔率不够，不值得入场）
 *
 * 与 v2 的核心区别：
 *   - 不再追随动量信号（放量/强动量加分 → 废弃）
 *   - WR 从投票者变为入场触发器（极值区才有意义）
 *   - 价格结构前置过滤（不在 S/R 区域 → 不开仓）
 *
 * @author Peter Wang
 */
@Slf4j
@Service
public class CompositeStrategy implements Strategy {

    private static final String STRATEGY_NAME = "Composite";
    private static final int STRATEGY_WEIGHT = 10;

    // Layer 2 门槛
    private static final double ADX_THRESHOLD = 30.0;

    // Layer 3 触发门槛（数据验证：WR极值区胜率83-100%，中性区33-67%）
    private static final double WR_OVERSOLD_TRIGGER  = -70.0;  // < -70 才允许做多
    private static final double WR_OVERBOUGHT_TRIGGER = -30.0; // > -30 才允许做空

    // TP:SL 最低赔率要求
    private static final double MIN_REWARD_RISK_RATIO = 2.0;

    // ATR 止损倍数（SL = 关键位另一侧 1.5×ATR）
    private static final double SL_ATR_MULTIPLIER = 1.5;
    // TP 至少 3×ATR（无明确关键位时的兜底）
    private static final double TP_ATR_MULTIPLIER  = 3.0;

    @Autowired(required = false)
    private List<Strategy> strategies; // 保留注入以兼容 getActiveStrategies() API

    @Autowired(required = false)
    private ATRCalculator atrCalculator;

    @Autowired(required = false)
    private HMACalculator hmaCalculator;

    // ─────────────────────────────────────────────────────────
    // 主入口
    // ─────────────────────────────────────────────────────────

    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            return hold("数据不足", 0, 0);
        }

        List<Kline> klines = context.getKlines();
        double currentPrice = context.getCurrentPrice().doubleValue();

        // ══════════════════════════════════════════════════════
        // LAYER 1：价格结构 — 是否在关键 S/R 位附近？
        // ══════════════════════════════════════════════════════

        KeyLevelCalculator.KeyLevelResult levels = context.getIndicator("KeyLevels");
        if (levels == null) {
            return hold("关键位数据缺失，跳过", 0, 0);
        }

        boolean atSupport    = levels.isAtSupport();
        boolean atResistance = levels.isAtResistance();

        if (!atSupport && !atResistance) {
            log.info("[Composite] Layer1 ❌ 价格不在关键位附近 (距支撑{}%, 距阻力{}%)",
                    String.format("%.3f", levels.getDistanceToSupportPercent()),
                    String.format("%.3f", levels.getDistanceToResistancePercent()));
            return hold(String.format("价格不在关键位(支撑距%.2f%%, 阻力距%.2f%%)",
                    levels.getDistanceToSupportPercent(), levels.getDistanceToResistancePercent()), 0, 0);
        }

        log.info("[Composite] Layer1 ✅ 价格在{}区域 (支撑:{}, 阻力:{})",
                atSupport ? "支撑" : "阻力",
                levels.getNearestSupport() != null ? String.format("%.2f", levels.getNearestSupport().getPrice()) : "无",
                levels.getNearestResistance() != null ? String.format("%.2f", levels.getNearestResistance().getPrice()) : "无");

        // ══════════════════════════════════════════════════════
        // LAYER 2：趋势方向 — ADX + Supertrend + EMA 三者一致
        // ══════════════════════════════════════════════════════

        Double adx = context.getIndicator("ADX");
        if (adx == null || adx < ADX_THRESHOLD) {
            log.info("[Composite] Layer2 ❌ ADX={} < {} 趋势太弱", adx, ADX_THRESHOLD);
            return hold(String.format("趋势太弱ADX=%.1f(需≥%.0f)", adx != null ? adx : 0, ADX_THRESHOLD), 0, 0);
        }

        SupertrendCalculator.SupertrendResult st = context.getIndicator("Supertrend");
        EMACalculator.EMATrend ema = context.getIndicator("EMATrend");

        if (st == null || ema == null) {
            return hold("Supertrend或EMA数据缺失", 0, 0);
        }

        // 计算 HMA 趋势（若未在 Orchestrator 计算则在此补算）
        HMACalculator.HMAResult hma = context.getIndicator("HMA");
        if (hma == null && hmaCalculator != null) {
            hma = hmaCalculator.calculate(klines);
            if (hma != null) context.addIndicator("HMA", hma);
        }

        boolean trendUp   = st.isUpTrend()   && ema.isUpTrend();
        boolean trendDown = st.isDownTrend()  && ema.isDownTrend();

        // HMA 冲突 → 直接拒绝（数据：HMA/ST冲突均值亏损 -$88.70/单）
        if (hma != null && !"FLAT".equals(hma.getTrend())) {
            boolean hmaUp = "UP".equals(hma.getTrend());
            if (trendUp && !hmaUp) {
                log.warn("[Composite] Layer2 ❌ HMA=DOWN 与上涨趋势冲突，拒绝");
                return hold("HMA下跌与上涨趋势冲突", 0, 0);
            }
            if (trendDown && hmaUp) {
                log.warn("[Composite] Layer2 ❌ HMA=UP 与下跌趋势冲突，拒绝");
                return hold("HMA上涨与下跌趋势冲突", 0, 0);
            }
        }

        if (!trendUp && !trendDown) {
            log.info("[Composite] Layer2 ❌ Supertrend({}) 与 EMA({}) 方向不一致",
                    st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN");
            return hold(String.format("ST(%s)与EMA(%s)方向不一致",
                    st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN"), 0, 0);
        }

        log.info("[Composite] Layer2 ✅ 趋势确认 {} (ADX={}, ST={}, EMA={})",
                trendUp ? "做多" : "做空", String.format("%.1f", adx),
                st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN");

        // ══════════════════════════════════════════════════════
        // LAYER 3：入场触发 — WR 极值 + K 线形态确认
        // ══════════════════════════════════════════════════════

        Double wr = context.getIndicator("WilliamsR");
        CandlePattern pattern = context.getIndicator("CandlePattern");

        // 计算 ATR（用于 TP/SL）
        double atrValue = computeATR(klines);

        // ── 做多条件：趋势向上 + 价格在支撑区 ──
        if (trendUp && atSupport) {
            if (wr == null || wr > WR_OVERSOLD_TRIGGER) {
                log.info("[Composite] Layer3 ❌ 在支撑区等待WR超卖 WR={}(需<{})",
                        String.format("%.1f", wr != null ? wr : 0), String.format("%.0f", WR_OVERSOLD_TRIGGER));
                return hold(String.format("等待WR超卖(WR=%.1f,需<%.0f)", wr != null ? wr : 0, WR_OVERSOLD_TRIGGER), 0, 0);
            }

            // 在支撑位出现看跌形态 → 谨慎，等待
            if (pattern != null && pattern.hasPattern()
                    && pattern.getDirection() == CandlePattern.Direction.BEARISH
                    && pattern.getStrength() >= 8) {
                log.warn("[Composite] Layer3 ❌ 在支撑区出现强烈看跌形态({})，等待", pattern.getType());
                return hold("支撑区出现看跌形态，等待确认", 0, 0);
            }

            log.info("[Composite] Layer3 ✅ 做多触发 WR={}, 形态={}", String.format("%.1f", wr),
                    pattern != null && pattern.hasPattern() ? pattern.getType() : "无");

            return buildSignal(TradingSignal.SignalType.BUY, context, levels, atrValue, wr, adx, pattern, hma, st);
        }

        // ── 做空条件：趋势向下 + 价格在阻力区 ──
        if (trendDown && atResistance) {
            if (wr == null || wr < WR_OVERBOUGHT_TRIGGER) {
                log.info("[Composite] Layer3 ❌ 在阻力区等待WR超买 WR={}(需>{})",
                        String.format("%.1f", wr != null ? wr : 0), String.format("%.0f", WR_OVERBOUGHT_TRIGGER));
                return hold(String.format("等待WR超买(WR=%.1f,需>%.0f)", wr != null ? wr : 0, WR_OVERBOUGHT_TRIGGER), 0, 0);
            }

            // 在阻力位出现看涨形态 → 谨慎，等待
            if (pattern != null && pattern.hasPattern()
                    && pattern.getDirection() == CandlePattern.Direction.BULLISH
                    && pattern.getStrength() >= 8) {
                log.warn("[Composite] Layer3 ❌ 在阻力区出现强烈看涨形态({})，等待", pattern.getType());
                return hold("阻力区出现看涨形态，等待确认", 0, 0);
            }

            log.info("[Composite] Layer3 ✅ 做空触发 WR={}, 形态={}", String.format("%.1f", wr),
                    pattern != null && pattern.hasPattern() ? pattern.getType() : "无");

            return buildSignal(TradingSignal.SignalType.SELL, context, levels, atrValue, wr, adx, pattern, hma, st);
        }

        // 趋势方向与价格位置不匹配（上涨但在阻力区 / 下跌但在支撑区）
        if (trendUp && atResistance) {
            return hold("上涨趋势但价格在阻力位，等待突破确认或回调至支撑", 0, 0);
        }
        if (trendDown && atSupport) {
            return hold("下跌趋势但价格在支撑位，等待破位确认或反弹至阻力", 0, 0);
        }

        return hold("无匹配入场条件", 0, 0);
    }

    // ─────────────────────────────────────────────────────────
    // 生成交易信号（含 TP/SL 计算）
    // ─────────────────────────────────────────────────────────

    private TradingSignal buildSignal(
            TradingSignal.SignalType type,
            MarketContext context,
            KeyLevelCalculator.KeyLevelResult levels,
            double atrValue,
            Double wr,
            Double adx,
            CandlePattern pattern,
            HMACalculator.HMAResult hma,
            SupertrendCalculator.SupertrendResult st) {

        double currentPrice = context.getCurrentPrice().doubleValue();
        boolean isBuy = type == TradingSignal.SignalType.BUY;

        // ── 止损计算 ──
        double slDistance;
        double slPrice;
        if (isBuy) {
            // SL 放在支撑位下方 1.5×ATR
            double supportPrice = levels.getNearestSupport() != null
                    ? levels.getNearestSupport().getPrice() : currentPrice;
            slDistance = (currentPrice - supportPrice) + atrValue * SL_ATR_MULTIPLIER;
            slPrice    = currentPrice - slDistance;
        } else {
            // SL 放在阻力位上方 1.5×ATR
            double resistancePrice = levels.getNearestResistance() != null
                    ? levels.getNearestResistance().getPrice() : currentPrice;
            slDistance = (resistancePrice - currentPrice) + atrValue * SL_ATR_MULTIPLIER;
            slPrice    = currentPrice + slDistance;
        }
        slDistance = Math.max(slDistance, atrValue * 1.0); // 最小 1×ATR 止损

        // ── 止盈计算 ──
        // 优先用下一个关键位作为 TP，确保赔率 ≥ 2:1
        double tpPrice;
        double tpDistance;

        if (isBuy) {
            // TP 目标：第二阻力位（如果能达到 2:1），否则 3×ATR
            double nextResistance = levels.getSecondResistance() != null
                    ? levels.getSecondResistance().getPrice()
                    : (levels.getNearestResistance() != null ? levels.getNearestResistance().getPrice() : 0);

            if (nextResistance > currentPrice) {
                tpDistance = nextResistance - currentPrice;
                if (tpDistance < slDistance * MIN_REWARD_RISK_RATIO) {
                    // 第二阻力位也不够 2:1，降级用 3×ATR
                    tpDistance = atrValue * TP_ATR_MULTIPLIER;
                }
            } else {
                tpDistance = atrValue * TP_ATR_MULTIPLIER;
            }
            tpPrice = currentPrice + tpDistance;
        } else {
            // TP 目标：第二支撑位（如果能达到 2:1），否则 3×ATR
            double nextSupport = levels.getSecondSupport() != null
                    ? levels.getSecondSupport().getPrice()
                    : (levels.getNearestSupport() != null ? levels.getNearestSupport().getPrice() : 0);

            if (nextSupport > 0 && nextSupport < currentPrice) {
                tpDistance = currentPrice - nextSupport;
                if (tpDistance < slDistance * MIN_REWARD_RISK_RATIO) {
                    tpDistance = atrValue * TP_ATR_MULTIPLIER;
                }
            } else {
                tpDistance = atrValue * TP_ATR_MULTIPLIER;
            }
            tpPrice = currentPrice - tpDistance;
        }

        double rrRatio = tpDistance / slDistance;

        // 赔率不足 2:1 → 拒绝入场
        if (rrRatio < MIN_REWARD_RISK_RATIO) {
            log.warn("[Composite] ⛔ TP:SL={} < {}，赔率不足，放弃入场 (TP={}, SL={})",
                    String.format("%.2f", rrRatio), String.format("%.1f", MIN_REWARD_RISK_RATIO),
                    String.format("%.2f", tpPrice), String.format("%.2f", slPrice));
            return hold(String.format("赔率TP:SL=%.2f不足%.1f，放弃", rrRatio, MIN_REWARD_RISK_RATIO), 0, 0);
        }

        // ── 信号强度（简洁版，不再用加权投票）──
        int strength = computeStrength(adx, wr, isBuy, pattern, st, levels);

        // ── 构建原因说明 ──
        String nearLevel = isBuy
                ? (levels.getNearestSupport() != null ? String.format("%.2f", levels.getNearestSupport().getPrice()) : "N/A")
                : (levels.getNearestResistance() != null ? String.format("%.2f", levels.getNearestResistance().getPrice()) : "N/A");
        String levelSource = isBuy
                ? (levels.getNearestSupport() != null ? levels.getNearestSupport().getSource() : "N/A")
                : (levels.getNearestResistance() != null ? levels.getNearestResistance().getSource() : "N/A");

        String reason = String.format(
                "%s@%s[%s] WR=%.1f ADX=%.1f TP:SL=%.1f%s",
                isBuy ? "支撑做多" : "阻力做空",
                nearLevel, levelSource, wr, adx, rrRatio,
                pattern != null && pattern.hasPattern() ? " " + pattern.getType().getDescription() : "");

        log.info("[Composite] 📊 生成{}信号 入场:{} SL:{} TP:{} TP:SL={} 强度:{}",
                isBuy ? "做多" : "做空",
                String.format("%.2f", currentPrice), String.format("%.2f", slPrice),
                String.format("%.2f", tpPrice), String.format("%.2f", rrRatio), strength);

        return TradingSignal.builder()
                .type(type)
                .strength(strength)
                .score(strength)
                .buyScore(isBuy ? strength : 0)
                .sellScore(isBuy ? 0 : strength)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .symbol(context.getSymbol())
                .currentPrice(context.getCurrentPrice())
                .suggestedStopLoss(BigDecimal.valueOf(slPrice).setScale(2, RoundingMode.HALF_UP))
                .suggestedTakeProfit(BigDecimal.valueOf(tpPrice).setScale(2, RoundingMode.HALF_UP))
                .williamsR(wr)
                .adx(adx)
                .supertrendValue(BigDecimal.valueOf(st.getSupertrendValue()))
                .supertrendDirection(st.isUpTrend() ? "UP" : "DOWN")
                .signalGenerateTime(LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // 信号强度（基于确认质量）
    // ─────────────────────────────────────────────────────────

    private int computeStrength(Double adx, Double wr, boolean isBuy,
                                 CandlePattern pattern,
                                 SupertrendCalculator.SupertrendResult st,
                                 KeyLevelCalculator.KeyLevelResult levels) {
        int score = 50; // 基础分：三层都过了就是 50 起

        // ADX 强度加分
        if (adx != null) {
            if (adx >= 50) score += 20;
            else if (adx >= 40) score += 15;
            else if (adx >= 30) score += 10;
        }

        // WR 极值程度加分
        if (wr != null) {
            if (isBuy && wr < -85) score += 15;
            else if (isBuy && wr < -70) score += 8;
            else if (!isBuy && wr > -15) score += 15;
            else if (!isBuy && wr > -30) score += 8;
        }

        // Supertrend 刚翻转加分（最佳入场时机）
        if (st != null && st.isTrendChanged()) score += 10;

        // 关键位强度加分
        KeyLevelCalculator.KeyLevel relevantLevel = isBuy
                ? levels.getNearestSupport() : levels.getNearestResistance();
        if (relevantLevel != null && relevantLevel.getStrength() == KeyLevelCalculator.LevelStrength.STRONG) {
            score += 10;
        }

        // 确认 K 线形态加分
        if (pattern != null && pattern.hasPattern()) {
            boolean bullishPattern = pattern.getDirection() == CandlePattern.Direction.BULLISH;
            if (isBuy && bullishPattern) score += 5;
            else if (!isBuy && !bullishPattern) score += 5;
        }

        return Math.min(score, 100);
    }

    // ─────────────────────────────────────────────────────────
    // ATR 计算（14 根 K 线）
    // ─────────────────────────────────────────────────────────

    private double computeATR(List<Kline> klines) {
        int period = Math.min(14, klines.size() - 1);
        if (period < 3) return 10.0; // 兜底默认值

        double atrSum = 0;
        for (int i = 0; i < period; i++) {
            Kline cur  = klines.get(i);
            Kline prev = klines.get(i + 1);
            double high  = cur.getHighPrice().doubleValue();
            double low   = cur.getLowPrice().doubleValue();
            double close = prev.getClosePrice().doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - close), Math.abs(low - close)));
            atrSum += tr;
        }
        double atr = atrSum / period;
        log.debug("[Composite] ATR(14)={}", String.format("%.3f", atr));
        return atr;
    }

    // ─────────────────────────────────────────────────────────
    // HOLD 信号
    // ─────────────────────────────────────────────────────────

    private TradingSignal hold(String reason, int buyScore, int sellScore) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .buyScore(buyScore)
                .sellScore(sellScore)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // 兼容性 API（供 Controller/API 调用）
    // ─────────────────────────────────────────────────────────

    public List<Strategy> getActiveStrategies() {
        if (strategies == null) return new ArrayList<>();
        return strategies.stream()
                .filter(s -> !s.getName().equals(STRATEGY_NAME))
                .filter(Strategy::isEnabled)
                .collect(Collectors.toList());
    }

    public int getTotalWeight() {
        return getActiveStrategies().stream().mapToInt(Strategy::getWeight).sum();
    }

    @Override
    public String getName() { return STRATEGY_NAME; }

    @Override
    public int getWeight() { return STRATEGY_WEIGHT; }

    @Override
    public String getDescription() {
        return "三层串联过滤策略 v3 - 价格结构(S/R位) + 趋势方向(ADX/ST/EMA) + 入场触发(WR极值)";
    }
}
