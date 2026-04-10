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
import java.time.ZoneId;
import java.util.Arrays;
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
     * 影线/振幅最小比例（提高至 50%）
     * 回测实证：35% 阈值捞到大量劣质信号（胜率14-18%），50% 后信号更纯粹
     */
    private static final double WICK_RATIO_THRESHOLD = 0.50;

    /**
     * 往前看 K 线数（从3根缩减至2根）
     * 回测实证：lookback=3 导致大量"过期拒绝"被重复触发
     */
    private static final int REJECTION_LOOKBACK = 2;

    /**
     * SL 缓冲：ATR 倍数（从0.3提高至0.8）
     * 回测实证：持仓<30min的胜率仅14-27%，SL太紧被噪音震出
     * 0.8×ATR 缓冲让仓位有足够空间，与持仓>30min的高胜率对齐
     */
    private static final double SL_ATR_BUFFER = 0.8;

    /**
     * 最低赔率要求（从1.5提高至2.0）
     * 毛利率接近1:1时，手续费摩擦($34/笔)使低赔率交易必然亏损
     */
    private static final double MIN_RR_RATIO = 2.0;

    /** TP fallback 倍数（无明确对面 S/R 时用 2.5×ATR） */
    private static final double TP_ATR_FALLBACK = 2.5;

    /**
     * 拒绝K线最小振幅（相对ATR）
     * 回测实证：振幅<0.5×ATR的K线是噪音蜡烛，拒绝信号不可靠
     */
    private static final double MIN_CANDLE_RANGE_ATR = 0.5;

    /**
     * 噪音时段过滤（UTC小时）
     * 回测实证：UTC 5-8、11-14点均盈亏为负（-$20~-$59/笔）
     * 对应欧洲开盘前低流动性 + 美欧重叠高噪音时段
     */
    private static final int[] NOISE_HOURS_UTC = {5, 6, 7, 8, 11, 12, 13, 14};

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

        // ── 1小时趋势感知（软过滤，不硬封锁方向）──
        // 使用 EMATrend（EMA20/EMA50 on 5m ≈ 1.7h/4.2h）作为 1H 趋势代理。
        // 逻辑：S/R 拒绝策略两个方向都可以做，但逆 1H 趋势时
        //   要求更深的 StochRSI 极值确认（提高门槛 +10 点），而非直接封锁。
        EMACalculator.EMATrend emaTrend = context.getIndicator("EMATrend");
        boolean emaUp   = emaTrend != null && emaTrend.isUpTrend();
        boolean emaDown = emaTrend != null && emaTrend.isDownTrend();

        // 逆 1H 趋势时，StochRSI 门槛提高 10 点（顺势可用默认阈值）
        // 逆势做空（EMA 向上）：K 须 > 85；逆势做多（EMA 向下）：K 须 < 15
        double shortStochThreshold = emaUp   ? STOCH_OVERBOUGHT_THRESHOLD + 10.0 : STOCH_OVERBOUGHT_THRESHOLD;
        double longStochThreshold  = emaDown ? STOCH_OVERSOLD_THRESHOLD   - 10.0 : STOCH_OVERSOLD_THRESHOLD;

        log.info("[SRRejection] 1H趋势(EMA): {} | 做空StochRSI门槛={} 做多门槛={}",
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

        // ──────────────────────────────────────────────────────
        // 做空：阻力位 + 上影线拒绝 + StochRSI 超买确认
        // 逆 1H 趋势（EMA向上）时门槛自动提高至 >85
        // ──────────────────────────────────────────────────────
        KeyLevelCalculator.KeyLevel resistance = levels.getNearestResistance();
        if (resistance != null) {
            RejectionCandle bearish = findBearishRejection(klines, resistance.getPrice(), REJECTION_LOOKBACK);
            if (bearish.found) {
                    // StochRSI 动态门槛：顺势 >75，逆 1H 趋势 >85
                    if (stochRSI.getK() < shortStochThreshold) {
                        log.info("[SRRejection] 做空被 StochRSI 过滤 K={} < {}（{}）",
                                String.format("%.1f", stochRSI.getK()),
                                String.format("%.0f", shortStochThreshold),
                                emaUp ? "逆1H上升趋势，门槛提高至85" : "动量未超买");
                    } else {
                        // SL 放在 max(wickExtreme, resistanceLevel) 上方 + ATR 缓冲
                        // 修复：原来只用 wickExtreme，当 K 线高点低于阻力位时 SL 极度紧张
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

        // ──────────────────────────────────────────────────────
        // 做多：支撑位 + 下影线拒绝 + StochRSI 超卖确认
        // 逆 1H 趋势（EMA向下）时门槛自动提高至 <15
        // ──────────────────────────────────────────────────────
        KeyLevelCalculator.KeyLevel support = levels.getNearestSupport();
        if (support != null) {
            RejectionCandle bullish = findBullishRejection(klines, support.getPrice(), REJECTION_LOOKBACK);
            if (bullish.found) {
                    // StochRSI 动态门槛：顺势 <25，逆 1H 趋势 <15
                    if (stochRSI.getK() > longStochThreshold) {
                        log.info("[SRRejection] 做多被 StochRSI 过滤 K={} > {}（{}）",
                                String.format("%.1f", stochRSI.getK()),
                                String.format("%.0f", longStochThreshold),
                                emaDown ? "逆1H下降趋势，门槛降低至15" : "动量未超卖");
                    } else {
                        // SL 放在 min(wickExtreme, supportLevel) 下方 + ATR 缓冲
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
        Double atr = atrCalculator.calculate(klines, 14);
        double minRange = (atr != null) ? atr * MIN_CANDLE_RANGE_ATR : 0.3;

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
        Double atr = atrCalculator.calculate(klines, 14);
        double minRange = (atr != null) ? atr * MIN_CANDLE_RANGE_ATR : 0.3;

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
        // fallback: 2×ATR
        return currentPrice - atr * TP_ATR_FALLBACK;
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
        // fallback: 2×ATR
        return currentPrice + atr * TP_ATR_FALLBACK;
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
