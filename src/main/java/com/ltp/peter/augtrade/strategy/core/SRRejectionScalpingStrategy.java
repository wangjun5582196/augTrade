package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.ATRCalculator;
import com.ltp.peter.augtrade.indicator.EMACalculator;
import com.ltp.peter.augtrade.indicator.KeyLevelCalculator;
import com.ltp.peter.augtrade.indicator.StochRSICalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * S/R 拒绝剥头皮策略（SR Rejection Scalping）
 *
 * 核心逻辑：价格行为 + 宏观方向锁，不逆宏观趋势交易。
 *
 * 入场条件（三步必须同时满足）：
 *   Step 0【宏观过滤】：MACRO_BULL 且 24h > 0 → 禁止做空；MACRO_BEAR 且 24h < 0 → 禁止做多
 *   Step 1【触位】：最近 N 根 K 线中，至少一根的 High/Low 真正触及关键 S/R 位
 *     做空：K 线最高价 必须 >= 阻力位（不允许高价低于阻力位的假触位）
 *     做多：K 线最低价 必须 <= 支撑位（不允许低价高于支撑位的假触位）
 *   Step 2【拒绝】：该触位 K 线形成"影线拒绝"形态（影线/振幅 ≥ 50%）
 *
 * SL/TP：
 *   做空 SL = max(wickExtreme, resistanceLevel) + 0.8×ATR（止损放在真实阻力上方）
 *   做多 SL = min(wickExtreme, supportLevel) - 0.8×ATR（止损放在真实支撑下方）
 *   做空 TP = 最近支撑位（若不足 2:1 则 fallback 到 2.5×ATR）
 *   做多 TP = 最近阻力位（若不足 2:1 则 fallback 到 2.5×ATR）
 *   最低赔率要求：2.0:1
 *
 * 注意：isEnabled() = false，不参与 CompositeStrategy 的投票。
 *       通过 StrategyOrchestrator.generateSignalWithStrategy() 或
 *       BacktestService(strategyName=SR_REJECTION) 单独调用。
 *
 * @author Peter Wang
 */
@Slf4j
@Service
public class SRRejectionScalpingStrategy implements Strategy {

    public static final String STRATEGY_NAME = "SR_REJECTION_SCALPING";
    private static final int STRATEGY_WEIGHT = 10;

    /**
     * 触位容差（单向缓冲，仅用于防止浮点误差）
     * 做空：K线最高价必须 >= 阻力位 - 容差（即真实触及阻力位）
     * 做多：K线最低价必须 <= 支撑位 + 容差（即真实触及支撑位）
     * 0.05% = $2.4（@$4800），防止浮点误差和极小滑点
     */
    private static final double TOUCH_THRESHOLD_PCT = 0.0005;

    /**
     * 影线/振幅最小比例（提高至 55%）
     * 回测实证：35% 阈值捞到大量劣质信号（胜率14-18%），50% 后信号更纯粹
     * 高质量信号特征：影线比 ≥52%（来自 PnL>$400 的大赢单统计），55% 为更严格的质量门槛
     */
    private static final double WICK_RATIO_THRESHOLD = 0.55;

    /**
     * 往前看 K 线数（从3根缩减至2根）
     * 回测实证：lookback=3 导致大量"过期拒绝"被重复触发
     */
    private static final int REJECTION_LOOKBACK = 2;

    /**
     * 是否要求"确认K线"过滤（软过滤：只过滤拒绝信号已被突破的情形）
     *
     * 当拒绝K线为 klines[1]（非最新）时：
     *   做空：klines[0] 不能收盘高于阻力位（否则拒绝失效）
     *   做多：klines[0] 不能收盘低于支撑位（否则拒绝失效）
     */
    private static final boolean REQUIRE_CONFIRM_CANDLE = true;

    /**
     * 是否要求关键位为高质量合流区（减少单来源噪音位）
     *
     * 高质量 = STRONG 级别（PDH/PDL、$50 整数关口）
     *        OR 多来源合流（source 含 "+"，如 "Round25+SwingHigh_3"）
     *
     * 回测数据支撑：单一摆动点或单一 $25 关口可靠性低（与 CompositeStrategy 的 sourceCount≥2 对齐）。
     * 代价：减少约 30-40% 的交易次数，但胜率显著提升。
     */
    private static final boolean REQUIRE_HIGH_QUALITY_LEVEL = true;

    /**
     * SL 缓冲：ATR 倍数（0.8）
     * 0.5×ATR 时：win rate=36.5%（SL偏紧被噪音震出）
     * 0.8×ATR 时：win rate=39.8%（给持仓更多呼吸空间），净收益更高
     */
    private static final double SL_ATR_BUFFER = 0.8;

    /**
     * 最低赔率要求（从1.5提高至2.0）
     * 毛利率接近1:1时，手续费摩擦($34/笔)使低赔率交易必然亏损
     */
    private static final double MIN_RR_RATIO = 2.0;

    /** TP fallback 倍数（无明确对面 S/R 时用 3.0×ATR） */
    private static final double TP_ATR_FALLBACK = 3.0;

    /**
     * 拒绝K线最小振幅（相对ATR）
     * 回测实证：振幅<0.5×ATR的K线是噪音蜡烛，拒绝信号不可靠
     * minRange 使用 min(ATR7, ATR14)：ATR7 响应更快，极端行情消退后
     * 门槛随之自动下降，避免高波动遗留值导致后续低波动期系统性沉默。
     */
    private static final double MIN_CANDLE_RANGE_ATR = 0.5;

    /**
     * 噪音时段过滤（UTC小时，时间戳以北京时间存储，UTC = 北京时间 - 8）
     *
     * 回测实证（全量数据 2025-12-11 ~ 2026-05-10）：
     *   UTC 1  (北京 09:00) = 中国金市开盘噪音 → 16.7%胜率，-$2146 ← 最差
     *   UTC 5-8 (北京 13-16) = 欧洲开盘前低流动性 → 已过滤
     *   UTC 10 (北京 18:00) = 伦敦中段震荡 → 23.1%胜率，-$1010
     *   UTC 13 (北京 21:00) = 美欧重叠，方向最混乱 → 19.0%胜率，-$1365
     */
    private static final int[] NOISE_HOURS_UTC = {1, 5, 6, 7, 8, 10, 13, 14, 15, 16};

    @Autowired
    private ATRCalculator atrCalculator;

    @Autowired
    private StochRSICalculator stochRSICalculator;

    /**
     * 做空要求 Stoch RSI K 线超过此值（超买区确认）
     * 价格在阻力位 + 动量超买 → 做空拒绝信号更可靠
     */
    private static final double STOCH_OVERBOUGHT_THRESHOLD = 75.0;

    /**
     * 做多要求 Stoch RSI K 线低于此值（超卖区确认）
     * 价格在支撑位 + 动量超卖 → 做多拒绝信号更可靠
     */
    private static final double STOCH_OVERSOLD_THRESHOLD = 25.0;

    // ── S/R 翻转做空参数 ──────────────────────────────────────

    /**
     * 翻转检测：向前看多少根K线内是否有有效跌穿（60根=5小时）
     * 超出此窗口的跌穿视为过期，不再视为有效翻转信号
     */
    private static final int FLIP_LOOKBACK = 60;

    /**
     * 翻转确认：最少需要多少根K线的收盘价低于支撑位，才算真实跌穿
     * 回测验证：4根过严（把大量盈利信号也过滤掉了），保持3根
     */
    private static final int FLIP_CONFIRM_CLOSES = 3;

    /**
     * 翻转确认：收盘价需低于支撑位多少才算有效跌穿（0.10%）
     * 防止浮点误差和极微小滑点导致误判
     */
    private static final double FLIP_CLOSE_THRESHOLD_PCT = 0.0010;

    /**
     * 翻转入场：当前价格距翻转阻力位的最大上行距离（0.30%，百分比）
     * 价格尚未反弹至翻转位附近时不触发；价格突破翻转位后也不触发
     */
    private static final double FLIP_PROXIMITY_PCT = 0.30;

    /**
     * Flip专用影线质量门槛
     * 回测验证：按影线比区分 Flip 胜率无明显规律（57%影线也有+$433大赢单）
     * 保持与普通拒绝一致的0.55，不单独收紧
     */
    private static final double FLIP_WICK_RATIO_THRESHOLD = 0.55;

    /**
     * 近期趋势封锁阈值（设为99.9%=禁用）
     * 实证：SR拒绝信号出现时，价格刚好到达S/R位，此时intradayDrop/Rise必然偏大，
     * 导致过滤器正好在信号最强时封锁交易，净效果为负。
     * 保留代码结构，阈值设为不可能触发的99.9%。
     */
    private static final double INTRADAY_TREND_THRESHOLD = 99.9;

    // ─────────────────────────────────────────────────────────
    // Strategy 接口实现
    // ─────────────────────────────────────────────────────────

    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().size() < 20) {
            return hold(context, "K线数据不足");
        }

        List<Kline> klines = context.getKlines();
        double currentPrice = context.getCurrentPrice().doubleValue();

        // 获取关键位（由 StrategyOrchestrator 统一计算，放在 context 里）
        KeyLevelCalculator.KeyLevelResult levels = context.getIndicator("KeyLevels");
        if (levels == null) {
            return hold(context, "KeyLevels 未计算");
        }

        // 计算 ATR(14)
        Double atrVal = atrCalculator.calculate(klines, 14);
        if (atrVal == null || atrVal <= 0) {
            return hold(context, "ATR 计算失败");
        }
        double atr = atrVal;

        // ── 时段过滤：过滤噪音时段 ──
        Kline latestKline = klines.get(0);
        if (latestKline.getTimestamp() != null) {
            int hourUtc = latestKline.getTimestamp()
                    .atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(ZoneId.of("UTC"))
                    .getHour();
            boolean isNoiseHour = Arrays.stream(NOISE_HOURS_UTC).anyMatch(h -> h == hourUtc);
            if (isNoiseHour) {
                return hold(context, String.format("噪音时段UTC %d点，跳过", hourUtc));
            }
        }

        // ── 宏观趋势感知（7d 价格变化，硬性提高逆势门槛）──
        // 在牛市中做空需要更极端的 StochRSI 超买确认，防止在上涨趋势中频繁被轧空
        double change7d  = computeMacroChange7d(klines);
        double change24h = computeMacroChange24h(klines);
        boolean macroBull = change7d > 2.0;   // 7天涨幅 > 2% 视为宏观牛市
        boolean macroBear = change7d < -2.0;   // 7天跌幅 > 2% 视为宏观熊市

        // ── 1小时趋势感知（软过滤，不硬封锁方向）──
        EMACalculator.EMATrend emaTrend = context.getIndicator("EMATrend");
        boolean emaUp   = emaTrend != null && emaTrend.isUpTrend();
        boolean emaDown = emaTrend != null && emaTrend.isDownTrend();

        // StochRSI 门槛叠加规则（取最严格值）：
        //   宏观牛市做空：基础75 + EMA向上+10 + 宏观牛市+10 → 最高95
        //   宏观熊市做多：基础25 - EMA向下-10 - 宏观熊市-10 → 最低5
        double shortAdj = (emaUp ? 10.0 : 0.0) + (macroBull ? 10.0 : 0.0);
        double longAdj  = (emaDown ? -10.0 : 0.0) + (macroBear ? -10.0 : 0.0);
        double shortStochThreshold = Math.min(95.0, STOCH_OVERBOUGHT_THRESHOLD + shortAdj);
        double longStochThreshold  = Math.max(5.0,  STOCH_OVERSOLD_THRESHOLD   + longAdj);

        log.info("[SRRejection] 宏观7d={}% 24h={}% {} | EMA={} | 做空StochRSI门槛={} 做多门槛={}",
                String.format("%.2f", change7d),
                String.format("%.2f", change24h),
                macroBull ? "BULL" : (macroBear ? "BEAR" : "NEUTRAL"),
                emaUp ? "UP" : (emaDown ? "DOWN" : "FLAT"),
                String.format("%.0f", shortStochThreshold),
                String.format("%.0f", longStochThreshold));

        // ── Stochastic RSI ──
        StochRSICalculator.StochRSIResult stochRSI = stochRSICalculator.calculate(klines);
        if (stochRSI == null) {
            return hold(context, "StochRSI 数据不足");
        }
        log.info("[SRRejection] StochRSI K={} D={} | {}",
                String.format("%.1f", stochRSI.getK()),
                String.format("%.1f", stochRSI.getD()),
                stochRSI.getDescription());

        // ── 日内趋势感知（防止在单边行情中逆势开仓）──
        double intradayDropPct = computeIntradayDropFromHigh(klines);  // 距日内最高跌幅%
        double intradayRisePct = computeIntradayRiseFromLow(klines);   // 距日内最低涨幅%
        boolean intradayBear = intradayDropPct > INTRADAY_TREND_THRESHOLD;  // 日内熊市
        boolean intradayBull = intradayRisePct > INTRADAY_TREND_THRESHOLD;  // 日内牛市
        if (intradayBear || intradayBull) {
            log.info("[SRRejection] 日内趋势感知: 跌{}% 涨{}% → {}",
                    String.format("%.2f", intradayDropPct),
                    String.format("%.2f", intradayRisePct),
                    intradayBear ? "日内熊市(封锁做多)" : "日内牛市(封锁普通做空)");
        }

        // ──────────────────────────────────────────────────────
        // 做空：阻力位 + 上影线拒绝 + 确认K线 + StochRSI 超买确认
        // 逆 1H 趋势（EMA向上）时门槛自动提高至 >85
        // ──────────────────────────────────────────────────────
        KeyLevelCalculator.KeyLevel resistance = levels.getNearestResistance();
        if (resistance != null) {
            // 关键位质量过滤：只在 STRONG 或多来源合流位开仓
            if (REQUIRE_HIGH_QUALITY_LEVEL && !isHighQualityLevel(resistance)) {
                log.info("[SRRejection] 做空阻力位质量不足 ({} / {})，跳过",
                        resistance.getStrength(), resistance.getSource());
                resistance = null;
            }
        }
        if (resistance != null) {
            RejectionCandle bearish = findBearishRejection(klines, resistance.getPrice(), REJECTION_LOOKBACK);
            if (bearish.found) {
                // 确认K线过滤：拒绝K线为 klines[1]（非最新根）时，要求 klines[0] 没有突破阻力位上方
                // 软过滤：阻力位若被 klines[0] 突破收盘，说明拒绝信号已失效，不入场
                boolean confirmOk = true;
                if (REQUIRE_CONFIRM_CANDLE && bearish.klineIndex == 1 && klines.size() > 1) {
                    Kline confirmK = klines.get(0);
                    double confirmClose = confirmK.getClosePrice().doubleValue();
                    double resistPrice  = resistance.getPrice();
                    // klines[0] 收盘高于阻力位 = 拒绝失效，假突破
                    if (confirmClose > resistPrice) {
                        confirmOk = false;
                        log.info("[SRRejection] 做空被确认K线过滤（klines[0] close={} > 阻力{}，拒绝失效）",
                                String.format("%.2f", confirmClose),
                                String.format("%.2f", resistPrice));
                    }
                }

                if (confirmOk) {
                    // ── 日内牛市封锁普通做空（防止在强势上涨日逆势做空）──
                    if (intradayBull) {
                        log.info("[SRRejection] 普通做空被日内牛市封锁（日内涨{}%>{}%）",
                                String.format("%.2f", intradayRisePct), INTRADAY_TREND_THRESHOLD);
                    }
                    // ── MACRO_BULL + 24h无真实回调 → 完全封锁做空 ──
                    // 对齐 CompositeStrategy 宏观锁逻辑：牛市中必须有明确回调（24h < -0.5%）才允许做空
                    else if (macroBull && change24h > -0.5) {
                        log.info("[SRRejection] 做空被MACRO_BULL封锁(7d={}% 24h={}%，需24h<-0.5%才允许)",
                                String.format("%.2f", change7d), String.format("%.2f", change24h));
                    } else {
                        // K/D方向加权：K仍高于D说明动量未转空，门槛再提高10点
                        double effectiveShortThreshold = shortStochThreshold
                                + (stochRSI.getK() > stochRSI.getD() ? 10.0 : 0.0);
                        if (stochRSI.getK() < effectiveShortThreshold) {
                            log.info("[SRRejection] 做空被 StochRSI 过滤 K={} < {}（{}{}）",
                                    String.format("%.1f", stochRSI.getK()),
                                    String.format("%.0f", effectiveShortThreshold),
                                    emaUp ? "逆1H上升趋势" : "动量未超买",
                                    stochRSI.getK() > stochRSI.getD() ? " +K>D+10" : "");
                        } else {
                            double slBase = Math.max(bearish.wickExtreme, resistance.getPrice());
                            double sl = slBase + atr * SL_ATR_BUFFER;
                            double risk = sl - currentPrice;

                            if (risk <= 0) {
                                log.info("[SRRejection] 做空 SL 计算异常 (sl={} <= currentPrice={})", sl, currentPrice);
                            } else {
                                double tp = computeShortTP(levels, currentPrice, atr, risk);
                                double reward = currentPrice - tp;
                                double rr = reward / risk;

                                if (rr >= MIN_RR_RATIO) {
                                    int strength = computeStrength(resistance, bearish, stochRSI, rr);
                                    String reason = String.format(
                                            "阻力%.2f 上影线拒绝(影线比%.0f%%) StochRSI K=%.1f(超买) | SL=%.2f TP=%.2f RR=%.2f:1",
                                            resistance.getPrice(), bearish.wickRatio * 100,
                                            stochRSI.getK(), sl, tp, rr);
                                    log.info("[SRRejection] ✅ SELL | {}", reason);
                                    return TradingSignal.builder()
                                            .type(TradingSignal.SignalType.SELL)
                                            .strength(strength).score(strength)
                                            .strategyName(STRATEGY_NAME)
                                            .symbol(context.getSymbol())
                                            .currentPrice(context.getCurrentPrice())
                                            .suggestedStopLoss(BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP))
                                            .suggestedTakeProfit(BigDecimal.valueOf(tp).setScale(2, RoundingMode.HALF_UP))
                                            .reason(reason)
                                            .build();
                                } else {
                                    log.info("[SRRejection] 做空赔率不足 {}:1 (需≥{}:1)，HOLD",
                                            String.format("%.2f", rr), MIN_RR_RATIO);
                                }
                            }
                        }
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────
        // S/R 翻转做空：STRONG 支撑被有效跌穿后，反弹回测时做空
        // 适用场景：单边暴跌后的 dead-cat bounce 回测翻转阻力
        // ──────────────────────────────────────────────────────
        KeyLevelCalculator.KeyLevel flippedResistance = findFlippedSupport(klines, levels, currentPrice);
        if (flippedResistance != null) {
            // 日内牛市封锁 Flip 做空（日内牛市中 Flip 也不应做空）
            if (intradayBull) {
                log.info("[SRRejection] Flip做空被日内牛市封锁（日内涨{}%>{}%）",
                        String.format("%.2f", intradayRisePct), INTRADAY_TREND_THRESHOLD);
            } else {
            RejectionCandle bearishFlip = findBearishRejection(klines, flippedResistance.getPrice(), REJECTION_LOOKBACK);
            // Flip 专用影线质量门槛（比普通拒绝更严格）
            if (bearishFlip.found && bearishFlip.wickRatio < FLIP_WICK_RATIO_THRESHOLD) {
                log.info("[SRRejection] Flip影线质量不足 {}%<{}%，跳过",
                        String.format("%.0f", bearishFlip.wickRatio * 100),
                        String.format("%.0f", FLIP_WICK_RATIO_THRESHOLD * 100));
                bearishFlip = RejectionCandle.NOT_FOUND;
            }
            if (bearishFlip.found) {
                boolean confirmOk = true;
                if (REQUIRE_CONFIRM_CANDLE && bearishFlip.klineIndex == 1 && klines.size() > 1) {
                    Kline confirmK = klines.get(0);
                    double confirmClose = confirmK.getClosePrice().doubleValue();
                    double flipPrice = flippedResistance.getPrice();
                    if (confirmClose > flipPrice) {
                        confirmOk = false;
                        log.info("[SRRejection] Flip做空被确认K线过滤（close={} > 翻转阻力{}，拒绝失效）",
                                String.format("%.2f", confirmClose), String.format("%.2f", flipPrice));
                    }
                }
                if (confirmOk) {
                    if (macroBull && change24h > -0.5) {
                        log.info("[SRRejection] Flip做空被MACRO_BULL封锁(7d={}% 24h={}%)",
                                String.format("%.2f", change7d), String.format("%.2f", change24h));
                    } else {
                        double effectiveFlipThreshold = shortStochThreshold
                                + (stochRSI.getK() > stochRSI.getD() ? 10.0 : 0.0);
                        if (stochRSI.getK() < effectiveFlipThreshold) {
                            log.info("[SRRejection] Flip做空被StochRSI过滤 K={} < {}",
                                    String.format("%.1f", stochRSI.getK()),
                                    String.format("%.0f", effectiveFlipThreshold));
                        } else {
                            double slBase = Math.max(bearishFlip.wickExtreme, flippedResistance.getPrice());
                            double sl = slBase + atr * SL_ATR_BUFFER;
                            double risk = sl - currentPrice;
                            if (risk <= 0) {
                                log.info("[SRRejection] Flip做空SL计算异常 (sl={} <= currentPrice={})", sl, currentPrice);
                            } else {
                                double tp = computeShortTP(levels, currentPrice, atr, risk);
                                double reward = currentPrice - tp;
                                double rr = reward / risk;
                                if (rr >= MIN_RR_RATIO) {
                                    int strength = computeStrength(flippedResistance, bearishFlip, stochRSI, rr);
                                    String reason = String.format(
                                            "S/R翻转 支撑%.2f→阻力 上影线拒绝(影线比%.0f%%) StochRSI K=%.1f | SL=%.2f TP=%.2f RR=%.2f:1",
                                            flippedResistance.getPrice(), bearishFlip.wickRatio * 100,
                                            stochRSI.getK(), sl, tp, rr);
                                    log.info("[SRRejection] ✅ FLIP-SELL | {}", reason);
                                    return TradingSignal.builder()
                                            .type(TradingSignal.SignalType.SELL)
                                            .strength(strength).score(strength)
                                            .strategyName(STRATEGY_NAME)
                                            .symbol(context.getSymbol())
                                            .currentPrice(context.getCurrentPrice())
                                            .suggestedStopLoss(BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP))
                                            .suggestedTakeProfit(BigDecimal.valueOf(tp).setScale(2, RoundingMode.HALF_UP))
                                            .reason(reason)
                                            .build();
                                } else {
                                    log.info("[SRRejection] Flip做空赔率不足 {}:1 (需≥{}:1)",
                                            String.format("%.2f", rr), MIN_RR_RATIO);
                                }
                            }
                        }
                    }
                }
            }
            } // end else !intradayBull
        }

        // ──────────────────────────────────────────────────────
        // 做多：支撑位 + 下影线拒绝 + 确认K线 + StochRSI 超卖确认
        // 逆 1H 趋势（EMA向下）时门槛自动提高至 <15
        // ──────────────────────────────────────────────────────
        KeyLevelCalculator.KeyLevel support = levels.getNearestSupport();
        if (support != null) {
            // 关键位质量过滤：只在 STRONG 或多来源合流位开仓
            if (REQUIRE_HIGH_QUALITY_LEVEL && !isHighQualityLevel(support)) {
                log.info("[SRRejection] 做多支撑位质量不足 ({} / {})，跳过",
                        support.getStrength(), support.getSource());
                support = null;
            }
        }
        if (support != null) {
            RejectionCandle bullish = findBullishRejection(klines, support.getPrice(), REJECTION_LOOKBACK);
            if (bullish.found) {
                // 确认K线过滤：拒绝K线为 klines[1]（非最新根）时，要求 klines[0] 没有跌破支撑位
                // 软过滤：支撑位若被 klines[0] 跌破收盘，说明拒绝信号已失效，不入场
                boolean confirmOk = true;
                if (REQUIRE_CONFIRM_CANDLE && bullish.klineIndex == 1 && klines.size() > 1) {
                    Kline confirmK = klines.get(0);
                    double confirmClose = confirmK.getClosePrice().doubleValue();
                    double supportPrice = support.getPrice();
                    // klines[0] 收盘低于支撑位 = 拒绝失效，假支撑
                    if (confirmClose < supportPrice) {
                        confirmOk = false;
                        log.info("[SRRejection] 做多被确认K线过滤（klines[0] close={} < 支撑{}，拒绝失效）",
                                String.format("%.2f", confirmClose),
                                String.format("%.2f", supportPrice));
                    }
                }

                if (confirmOk) {
                    // ── 日内熊市封锁做多（防止在强势下跌日逆势做多）──
                    if (intradayBear) {
                        log.info("[SRRejection] 做多被日内熊市封锁（日内跌{}%>{}%，拒绝逆势做多）",
                                String.format("%.2f", intradayDropPct), INTRADAY_TREND_THRESHOLD);
                    } else {
                    // K/D方向加权：K仍低于D说明动量未转多，门槛再降低10点（要求更深超卖）
                    double effectiveLongThreshold = longStochThreshold
                            + (stochRSI.getK() < stochRSI.getD() ? -10.0 : 0.0);
                    // StochRSI 动态门槛：顺势 <25，逆 1H 趋势 <15
                    if (stochRSI.getK() > effectiveLongThreshold) {
                        log.info("[SRRejection] 做多被 StochRSI 过滤 K={} > {}（{}{}）",
                                String.format("%.1f", stochRSI.getK()),
                                String.format("%.0f", effectiveLongThreshold),
                                emaDown ? "逆1H下降趋势，门槛降低至15" : "动量未超卖",
                                stochRSI.getK() < stochRSI.getD() ? " +K<D-10" : "");
                    } else {
                        double slBase = Math.min(bullish.wickExtreme, support.getPrice());
                        double sl = slBase - atr * SL_ATR_BUFFER;
                        double risk = currentPrice - sl;

                        if (risk <= 0) {
                            log.info("[SRRejection] 做多 SL 计算异常 (sl={} >= currentPrice={})", sl, currentPrice);
                        } else {
                            double tp = computeLongTP(levels, currentPrice, atr, risk);
                            double reward = tp - currentPrice;
                            double rr = reward / risk;

                            if (rr >= MIN_RR_RATIO) {
                                int strength = computeStrength(support, bullish, stochRSI, rr);
                                String reason = String.format(
                                        "支撑%.2f 下影线拒绝(影线比%.0f%%) StochRSI K=%.1f(超卖) | SL=%.2f TP=%.2f RR=%.2f:1",
                                        support.getPrice(), bullish.wickRatio * 100,
                                        stochRSI.getK(), sl, tp, rr);
                                log.info("[SRRejection] ✅ BUY  | {}", reason);
                                return TradingSignal.builder()
                                        .type(TradingSignal.SignalType.BUY)
                                        .strength(strength).score(strength)
                                        .strategyName(STRATEGY_NAME)
                                        .symbol(context.getSymbol())
                                        .currentPrice(context.getCurrentPrice())
                                        .suggestedStopLoss(BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP))
                                        .suggestedTakeProfit(BigDecimal.valueOf(tp).setScale(2, RoundingMode.HALF_UP))
                                        .reason(reason)
                                        .build();
                            } else {
                                log.info("[SRRejection] 做多赔率不足 {}:1 (需≥{}:1)，HOLD",
                                        String.format("%.2f", rr), MIN_RR_RATIO);
                            }
                        }
                    }
                    } // end else !intradayBear
                }
            }
        }

        log.info("[SRRejection] HOLD | 无有效 S/R 拒绝信号 (支撑距{}, 阻力距{})",
                String.format("%.3f%%", levels.getDistanceToSupportPercent()),
                String.format("%.3f%%", levels.getDistanceToResistancePercent()));
        return hold(context, String.format("无拒绝信号(支撑距%.2f%% 阻力距%.2f%%)",
                levels.getDistanceToSupportPercent(), levels.getDistanceToResistancePercent()));
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
    public boolean isEnabled() {
        // 不参与 CompositeStrategy 投票，作为独立策略单独调用
        return false;
    }

    // ─────────────────────────────────────────────────────────
    // 拒绝 K 线检测
    // ─────────────────────────────────────────────────────────

    /**
     * 在最近 lookback 根 K 线中寻找"上影线拒绝"（做空信号）。
     *
     * 满足条件：
     *   1. K 线最高价必须 >= 阻力位（真实触及，允许 0.05% 浮点容差）
     *      旧逻辑：h >= resistanceLevel - 0.2%（允许低于阻力位 $9 也算触位，导致假信号）
     *      新逻辑：h >= resistanceLevel - 0.05%（必须真正触及阻力位）
     *   2. 上影线占振幅 ≥ 50%
     *   3. 上影线 > 下影线（上方拒绝为主导）
     *   4. 收盘价 < K 线中线（收低，确认被压）
     */
    private RejectionCandle findBearishRejection(List<Kline> klines, double resistanceLevel, int lookback) {
        double touchTolerance = resistanceLevel * TOUCH_THRESHOLD_PCT;
        Double atr14 = atrCalculator.calculate(klines, 14);
        Double atr7  = atrCalculator.calculate(klines, 7);
        // 取 min(ATR7, ATR14)：ATR7 响应当前市场节奏更快，避免极端行情遗留值虚高门槛
        double atr = (atr14 != null && atr7 != null) ? Math.min(atr14, atr7)
                   : (atr14 != null ? atr14 : (atr7 != null ? atr7 : null));
        double minRange = (atr > 0) ? atr * MIN_CANDLE_RANGE_ATR : 0.3;
        log.debug("[SRRejection] 做空 minRange={} (ATR7={} ATR14={})",
                String.format("%.2f", minRange),
                atr7 != null ? String.format("%.2f", atr7) : "N/A",
                atr14 != null ? String.format("%.2f", atr14) : "N/A");

        for (int i = 0; i < Math.min(lookback, klines.size()); i++) {
            Kline k = klines.get(i);
            double o = k.getOpenPrice().doubleValue();
            double h = k.getHighPrice().doubleValue();
            double l = k.getLowPrice().doubleValue();
            double c = k.getClosePrice().doubleValue();

            // Step 1: K线最高价必须真实触及阻力位（>= resistanceLevel - 容差）
            // 修复：0.2% 容差允许高点比阻力低 $9，这类 K 线没有真正触及阻力，是假触位
            if (h < resistanceLevel - touchTolerance) {
                continue;
            }

            double totalRange = h - l;
            if (totalRange < minRange) continue; // K线振幅须 ≥ 0.5×ATR，过滤噪音蜡烛

            double upperWick = h - Math.max(o, c);
            double lowerWick = Math.min(o, c) - l;
            double midPoint  = (h + l) / 2.0;

            double wickRatio = upperWick / totalRange;

            // Step 2-4: 影线形态验证
            if (wickRatio >= WICK_RATIO_THRESHOLD
                    && upperWick > lowerWick
                    && c < midPoint) {
                log.info("[SRRejection] 发现上影线拒绝 K线#{} H={} C={} 影线比={}% 阻力={}",
                        i, String.format("%.2f", h), String.format("%.2f", c),
                        String.format("%.0f", wickRatio * 100),
                        String.format("%.2f", resistanceLevel));
                return new RejectionCandle(true, i, wickRatio, h);
            }
        }
        return RejectionCandle.NOT_FOUND;
    }

    /**
     * 在最近 lookback 根 K 线中寻找"下影线拒绝"（做多信号）。
     *
     * 满足条件：
     *   1. K 线最低价必须 <= 支撑位（真实触及，允许 0.05% 浮点容差）
     *   2. 下影线占振幅 ≥ 50%
     *   3. 下影线 > 上影线（下方拒绝为主导）
     *   4. 收盘价 > K 线中线（收高，确认被撑）
     */
    private RejectionCandle findBullishRejection(List<Kline> klines, double supportLevel, int lookback) {
        double touchTolerance = supportLevel * TOUCH_THRESHOLD_PCT;
        Double atr14 = atrCalculator.calculate(klines, 14);
        Double atr7  = atrCalculator.calculate(klines, 7);
        double atr = (atr14 != null && atr7 != null) ? Math.min(atr14, atr7)
                   : (atr14 != null ? atr14 : (atr7 != null ? atr7 : null));
        double minRange = (atr > 0) ? atr * MIN_CANDLE_RANGE_ATR : 0.3;
        log.debug("[SRRejection] 做多 minRange={} (ATR7={} ATR14={})",
                String.format("%.2f", minRange),
                atr7 != null ? String.format("%.2f", atr7) : "N/A",
                atr14 != null ? String.format("%.2f", atr14) : "N/A");

        for (int i = 0; i < Math.min(lookback, klines.size()); i++) {
            Kline k = klines.get(i);
            double o = k.getOpenPrice().doubleValue();
            double h = k.getHighPrice().doubleValue();
            double l = k.getLowPrice().doubleValue();
            double c = k.getClosePrice().doubleValue();

            // Step 1: K线最低价必须真实触及支撑位（<= supportLevel + 容差）
            if (l > supportLevel + touchTolerance) {
                continue;
            }

            double totalRange = h - l;
            if (totalRange < minRange) continue; // K线振幅须 ≥ 0.5×ATR，过滤噪音蜡烛

            double upperWick = h - Math.max(o, c);
            double lowerWick = Math.min(o, c) - l;
            double midPoint  = (h + l) / 2.0;

            double wickRatio = lowerWick / totalRange;

            // Step 2-4: 影线形态验证
            if (wickRatio >= WICK_RATIO_THRESHOLD
                    && lowerWick > upperWick
                    && c > midPoint) {
                log.info("[SRRejection] 发现下影线拒绝 K线#{} L={} C={} 影线比={}% 支撑={}",
                        i, String.format("%.2f", l), String.format("%.2f", c),
                        String.format("%.0f", wickRatio * 100),
                        String.format("%.2f", supportLevel));
                return new RejectionCandle(true, i, wickRatio, l);
            }
        }
        return RejectionCandle.NOT_FOUND;
    }

    /**
     * 寻找"支撑翻转阻力"位（S/R Flip）
     *
     * 检测条件（全部满足）：
     *   1. allLevels 中存在 STRONG 或多来源合流的 SUPPORT 位
     *   2. 该支撑价格 > 当前价格（价格已跌穿该支撑位）
     *   3. 当前价格距该位置 ≤ FLIP_PROXIMITY_PCT（价格已反弹回翻转位附近）
     *   4. 最近 FLIP_LOOKBACK 根K线中，至少 FLIP_CONFIRM_CLOSES 根K线的收盘价
     *      低于（支撑位 × (1 - FLIP_CLOSE_THRESHOLD_PCT)），确认真实跌穿而非假破
     *
     * 返回满足条件的、距当前价格最近的翻转阻力位；无则返回 null。
     */
    private KeyLevelCalculator.KeyLevel findFlippedSupport(
            List<Kline> klines,
            KeyLevelCalculator.KeyLevelResult levels,
            double currentPrice) {

        int lookback = Math.min(FLIP_LOOKBACK, klines.size());

        return levels.getAllLevels().stream()
                .filter(l -> l.getType() == KeyLevelCalculator.LevelType.SUPPORT)
                .filter(l -> l.getStrength() == KeyLevelCalculator.LevelStrength.STRONG
                        || (l.getSource() != null && l.getSource().contains("+")))
                .filter(l -> l.getPrice() > currentPrice)
                .filter(l -> {
                    double distPct = (l.getPrice() - currentPrice) / currentPrice * 100;
                    return distPct <= FLIP_PROXIMITY_PCT;
                })
                .filter(l -> {
                    double breakThreshold = l.getPrice() * (1 - FLIP_CLOSE_THRESHOLD_PCT);
                    long breakCount = klines.subList(0, lookback).stream()
                            .filter(k -> k.getClosePrice().doubleValue() < breakThreshold)
                            .count();
                    if (breakCount >= FLIP_CONFIRM_CLOSES) {
                        log.info("[SRRejection] S/R翻转候选：支撑{}→阻力（近{}根K线中{}根收盘确认跌穿）",
                                String.format("%.2f", l.getPrice()), lookback, breakCount);
                    }
                    return breakCount >= FLIP_CONFIRM_CLOSES;
                })
                .min(Comparator.comparingDouble(l -> Math.abs(l.getPrice() - currentPrice)))
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────
    // TP 计算
    // ─────────────────────────────────────────────────────────

    /** 做空 TP：取最近支撑位，不够则 fallback 2×ATR */
    private double computeShortTP(KeyLevelCalculator.KeyLevelResult levels,
                                   double currentPrice, double atr, double risk) {
        KeyLevelCalculator.KeyLevel nearestSupport = levels.getNearestSupport();
        if (nearestSupport != null) {
            double tp = nearestSupport.getPrice();
            double reward = currentPrice - tp;
            if (reward >= risk * MIN_RR_RATIO) {
                return tp;
            }
            // 第二近支撑
            KeyLevelCalculator.KeyLevel secondSupport = levels.getSecondSupport();
            if (secondSupport != null) {
                tp = secondSupport.getPrice();
                reward = currentPrice - tp;
                if (reward >= risk * MIN_RR_RATIO) {
                    return tp;
                }
            }
        }
        // fallback: 取ATR倍数与实际风险2.5倍中的较大值，确保TP与实际SL成比例
        return currentPrice - Math.max(atr * TP_ATR_FALLBACK, risk * TP_ATR_FALLBACK);
    }

    /** 做多 TP：取最近阻力位，不够则 fallback 2×ATR */
    private double computeLongTP(KeyLevelCalculator.KeyLevelResult levels,
                                  double currentPrice, double atr, double risk) {
        KeyLevelCalculator.KeyLevel nearestResistance = levels.getNearestResistance();
        if (nearestResistance != null) {
            double tp = nearestResistance.getPrice();
            double reward = tp - currentPrice;
            if (reward >= risk * MIN_RR_RATIO) {
                return tp;
            }
            // 第二近阻力
            KeyLevelCalculator.KeyLevel secondResistance = levels.getSecondResistance();
            if (secondResistance != null) {
                tp = secondResistance.getPrice();
                reward = tp - currentPrice;
                if (reward >= risk * MIN_RR_RATIO) {
                    return tp;
                }
            }
        }
        // fallback: 取ATR倍数与实际风险2.5倍中的较大值，确保TP与实际SL成比例
        return currentPrice + Math.max(atr * TP_ATR_FALLBACK, risk * TP_ATR_FALLBACK);
    }

    // ─────────────────────────────────────────────────────────
    // 信号强度
    // ─────────────────────────────────────────────────────────

    private int computeStrength(KeyLevelCalculator.KeyLevel level,
                                RejectionCandle rejection,
                                StochRSICalculator.StochRSIResult stochRSI,
                                double rr) {
        int strength = 60; // 基础分（三步条件均满足）

        // 关键位强度加分
        if (level.getStrength() == KeyLevelCalculator.LevelStrength.STRONG) {
            strength += 20;
        } else if (level.getStrength() == KeyLevelCalculator.LevelStrength.MEDIUM) {
            strength += 10;
        }

        // 影线越长越强
        if (rejection.wickRatio >= 0.60) {
            strength += 15;
        } else if (rejection.wickRatio >= 0.50) {
            strength += 8;
        }

        // StochRSI 极端区加分
        double k = stochRSI.getK();
        if (k > 90 || k < 10) {
            strength += 15; // 极端超买/超卖，信号最强
        } else if (k > 80 || k < 20) {
            strength += 8;
        }

        // K 线与 D 线同向交叉（动量确认）
        if (stochRSI.isKCrossedBelowD() || stochRSI.isKCrossedAboveD()) {
            strength += 5;
        }

        // 赔率越高越强
        if (rr >= 3.0) {
            strength += 10;
        } else if (rr >= 2.5) {
            strength += 5;
        }

        return Math.min(100, strength);
    }

    // ─────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────

    /**
     * 判断关键位是否为高质量合流区
     *
     * 高质量条件（满足任一即可）：
     *   1. STRONG 级别（PDH/PDL、$50 整数关口）
     *   2. 多来源合流（source 含 "+"，如 "Round25+SwingHigh"）
     */
    private boolean isHighQualityLevel(KeyLevelCalculator.KeyLevel level) {
        if (level == null) return false;
        if (level.getStrength() == KeyLevelCalculator.LevelStrength.STRONG) return true;
        String src = level.getSource();
        return src != null && src.contains("+");
    }

    /**
     * 计算最近12根K线（约1小时）内收盘价最高点到当前收盘价的跌幅（%，正数表示已跌）
     *
     * 相比"全日高点"方案的优势：
     *   价格在支撑位底部企稳时，近1小时收盘价最大值 ≈ 当前价 → 跌幅小 → 不误判为熊市
     *   价格在最近1小时内持续下跌时，最大收盘价远高于当前价 → 跌幅大 → 正确判为熊市
     */
    private double computeIntradayDropFromHigh(List<Kline> klines) {
        if (klines.isEmpty()) return 0.0;
        int window = Math.min(12, klines.size());
        double currentPrice = klines.get(0).getClosePrice().doubleValue();
        double recentHighClose = klines.subList(0, window).stream()
                .mapToDouble(k -> k.getClosePrice().doubleValue())
                .max().orElse(currentPrice);
        return recentHighClose > 0 ? (recentHighClose - currentPrice) / recentHighClose * 100.0 : 0.0;
    }

    /**
     * 计算最近12根K线（约1小时）内收盘价最低点到当前收盘价的涨幅（%，正数表示已涨）
     *
     * 相比"全日低点"方案的优势：
     *   价格在阻力位顶部受压时，近1小时收盘价最小值 ≈ 当前价 → 涨幅小 → 不误判为牛市
     *   价格在最近1小时内持续上涨时，最小收盘价远低于当前价 → 涨幅大 → 正确判为牛市
     */
    private double computeIntradayRiseFromLow(List<Kline> klines) {
        if (klines.isEmpty()) return 0.0;
        int window = Math.min(12, klines.size());
        double currentPrice = klines.get(0).getClosePrice().doubleValue();
        double recentLowClose = klines.subList(0, window).stream()
                .mapToDouble(k -> k.getClosePrice().doubleValue())
                .min().orElse(currentPrice);
        return recentLowClose > 0 ? (currentPrice - recentLowClose) / recentLowClose * 100.0 : 0.0;
    }

    /**
     * 计算 7 天价格变化百分比（用于宏观趋势判断）
     * klines 为倒序列表（最新在前），5m K线 7天 = 2016 根
     */
    private double computeMacroChange7d(List<Kline> klines) {
        int lookback = Math.min(2016, klines.size() - 1);
        if (lookback < 288) return 0.0;
        double current = klines.get(0).getClosePrice().doubleValue();
        double past    = klines.get(lookback).getClosePrice().doubleValue();
        return past > 0 ? (current - past) / past * 100.0 : 0.0;
    }

    /**
     * 计算 24 小时价格变化百分比（用于 MACRO_BULL 封锁做空时的回调确认）
     * klines 为倒序列表（最新在前），5m K线 24小时 = 288 根
     */
    private double computeMacroChange24h(List<Kline> klines) {
        int lookback = Math.min(288, klines.size() - 1);
        if (lookback < 60) return 0.0;
        double current = klines.get(0).getClosePrice().doubleValue();
        double past    = klines.get(lookback).getClosePrice().doubleValue();
        return past > 0 ? (current - past) / past * 100.0 : 0.0;
    }

    private TradingSignal hold(MarketContext context, String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .symbol(context != null ? context.getSymbol() : "UNKNOWN")
                .currentPrice(context != null ? context.getCurrentPrice() : BigDecimal.ZERO)
                .reason(reason)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // 内部数据类
    // ─────────────────────────────────────────────────────────

    /** 拒绝 K 线检测结果 */
    private static class RejectionCandle {
        static final RejectionCandle NOT_FOUND = new RejectionCandle(false, -1, 0, 0);

        final boolean found;
        /** 在 klines 列表中的索引（0=最新） */
        final int klineIndex;
        /** 影线占振幅的比例 */
        final double wickRatio;
        /**
         * 影线极值：
         *   做空（上影线拒绝）→ K 线 High（止损放在此上方）
         *   做多（下影线拒绝）→ K 线 Low（止损放在此下方）
         */
        final double wickExtreme;

        RejectionCandle(boolean found, int klineIndex, double wickRatio, double wickExtreme) {
            this.found       = found;
            this.klineIndex  = klineIndex;
            this.wickRatio   = wickRatio;
            this.wickExtreme = wickExtreme;
        }
    }
}
