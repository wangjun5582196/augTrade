package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.ATRCalculator;
import com.ltp.peter.augtrade.indicator.EMACalculator;
import com.ltp.peter.augtrade.indicator.StochRSICalculator;
import com.ltp.peter.augtrade.indicator.SupertrendCalculator;
import com.ltp.peter.augtrade.indicator.VWAPCalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * EMA 回调量能爆发策略（EMA Pullback Volume Surge，EPVS）
 *
 * 核心理念：在趋势市场中，机构交易员在价格回踩 EMA20 时大量建仓，
 * 表现为成交量明显放大。VWAP 提供机构视角的方向偏好，StochRSI
 * 确认回调深度，三者共振产生高可信度信号。
 *
 * 与现有策略的本质区别：
 *   - CompositeV3 / SR_REJECTION 以【静态 S/R 关键位】为入场锚点
 *   - EPVS 以【动态 EMA20 均线回踩】为入场锚点，不依赖关键位数据库
 *   - 用【量能爆发】替代影线形态作为触发信号，捕捉机构建仓时机
 *
 * 五层过滤（全部通过才开仓）：
 *   Layer 0【宏观方向锁】：MACRO_BULL+24h>0 禁空；MACRO_BEAR+24h<0 禁多
 *   Layer 1【趋势确认】  ：EMA金叉/死叉 + Supertrend 同向 + ADX≥22
 *   Layer 2【VWAP偏向】  ：价格在 VWAP 同侧（±0.25% 容差）
 *   Layer 3【EMA20回踩】 ：收盘价进入 EMA20 ± 0.45% 区间
 *   Layer 4【量能触发】  ：近2根均量 > 1.3× 20周期均量
 *                         + StochRSI-K 进入超卖/超买区（或 K/D 金叉/死叉）
 *
 * SL/TP：
 *   做多 SL = EMA20 − 1.0×ATR（均线失守即趋势无效）
 *   做空 SL = EMA20 + 1.0×ATR
 *   TP  = SL 距离 × 2.5（固定赔率 fallback），最低赔率要求 2.5:1
 *
 * 使用方式：isEnabled()=false，不参与 CompositeStrategy 投票。
 *   通过 strategyName="EMA_PULLBACK" 触发回测，或在 TradingStrategyFactory
 *   中配置 active=ema_pullback 启用实盘。
 *
 * @author Peter Wang
 */
@Slf4j
@Service
public class EMAPullbackVolumeStrategy implements Strategy {

    public static final String STRATEGY_NAME = "EMA_PULLBACK_VOLUME";

    // EMA20 回踩触碰区间（双侧）：±0.40%（宽区间以增加信号数量）
    private static final double EMA_ZONE_PCT = 0.004;

    // VWAP 方向容差（价格在 VWAP ± 0.5% 内仍算"站上/站下"）
    private static final double VWAP_TOLERANCE_PCT = 0.005;

    // 量能爆发倍数：近2根K线均量 > 1.3 × 20周期均量
    private static final double VOLUME_SURGE_MULTIPLIER = 1.3;

    // StochRSI 超卖阈值（做多时要求 K < 30）
    private static final double STOCH_OVERSOLD = 30.0;

    // StochRSI 超买阈值（做空时要求 K > 70，BUY-ONLY模式下不使用）
    private static final double STOCH_OVERBOUGHT = 70.0;

    // SL ATR 倍数（入场价 - N×ATR，固定风险）
    private static final double SL_ATR_MULT = 1.5;

    // TP ATR 倍数（入场价 + N×ATR，3:1 gross RR）
    private static final double TP_ATR_MULT = 4.5;

    // 最低赔率门槛
    private static final double MIN_REWARD_RISK = 2.5;

    // ADX 最低趋势强度
    private static final double MIN_ADX = 22.0;

    // 设为 -100 → 空单永远被封锁（策略只做多，适合牛市）
    private static final double MACRO_BULL_BLOCK_SHORT_PCT = -100.0;

    @Autowired
    private ATRCalculator atrCalculator;

    @Autowired
    private EMACalculator emaCalculator;

    @Autowired
    private StochRSICalculator stochRSICalculator;

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public int getWeight() {
        return 10;
    }

    // 独立运行，不参与 CompositeStrategy 投票
    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public TradingSignal generateSignal(MarketContext context) {
        List<Kline> klines = context.getKlines();
        BigDecimal currentPrice = context.getCurrentPrice();

        if (klines == null || klines.size() < 60) {
            return hold(context, "K线不足60根");
        }

        // ─── 读取上下文指标 ───
        EMACalculator.EMATrend emaTrend = context.getIndicator("EMATrend");
        SupertrendCalculator.SupertrendResult supertrend = context.getIndicator("Supertrend");
        VWAPCalculator.VWAPResult vwap = context.getIndicator("VWAP");
        Double adx = context.getIndicatorAsDouble("ADX");
        String macroTrend = context.getIndicator("MacroTrend");
        Double macroPct24h = context.getIndicatorAsDouble("MacroPriceChange24h");
        Double macroPct7d = context.getIndicatorAsDouble("MacroPriceChange7d");

        // ADX 必须可用（判断是否处于趋势行情）
        if (adx == null) return hold(context, "ADX不可用");
        if (adx < MIN_ADX) {
            return hold(context, String.format("ADX=%.1f 低于%.0f，市场震荡无趋势", adx, MIN_ADX));
        }

        // 计算ATR（做SL）
        Double atrVal = atrCalculator.calculate(klines, 14);
        if (atrVal == null || atrVal <= 0) return hold(context, "ATR不可用");
        BigDecimal atr = BigDecimal.valueOf(atrVal);

        // 计算EMA20（核心回踩锚点）
        Double ema20Val = emaCalculator.calculate(klines, 20);
        if (ema20Val == null) return hold(context, "EMA20不可用");
        BigDecimal ema20 = BigDecimal.valueOf(ema20Val);

        // 计算StochRSI（入场时机）
        StochRSICalculator.StochRSIResult stochRSI = stochRSICalculator.calculate(klines);
        if (stochRSI == null) return hold(context, "StochRSI不可用");

        // 计算量能比
        double volRatio = calcVolumeRatio(klines);

        // ─── Layer 0: 宏观方向锁 ───
        // 顺势交易原则：周线方向锁 + 日线方向辅助判断
        boolean macroBlockLong = false;
        boolean macroBlockShort = false;
        if (macroTrend != null && macroPct24h != null) {
            boolean isBullMacro = macroTrend.contains("BULL") || macroTrend.contains("UP");
            boolean isBearMacro = macroTrend.contains("BEAR") || macroTrend.contains("DOWN");
            if (isBullMacro) macroBlockShort = true;       // 强多头时完全禁空
            if (isBearMacro && macroPct24h < 0) macroBlockLong = true;
        }
        // 7日涨幅 > 0.3% 时也封锁空单（黄金上涨趋势中不逆势做空）
        if (macroPct7d != null && macroPct7d > MACRO_BULL_BLOCK_SHORT_PCT) {
            macroBlockShort = true;
        }

        // ─── 尝试做多 ───
        if (!macroBlockLong) {
            TradingSignal buy = tryBuy(context, klines, currentPrice, ema20, atr, stochRSI,
                    emaTrend, supertrend, vwap, volRatio, adx);
            if (buy != null && buy.isBuy()) return buy;
        }

        // ─── 尝试做空 ───
        if (!macroBlockShort) {
            TradingSignal sell = trySell(context, klines, currentPrice, ema20, atr, stochRSI,
                    emaTrend, supertrend, vwap, volRatio, adx);
            if (sell != null && sell.isSell()) return sell;
        }

        return hold(context, "无满足入场条件的信号");
    }

    // ═══════════════════════════════════════════════════════
    // 做多评估
    // ═══════════════════════════════════════════════════════

    private TradingSignal tryBuy(MarketContext context, List<Kline> klines,
                                  BigDecimal price, BigDecimal ema20, BigDecimal atr,
                                  StochRSICalculator.StochRSIResult stochRSI,
                                  EMACalculator.EMATrend emaTrend,
                                  SupertrendCalculator.SupertrendResult supertrend,
                                  VWAPCalculator.VWAPResult vwap,
                                  double volRatio, double adx) {
        String tag = "[EPVS-BUY]";

        // Layer 1: EMA 金叉（EMA20>EMA50）+ Supertrend 看涨
        if (emaTrend == null || !emaTrend.isBullishCross()) {
            log.debug("{} L1未通过: EMA未金叉", tag);
            return null;
        }
        if (supertrend == null || !supertrend.isUpTrend()) {
            log.debug("{} L1未通过: Supertrend非看涨", tag);
            return null;
        }

        // Layer 2: VWAP 方向偏向 — 价格在 VWAP 上方（或 VWAP 附近，允许 ±0.25% 容差）
        if (vwap != null) {
            double vwapLow = vwap.getVwap() * (1 - VWAP_TOLERANCE_PCT);
            if (price.doubleValue() < vwapLow) {
                log.debug("{} L2未通过: 价格{} 低于VWAP下限{}", tag,
                        String.format("%.2f", price.doubleValue()), String.format("%.2f", vwapLow));
                return null;
            }
        }

        double ema20d = ema20.doubleValue();
        double priceD = price.doubleValue();

        // Layer 3a: 当前价格在 EMA20 区间内（回踩到位）
        double zoneHigh = ema20d * (1 + EMA_ZONE_PCT);
        double zoneLow  = ema20d * (1 - EMA_ZONE_PCT);
        if (priceD < zoneLow || priceD > zoneHigh) {
            log.debug("{} L3a未通过: 价格{} 不在EMA20区[{}, {}]", tag,
                    String.format("%.2f", priceD), String.format("%.2f", zoneLow), String.format("%.2f", zoneHigh));
            return null;
        }

        // Layer 3b: 触发K线必须收阳（close > open）→ 证明在EMA20附近有买盘接力，不是恐慌踩踏
        BigDecimal openPrice0 = klines.get(0).getOpenPrice();
        if (openPrice0 != null && price.compareTo(openPrice0) <= 0) {
            log.debug("{} L3b未通过: 触发K线收阴(close={} ≤ open={})，买盘不足", tag,
                    String.format("%.2f", priceD), String.format("%.2f", openPrice0.doubleValue()));
            return null;
        }

        // Layer 4a: StochRSI — K < 阈值（确认回调带来超卖）
        if (stochRSI.getK() >= STOCH_OVERSOLD) {
            log.debug("{} L4a未通过: StochRSI K={} 未超卖（阈值{}）", tag,
                    String.format("%.1f", stochRSI.getK()), STOCH_OVERSOLD);
            return null;
        }

        // Layer 4b: 量能爆发
        if (volRatio < VOLUME_SURGE_MULTIPLIER) {
            log.debug("{} L4b未通过: 量能比={} 低于{}", tag,
                    String.format("%.2f", volRatio), VOLUME_SURGE_MULTIPLIER);
            return null;
        }

        // ── SL/TP：入场价-based，固定 1.5×ATR 止损 / 4.5×ATR 止盈（3:1 gross RR）──
        BigDecimal sl = price.subtract(atr.multiply(BigDecimal.valueOf(SL_ATR_MULT)));
        BigDecimal tp = price.add(atr.multiply(BigDecimal.valueOf(TP_ATR_MULT)));
        BigDecimal risk = price.subtract(sl);
        if (risk.compareTo(BigDecimal.ONE) < 0) return hold(context, "BUY止损距离过小(<$1)");

        double rrActual = tp.subtract(price).doubleValue() / risk.doubleValue();
        if (rrActual < MIN_REWARD_RISK) {
            return hold(context, String.format("BUY赔率%.2f:1 不足%.1f:1", rrActual, MIN_REWARD_RISK));
        }

        int strength = calcStrength(adx, stochRSI.getK(), volRatio, supertrend.isTrendChanged(), true);
        String reason = String.format(
                "EMA回踩做多: EMA20=%.2f 前收=%.2f 当前=%.2f | StochRSI K=%.1f | 量能比=%.2f | ADX=%.1f",
                ema20d, klines.get(1).getClosePrice().doubleValue(), priceD,
                stochRSI.getK(), volRatio, adx);

        log.info("{} ✅ 信号 @ {} | SL={} TP={} R:R={}:1 强度={} | {}",
                tag, price, sl.setScale(2, RoundingMode.HALF_UP), tp.setScale(2, RoundingMode.HALF_UP),
                String.format("%.2f", rrActual), strength, reason);

        return TradingSignal.builder()
                .type(TradingSignal.SignalType.BUY)
                .symbol(context.getSymbol())
                .currentPrice(price)
                .suggestedStopLoss(sl.setScale(2, RoundingMode.HALF_UP))
                .suggestedTakeProfit(tp.setScale(2, RoundingMode.HALF_UP))
                .strength(strength)
                .score(strength)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .atr(atr)
                .build();
    }

    // ═══════════════════════════════════════════════════════
    // 做空评估
    // ═══════════════════════════════════════════════════════

    private TradingSignal trySell(MarketContext context, List<Kline> klines,
                                   BigDecimal price, BigDecimal ema20, BigDecimal atr,
                                   StochRSICalculator.StochRSIResult stochRSI,
                                   EMACalculator.EMATrend emaTrend,
                                   SupertrendCalculator.SupertrendResult supertrend,
                                   VWAPCalculator.VWAPResult vwap,
                                   double volRatio, double adx) {
        String tag = "[EPVS-SELL]";

        // Layer 1: EMA 死叉（EMA20<EMA50）+ Supertrend 看跌
        if (emaTrend == null || !emaTrend.isBearishCross()) {
            log.debug("{} L1未通过: EMA未死叉", tag);
            return null;
        }
        if (supertrend == null || !supertrend.isDownTrend()) {
            log.debug("{} L1未通过: Supertrend非看跌", tag);
            return null;
        }

        // Layer 2: VWAP 方向偏向 — 价格在 VWAP 下方（允许 ±0.25% 容差）
        if (vwap != null) {
            double vwapHigh = vwap.getVwap() * (1 + VWAP_TOLERANCE_PCT);
            if (price.doubleValue() > vwapHigh) {
                log.debug("{} L2未通过: 价格{} 高于VWAP上限{}", tag,
                        String.format("%.2f", price.doubleValue()), String.format("%.2f", vwapHigh));
                return null;
            }
        }

        double ema20d = ema20.doubleValue();
        double priceD = price.doubleValue();

        // Layer 3a: 当前价格在 EMA20 区间内（反弹到位）
        double zoneHigh = ema20d * (1 + EMA_ZONE_PCT);
        double zoneLow  = ema20d * (1 - EMA_ZONE_PCT);
        if (priceD < zoneLow || priceD > zoneHigh) {
            log.debug("{} L3a未通过: 价格{} 不在EMA20区[{}, {}]", tag,
                    String.format("%.2f", priceD), String.format("%.2f", zoneLow), String.format("%.2f", zoneHigh));
            return null;
        }

        // Layer 3b: 价格不得低于 EMA20-0.25%（过滤深度空头中的随机反弹）
        double sellZoneBottom = ema20d * 0.9975;
        if (priceD < sellZoneBottom) {
            log.debug("{} L3b未通过: 价格{}低于EMA20-0.25%({})，反弹未充分", tag,
                    String.format("%.2f", priceD), String.format("%.2f", sellZoneBottom));
            return null;
        }

        // Layer 4a: StochRSI — K > 阈值（超买区，顺势做空）
        if (stochRSI.getK() <= STOCH_OVERBOUGHT) {
            log.debug("{} L4a未通过: StochRSI K={} 未超买（阈值{}）", tag,
                    String.format("%.1f", stochRSI.getK()), STOCH_OVERBOUGHT);
            return null;
        }

        // Layer 4b: 量能爆发
        if (volRatio < VOLUME_SURGE_MULTIPLIER) {
            log.debug("{} L4b未通过: 量能比={} 低于{}", tag,
                    String.format("%.2f", volRatio), VOLUME_SURGE_MULTIPLIER);
            return null;
        }

        // ── SL/TP：入场价-based，固定 1.5×ATR 止损 / 4.5×ATR 止盈（3:1 gross RR）──
        BigDecimal sl = price.add(atr.multiply(BigDecimal.valueOf(SL_ATR_MULT)));
        BigDecimal tp = price.subtract(atr.multiply(BigDecimal.valueOf(TP_ATR_MULT)));
        BigDecimal risk = sl.subtract(price);
        if (risk.compareTo(BigDecimal.ONE) < 0) return hold(context, "SELL止损距离过小(<$1)");

        double rrActual = price.subtract(tp).doubleValue() / risk.doubleValue();
        if (rrActual < MIN_REWARD_RISK) {
            return hold(context, String.format("SELL赔率%.2f:1 不足%.1f:1", rrActual, MIN_REWARD_RISK));
        }

        int strength = calcStrength(adx, stochRSI.getK(), volRatio, supertrend.isTrendChanged(), false);
        String reason = String.format(
                "EMA反弹做空: EMA20=%.2f 前收=%.2f 当前=%.2f | StochRSI K=%.1f | 量能比=%.2f | ADX=%.1f",
                ema20d, klines.get(1).getClosePrice().doubleValue(), priceD,
                stochRSI.getK(), volRatio, adx);

        log.info("{} ✅ 信号 @ {} | SL={} TP={} R:R={}:1 强度={} | {}",
                tag, price, sl.setScale(2, RoundingMode.HALF_UP), tp.setScale(2, RoundingMode.HALF_UP),
                String.format("%.2f", rrActual), strength, reason);

        return TradingSignal.builder()
                .type(TradingSignal.SignalType.SELL)
                .symbol(context.getSymbol())
                .currentPrice(price)
                .suggestedStopLoss(sl.setScale(2, RoundingMode.HALF_UP))
                .suggestedTakeProfit(tp.setScale(2, RoundingMode.HALF_UP))
                .strength(strength)
                .score(strength)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .atr(atr)
                .build();
    }

    // ═══════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════

    /**
     * 近2根K线均量 / 前20根K线均量，衡量量能爆发程度。
     * 避免把触发K线本身算进基准均量（取 index 2~21）。
     */
    private double calcVolumeRatio(List<Kline> klines) {
        if (klines.size() < 22) return 0.0;

        double recentVol = 0;
        for (int i = 0; i < 2; i++) {
            if (klines.get(i).getVolume() != null)
                recentVol += klines.get(i).getVolume().doubleValue();
        }
        recentVol /= 2;

        double baseVol = 0;
        int cnt = 0;
        for (int i = 2; i < 22; i++) {
            if (klines.get(i).getVolume() != null) {
                baseVol += klines.get(i).getVolume().doubleValue();
                cnt++;
            }
        }
        if (cnt == 0 || baseVol == 0) return 0.0;
        return recentVol / (baseVol / cnt);
    }

    /**
     * 信号强度计算（50基础分 + 多维加分，上限100）
     *
     * ADX 加分     ：趋势越强加分越高
     * StochRSI 加分：越极端的超卖/超买加分越高
     * 量能加分     ：量能爆发越猛加分越高
     * 趋势翻转加分 ：Supertrend 刚翻转为强信号
     */
    private int calcStrength(double adx, double stochK, double volRatio,
                              boolean trendChanged, boolean isBuy) {
        int s = 50;

        // ADX 加分
        if (adx >= 45) s += 20;
        else if (adx >= 35) s += 12;
        else if (adx >= 25) s += 5;

        // StochRSI 加分
        if (isBuy) {
            if (stochK < 15) s += 15;
            else if (stochK < 25) s += 8;
        } else {
            if (stochK > 85) s += 15;
            else if (stochK > 75) s += 8;
        }

        // 量能加分
        if (volRatio >= 2.5) s += 15;
        else if (volRatio >= 1.8) s += 8;
        else if (volRatio >= 1.3) s += 3;

        // Supertrend 翻转加分
        if (trendChanged) s += 10;

        return Math.min(s, 100);
    }

    private TradingSignal hold(MarketContext context, String reason) {
        log.debug("[EPVS] HOLD: {}", reason);
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .symbol(context.getSymbol())
                .currentPrice(context.getCurrentPrice())
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}
