package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * OBV - 能量潮指标 (On Balance Volume)
 * 
 * 成交量确认指标，日内短线的关键辅助：
 * - 价格上涨 + OBV上涨 = 趋势健康，可跟进
 * - 价格上涨 + OBV下降 = 量价背离，趋势可能反转
 * - OBV突破前高 = 量能突破，价格即将跟进
 * 
 * 用途：
 * 1. 确认趋势的有效性（量价齐升/齐跌）
 * 2. 发现量价背离（预警反转）
 * 3. 辅助判断突破的真假
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class OBVCalculator implements TechnicalIndicator<OBVCalculator.OBVResult> {

    private static final int DEFAULT_LOOKBACK = 20;

    @Override
    public OBVResult calculate(List<Kline> klines) {
        return calculate(klines, DEFAULT_LOOKBACK);
    }

    /**
     * 计算OBV及相关指标
     * 
     * @param klines K线数据（最新在前）
     * @param lookback 回溯周期
     * @return OBV结果
     */
    public OBVResult calculate(List<Kline> klines, int lookback) {
        if (klines == null || klines.size() < lookback + 1) {
            return null;
        }

        int size = Math.min(klines.size(), lookback + 1);

        // 从旧到新计算OBV
        double[] obvValues = new double[size];
        double[] closes = new double[size];

        // 反转索引（klines最新在前）
        for (int i = 0; i < size; i++) {
            closes[i] = klines.get(size - 1 - i).getClosePrice().doubleValue();
        }

        obvValues[0] = klines.get(size - 1).getVolume().doubleValue();
        for (int i = 1; i < size; i++) {
            double vol = klines.get(size - 1 - i).getVolume().doubleValue();
            if (closes[i] > closes[i - 1]) {
                obvValues[i] = obvValues[i - 1] + vol;
            } else if (closes[i] < closes[i - 1]) {
                obvValues[i] = obvValues[i - 1] - vol;
            } else {
                obvValues[i] = obvValues[i - 1];
            }
        }

        // 最新OBV值
        int latest = size - 1;
        double currentOBV = obvValues[latest];

        // 计算OBV的短期EMA（5周期）用于平滑
        double obvEma = currentOBV;
        double emaMult = 2.0 / 6.0;
        for (int i = latest - 1; i >= Math.max(0, latest - 5); i--) {
            obvEma = obvValues[i] * emaMult + obvEma * (1 - emaMult);
        }

        // 检测量价背离
        // 看最近5根K线的价格趋势和OBV趋势
        int divergenceWindow = Math.min(5, latest);
        double priceChange = closes[latest] - closes[latest - divergenceWindow];
        double obvChange = obvValues[latest] - obvValues[latest - divergenceWindow];

        // OBV趋势（最近lookback周期）
        double obvTrend = obvValues[latest] - obvValues[Math.max(0, latest - lookback + 1)];

        OBVResult result = new OBVResult();
        result.setObv(currentOBV);
        result.setObvEma(obvEma);
        result.setObvTrend(obvTrend);
        result.setPriceChange(priceChange);
        result.setObvChange(obvChange);

        // 量价背离检测
        result.setBearishDivergence(priceChange > 0 && obvChange < 0); // 价格涨但OBV跌
        result.setBullishDivergence(priceChange < 0 && obvChange > 0); // 价格跌但OBV涨

        // 量能方向与价格方向一致
        result.setVolumeConfirmed(
                (priceChange > 0 && obvChange > 0) || (priceChange < 0 && obvChange < 0));

        // OBV是否在EMA上方（量能积极）
        result.setObvAboveEma(currentOBV > obvEma);

        log.debug("[OBV] OBV={:.0f}, EMA={:.0f}, 趋势={:.0f}, 量价确认={}, 背离={}",
                currentOBV, obvEma, obvTrend,
                result.isVolumeConfirmed() ? "是" : "否",
                result.hasDivergence() ? (result.isBearishDivergence() ? "看跌" : "看涨") : "无");

        return result;
    }

    @Override
    public String getName() {
        return "OBV";
    }

    @Override
    public int getRequiredPeriods() {
        return DEFAULT_LOOKBACK + 1;
    }

    @Override
    public String getDescription() {
        return "OBV能量潮 - 通过成交量变化确认价格趋势，发现量价背离";
    }

    /**
     * OBV计算结果
     */
    @Data
    public static class OBVResult {
        private double obv;              // OBV值
        private double obvEma;           // OBV的EMA平滑
        private double obvTrend;         // OBV趋势方向
        private double priceChange;      // 价格变化
        private double obvChange;        // OBV变化
        private boolean bearishDivergence; // 看跌背离（价涨量跌）
        private boolean bullishDivergence; // 看涨背离（价跌量涨）
        private boolean volumeConfirmed;  // 量价方向一致
        private boolean obvAboveEma;      // OBV在EMA上方

        /**
         * 是否存在背离
         */
        public boolean hasDivergence() {
            return bearishDivergence || bullishDivergence;
        }

        /**
         * 获取成交量状态描述
         */
        public String getVolumeDescription() {
            if (bearishDivergence) return "⚠️ 看跌背离(价涨量跌)";
            if (bullishDivergence) return "🔄 看涨背离(价跌量涨)";
            if (volumeConfirmed && obvAboveEma) return "✅ 量价齐升(强)";
            if (volumeConfirmed) return "✅ 量价一致";
            return "➖ 中性";
        }
    }
}
