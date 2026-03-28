package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stochastic RSI 计算器
 *
 * 计算步骤：
 *   1. 对收盘价序列计算 RSI(rsiPeriod)，得到 RSI 时间序列
 *   2. 在 RSI 序列上再做 Stochastic(stochPeriod)：
 *        StochRSI = (RSI - minRSI) / (maxRSI - minRSI)  × 100
 *   3. %K = SMA(StochRSI, kSmooth)   —— 平滑后的主线
 *   4. %D = SMA(%K, dSmooth)         —— 信号线
 *
 * 默认参数：RSI(14)，Stoch(14)，K平滑(3)，D平滑(3)
 * 数值范围：0 ~ 100
 *   >80 = 超买区，<20 = 超卖区
 *
 * 用于 SRRejectionScalpingStrategy：
 *   做空时要求 K > 75（动量超买，与阻力位上影线拒绝共振）
 *   做多时要求 K < 25（动量超卖，与支撑位下影线拒绝共振）
 *
 * @author Peter Wang
 */
@Slf4j
@Component
public class StochRSICalculator {

    private static final int RSI_PERIOD   = 14;
    private static final int STOCH_PERIOD = 14;
    private static final int K_SMOOTH     = 3;
    private static final int D_SMOOTH     = 3;

    /** 最少需要的 K 线根数 */
    public static final int MIN_KLINES = RSI_PERIOD + STOCH_PERIOD + K_SMOOTH + D_SMOOTH + 5;

    // ─────────────────────────────────────────────────────────
    // 对外接口
    // ─────────────────────────────────────────────────────────

    /**
     * 用默认参数计算 Stoch RSI
     *
     * @param klines K线列表（最新在前，即 klines.get(0) 为最新）
     * @return StochRSIResult，数据不足时返回 null
     */
    public StochRSIResult calculate(List<Kline> klines) {
        return calculate(klines, RSI_PERIOD, STOCH_PERIOD, K_SMOOTH, D_SMOOTH);
    }

    /**
     * 自定义参数计算 Stoch RSI
     */
    public StochRSIResult calculate(List<Kline> klines,
                                    int rsiPeriod, int stochPeriod,
                                    int kSmooth, int dSmooth) {
        if (klines == null || klines.size() < rsiPeriod + stochPeriod + kSmooth + dSmooth) {
            log.debug("[StochRSI] K线不足，需要至少 {} 根", rsiPeriod + stochPeriod + kSmooth + dSmooth);
            return null;
        }

        // 1. 将 K线倒序（最旧在前），便于时序计算
        List<Kline> ordered = new ArrayList<>(klines);
        Collections.reverse(ordered);

        // 2. 计算整个序列的 RSI 值
        List<Double> rsiSeries = computeRSISeries(ordered, rsiPeriod);
        if (rsiSeries.size() < stochPeriod + kSmooth + dSmooth) {
            return null;
        }

        // 3. 在 RSI 序列上做 Stochastic
        List<Double> stochSeries = computeStochastic(rsiSeries, stochPeriod);
        if (stochSeries.size() < kSmooth + dSmooth) {
            return null;
        }

        // 4. %K = SMA(stochSeries, kSmooth)
        List<Double> kSeries = computeSMA(stochSeries, kSmooth);
        if (kSeries.size() < dSmooth) {
            return null;
        }

        // 5. %D = SMA(%K, dSmooth)
        List<Double> dSeries = computeSMA(kSeries, dSmooth);
        if (dSeries.isEmpty()) {
            return null;
        }

        // 取最新值（列表末尾）
        double k = kSeries.get(kSeries.size() - 1);
        double d = dSeries.get(dSeries.size() - 1);

        // 取前一根 K 值，用于判断交叉
        double prevK = kSeries.size() >= 2 ? kSeries.get(kSeries.size() - 2) : k;
        double prevD = dSeries.size() >= 2 ? dSeries.get(dSeries.size() - 2) : d;

        StochRSIResult result = StochRSIResult.builder()
                .k(k)
                .d(d)
                .prevK(prevK)
                .prevD(prevD)
                .overbought(k > 80)
                .oversold(k < 20)
                .kCrossedAboveD(prevK <= prevD && k > d)   // K 金叉 D（做多信号）
                .kCrossedBelowD(prevK >= prevD && k < d)   // K 死叉 D（做空信号）
                .build();

        log.debug("[StochRSI] K={} D={} | {}",
                String.format("%.1f", k), String.format("%.1f", d),
                result.isOverbought() ? "超买" : result.isOversold() ? "超卖" : "中性");

        return result;
    }

    // ─────────────────────────────────────────────────────────
    // 内部计算
    // ─────────────────────────────────────────────────────────

    /**
     * 计算整条 RSI 时间序列（Wilder's Smoothing）
     * 输入：ordered klines（最旧在前）
     * 输出：与输入对齐的 RSI 序列（前 rsiPeriod 根无效，从第 rsiPeriod 根开始有值）
     */
    private List<Double> computeRSISeries(List<Kline> ordered, int period) {
        List<Double> rsiValues = new ArrayList<>();

        if (ordered.size() <= period) return rsiValues;

        // 计算每根 K 线的涨跌幅
        double avgGain = 0;
        double avgLoss = 0;

        for (int i = 1; i <= period; i++) {
            double change = ordered.get(i).getClosePrice().doubleValue()
                    - ordered.get(i - 1).getClosePrice().doubleValue();
            if (change > 0) avgGain += change;
            else            avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // 第一个 RSI 值
        double rs  = avgLoss == 0 ? 100 : avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        rsiValues.add(rsi);

        // Wilder's Smoothing 后续 RSI
        for (int i = period + 1; i < ordered.size(); i++) {
            double change = ordered.get(i).getClosePrice().doubleValue()
                    - ordered.get(i - 1).getClosePrice().doubleValue();
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            rs  = avgLoss == 0 ? 100 : avgGain / avgLoss;
            rsi = 100 - (100 / (1 + rs));
            rsiValues.add(rsi);
        }

        return rsiValues;
    }

    /**
     * 对 RSI 序列做 Stochastic（滑动窗口内找最高最低 RSI）
     * 输出长度 = 输入长度 - stochPeriod + 1
     */
    private List<Double> computeStochastic(List<Double> rsiSeries, int stochPeriod) {
        List<Double> result = new ArrayList<>();

        for (int i = stochPeriod - 1; i < rsiSeries.size(); i++) {
            double minRSI = Double.MAX_VALUE;
            double maxRSI = Double.MIN_VALUE;

            for (int j = i - stochPeriod + 1; j <= i; j++) {
                double v = rsiSeries.get(j);
                if (v < minRSI) minRSI = v;
                if (v > maxRSI) maxRSI = v;
            }

            double stoch = (maxRSI - minRSI) == 0
                    ? 50.0
                    : (rsiSeries.get(i) - minRSI) / (maxRSI - minRSI) * 100;
            result.add(stoch);
        }

        return result;
    }

    /**
     * 简单移动平均（SMA）
     */
    private List<Double> computeSMA(List<Double> series, int period) {
        List<Double> result = new ArrayList<>();

        for (int i = period - 1; i < series.size(); i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += series.get(j);
            }
            result.add(sum / period);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────
    // 结果模型
    // ─────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class StochRSIResult {
        /** %K 线当前值（0-100） */
        private double k;
        /** %D 信号线当前值（0-100） */
        private double d;
        /** 前一根 %K */
        private double prevK;
        /** 前一根 %D */
        private double prevD;
        /** K > 80，超买区 */
        private boolean overbought;
        /** K < 20，超卖区 */
        private boolean oversold;
        /** K 从下方穿越 D（金叉，做多信号） */
        private boolean kCrossedAboveD;
        /** K 从上方穿越 D（死叉，做空信号） */
        private boolean kCrossedBelowD;

        public String getDescription() {
            if (overbought) return String.format("超买 K=%.1f D=%.1f", k, d);
            if (oversold)   return String.format("超卖 K=%.1f D=%.1f", k, d);
            return String.format("中性 K=%.1f D=%.1f", k, d);
        }
    }
}
