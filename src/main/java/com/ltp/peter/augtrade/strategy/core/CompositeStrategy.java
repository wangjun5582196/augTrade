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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    // Layer 3 触发门槛
    // 日内短线修改：WR做多门槛从 -80 放宽到 -60
    // 原因：trendUp（ST+EMA双双向上）+ WR<-80 在5m图上几乎不共存——
    //   ST翻多时价格已回升，WR通常在-50~-70，-80永远等不到。
    //   日内回调到支撑位后，WR<-60 已足够确认短期超卖，继续等-80会错过所有买点。
    // SELL 维持 -20（ST+EMA向下 + WR接近0 仍合理共存）
    private static final double WR_OVERSOLD_TRIGGER   = -60.0; // < -60 才允许做多（日内超卖）
    private static final double WR_OVERBOUGHT_TRIGGER = -20.0; // > -20 才允许做空（极端超买）

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
        //帮我将这个集合里面的数据按照时间顺序排序
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
        // ══════════════════════════════════════════════════════
        // 时段 & 节假日过滤
        // ══════════════════════════════════════════════════════
        // 使用最新 K 线的时间戳判断，确保回测与实盘行为一致
        LocalDateTime klineTime = klines.get(0).getTimestamp();
        // K线时间戳以 ZoneId.systemDefault() 存储（BinanceHistoricalDataFetcher），必须用 systemDefault() 还原时区
        int hourUtc = (klineTime != null)
                ? klineTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("UTC")).getHour()
                : ZonedDateTime.now(ZoneId.of("UTC")).getHour();
        // UTC 13:00-17:00（北京时间 21:00-01:00）为美欧重叠交易时段：波动大，方向性差
        boolean isHighNoiseSession = (hourUtc >= 13 && hourUtc < 17);
        double effectiveAdxThreshold = isHighNoiseSession ? 40.0 : ADX_THRESHOLD;

        // 节假日过滤：圣诞节（12/24-12/26）、元旦（12/31-1/2）、感恩节（美）
        // 这些日期黄金流动性极低，止损宽度被动放大，假突破概率大幅提升
        if (klineTime != null) {
            int month = klineTime.getMonthValue();
            int day   = klineTime.getDayOfMonth();
            boolean isHolidayPeriod =
                    (month == 12 && day >= 24 && day <= 26) ||  // 圣诞节
                    (month == 12 && day == 31) ||               // 元旦前夜
                    (month == 1  && day <= 2);                  // 元旦
            if (isHolidayPeriod) {
                log.info("[Composite] ⛔ 节假日低流动性期间（{}/{}），禁止开仓", month, day);
                return hold(String.format("节假日低流动性(%d/%d)，禁止开仓", month, day), 0, 0);
            }
        }

        // ══════════════════════════════════════════════════════
        // LAYER 2：趋势方向 — ADX + Supertrend + EMA 三者一致
        // ══════════════════════════════════════════════════════

        Double adx = context.getIndicator("ADX");
        if (adx == null || adx < effectiveAdxThreshold) {
            log.info("[Composite] Layer2 ❌ ADX={} < {} 趋势太弱{}", adx,
                    effectiveAdxThreshold, isHighNoiseSession ? "（美欧重叠高噪音时段）" : "");
            return hold(String.format("趋势太弱ADX=%.1f(需≥%.0f%s)",
                    adx != null ? adx : 0, effectiveAdxThreshold,
                    isHighNoiseSession ? ",高噪音时段" : ""), 0, 0);
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

        // 宏观趋势（用于后续方向锁 + 放宽做多条件）
        String macroTrend = context.getIndicator("MacroTrend");
        boolean isMacroBull = "MACRO_BULL".equals(macroTrend) || "MACRO_UP".equals(macroTrend);
        boolean isMacroBear = "MACRO_BEAR".equals(macroTrend) || "MACRO_DOWN".equals(macroTrend);

        boolean trendUp   = st.isUpTrend()   && ema.isUpTrend();
        boolean trendDown = st.isDownTrend()  && ema.isDownTrend();

        // 牛市回调做多：MACRO_BULL 环境 + EMA 向上（慢趋势仍多）+ ST 短暂翻下（局部回调）
        // 这是经典的"顺势回调买点"——不要求 ST 也向上，放宽 Layer 2 以捕捉回调入场机会
        boolean trendUpRelaxed = isMacroBull && ema.isUpTrend() && st.isDownTrend();

        // HMA 冲突 → 直接拒绝（数据：HMA/ST冲突均值亏损 -$88.70/单）
        // relaxed BUY（ST下 + EMA上）也受 HMA 保护：HMA 向下则说明回调过深，等待
        if (hma != null && !"FLAT".equals(hma.getTrend())) {
            boolean hmaUp = "UP".equals(hma.getTrend());
            if ((trendUp || trendUpRelaxed) && !hmaUp) {
                log.warn("[Composite] Layer2 ❌ HMA=DOWN 与做多方向冲突，拒绝");
                return hold("HMA下跌与做多方向冲突", 0, 0);
            }
            if (trendDown && hmaUp) {
                log.warn("[Composite] Layer2 ❌ HMA=UP 与做空方向冲突，拒绝");
                return hold("HMA上涨与做空方向冲突", 0, 0);
            }
        }

        if (!trendUp && !trendDown && !trendUpRelaxed) {
            log.info("[Composite] Layer2 ❌ 无一致趋势方向 (ST={}, EMA={}, MacroTrend={})",
                    st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN",
                    macroTrend != null ? macroTrend : "N/A");
            return hold(String.format("ST(%s)与EMA(%s)方向不一致",
                    st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN"), 0, 0);
        }

        // ── 宏观趋势方向锁 ──
        // MACRO_BULL: 强牛市中做空须确认 24h 正在真实回调（< -0.3%），纯横盘/微涨时禁止做空
        // MACRO_BEAR: 强熊市禁止做多（含放宽的回调做多）
        // MACRO_NEUTRAL 但 7d+24h 均上涨：同样视为潜在牛市，做空须有 24h 回调确认
        if (isMacroBull) {
            if (trendDown) {
                Double change24h = context.getIndicator("MacroPriceChange24h");
                boolean activelyPullingBack = change24h != null && change24h < -0.3;
                if (!activelyPullingBack) {
                    log.info("[Composite] Layer2 ❌ 宏观{}但24h未回调({}%)，禁止做空", macroTrend,
                            String.format("%.2f", change24h != null ? change24h : 0.0));
                    return hold(String.format("宏观%s且24h未回调(%.2f%%)，禁止做空",
                            macroTrend, change24h != null ? change24h : 0.0), 0, 0);
                }
                log.info("[Composite] MACRO_BULL 但 24h 回调 {}%，允许做空", String.format("%.2f", change24h));
            }
        } else if (isMacroBear) {
            if (trendUp || trendUpRelaxed) {
                log.info("[Composite] Layer2 ❌ 宏观趋势{}，禁止做多", macroTrend);
                return hold("宏观趋势" + macroTrend + "，禁止做多", 0, 0);
            }
        } else if ("MACRO_NEUTRAL".equals(macroTrend)) {
            // NEUTRAL 但 7d+24h 双正 → 暗含上涨偏向，做空同样需要 24h 回调确认
            Double change7d  = context.getIndicator("MacroPriceChange7d");
            Double change24h = context.getIndicator("MacroPriceChange24h");
            if (trendDown && change7d != null && change7d > 0.5
                    && change24h != null && change24h > 0) {
                log.info("[Composite] Layer2 ❌ NEUTRAL但7d({}%)和24h({}%)均上涨，禁止做空",
                        String.format("%.2f", change7d), String.format("%.2f", change24h));
                return hold(String.format("NEUTRAL但7d+%.2f%%/24h+%.2f%%上涨，禁止做空",
                        change7d, change24h), 0, 0);
            }
        }

        log.info("[Composite] Layer2 ✅ 趋势确认 {} (ADX={}, ST={}, EMA={}, MacroTrend={}, relaxedBuy={})",
                (trendUp || trendUpRelaxed) ? "做多" : "做空", String.format("%.1f", adx),
                st.isUpTrend() ? "UP" : "DOWN", ema.isUpTrend() ? "UP" : "DOWN",
                macroTrend != null ? macroTrend : "N/A", trendUpRelaxed);

        // ══════════════════════════════════════════════════════
        // LAYER 3：入场触发 — WR 极值 + K 线形态确认
        // ══════════════════════════════════════════════════════

        Double wr = context.getIndicator("WilliamsR");
        CandlePattern pattern = context.getIndicator("CandlePattern");

        // 计算 ATR（用于 TP/SL）
        double atrValue = computeATR(klines);

        // ADX 自适应 WR 阈值（量化研究：强趋势回调通常不足 -70，固定阈值会错过入场点）
        // ADX ≥ 40（强趋势）：做多放宽至 -50，做空收紧至 -25（强趋势中不急于逆势）
        // ADX 30-40（中等趋势）：使用默认阈值 -60（做多）/ -20（做空）
        double wrBuyThreshold  = adx >= 40 ? -50.0 : WR_OVERSOLD_TRIGGER;   // 正常做多阈值
        double wrSellThreshold = adx >= 40 ? -25.0 : WR_OVERBOUGHT_TRIGGER; // 正常做空阈值

        // ── 做多条件：（正常趋势 or 牛市回调放宽）+ 价格在支撑区 ──
        if ((trendUp || trendUpRelaxed) && atSupport) {
            // 放宽条件（ST翻下但EMA仍上）要求比正常条件更深超卖（再收紧 10 点）
            double wrThreshold = trendUpRelaxed ? (wrBuyThreshold - 10.0) : wrBuyThreshold;
            if (wr == null || wr > wrThreshold) {
                log.info("[Composite] Layer3 ❌ 在支撑区等待WR超卖 WR={}(需<{}，{})",
                        String.format("%.1f", wr != null ? wr : 0), String.format("%.0f", wrThreshold),
                        trendUpRelaxed ? "牛市回调模式" : "正常模式");
                return hold(String.format("等待WR超卖(WR=%.1f,需<%.0f)", wr != null ? wr : 0, wrThreshold), 0, 0);
            }

            // 在支撑位出现看跌形态 → 谨慎，等待
            if (pattern != null && pattern.hasPattern()
                    && pattern.getDirection() == CandlePattern.Direction.BEARISH
                    && pattern.getStrength() >= 8) {
                log.warn("[Composite] Layer3 ❌ 在支撑区出现强烈看跌形态({})，等待", pattern.getType());
                return hold("支撑区出现看跌形态，等待确认", 0, 0);
            }

            log.info("[Composite] Layer3 ✅ 做多触发 WR={}, 形态={}, 模式={}",
                    String.format("%.1f", wr),
                    pattern != null && pattern.hasPattern() ? pattern.getType() : "无",
                    trendUpRelaxed ? "牛市回调" : "正常");

            return buildSignal(TradingSignal.SignalType.BUY, context, levels, atrValue, wr, adx, pattern, hma, st);
        }

        // ── 做空条件：趋势向下 + 价格在阻力区 ──
        if (trendDown && atResistance) {
            if (wr == null || wr < wrSellThreshold) {
                log.info("[Composite] Layer3 ❌ 在阻力区等待WR超买 WR={}(需>{}, ADX={})",
                        String.format("%.1f", wr != null ? wr : 0), String.format("%.0f", wrSellThreshold),
                        String.format("%.1f", adx));
                return hold(String.format("等待WR超买(WR=%.1f,需>%.0f)", wr != null ? wr : 0, wrSellThreshold), 0, 0);
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
        if ((trendUp || trendUpRelaxed) && atResistance) {
            return hold("做多方向但价格在阻力位，等待突破确认或回调至支撑", 0, 0);
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

        // 关键位来源数量过滤：单一整数关口（Round25/Round50）或单个摆动点可靠性极低（胜率仅9%）
        // 必须有至少2个独立来源共同确认，才视为有效的 S/R 位
        KeyLevelCalculator.KeyLevel relevantLevel = isBuy ? levels.getNearestSupport() : levels.getNearestResistance();
        if (relevantLevel != null) {
            int sourceCount = relevantLevel.getSource().split("\\+").length;
            if (sourceCount < 2) {
                log.info("[Composite] ⛔ 关键位仅有单一来源({})，拒绝入场（历史胜率9%）",
                        relevantLevel.getSource());
                return hold("单一关键位来源(" + relevantLevel.getSource() + ")，置信度不足", 0, 0);
            }
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
