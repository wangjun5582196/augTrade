package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class IndicatorService {
    
    /**
     * 计算简单移动平均线(SMA)
     */
    public BigDecimal calculateSMA(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(klines.get(i).getClosePrice());
        }
        
        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 计算指数移动平均线(EMA)
     */
    public BigDecimal calculateEMA(List<Kline> klines, int period) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal ema = klines.get(klines.size() - 1).getClosePrice();
        
        for (int i = klines.size() - 2; i >= Math.max(0, klines.size() - period); i--) {
            BigDecimal closePrice = klines.get(i).getClosePrice();
            ema = closePrice.multiply(multiplier)
                    .add(ema.multiply(BigDecimal.ONE.subtract(multiplier)));
        }
        
        return ema.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * 计算相对强弱指标(RSI)
     */
    public BigDecimal calculateRSI(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            return BigDecimal.valueOf(50); // 默认返回中性值
        }
        
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        
        for (int i = 0; i < period; i++) {
            BigDecimal change = klines.get(i).getClosePrice()
                    .subtract(klines.get(i + 1).getClosePrice());
            
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gains = gains.add(change);
            } else {
                losses = losses.add(change.abs());
            }
        }
        
        if (losses.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }
        
        BigDecimal avgGain = gains.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        BigDecimal rs = avgGain.divide(avgLoss, 4, RoundingMode.HALF_UP);
        
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP));
        
        log.debug("RSI({}) = {}", period, rsi);
        return rsi;
    }
    
    /**
     * 计算MACD指标
     * @return [MACD, Signal, Histogram]
     */
    public BigDecimal[] calculateMACD(List<Kline> klines, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (klines == null || klines.size() < slowPeriod) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        
        BigDecimal fastEMA = calculateEMA(klines, fastPeriod);
        BigDecimal slowEMA = calculateEMA(klines, slowPeriod);
        BigDecimal macd = fastEMA.subtract(slowEMA);
        
        // 简化的Signal线计算
        List<Kline> recentKlines = klines.subList(0, Math.min(signalPeriod, klines.size()));
        BigDecimal signal = calculateEMA(recentKlines, signalPeriod);
        BigDecimal histogram = macd.subtract(signal);
        
        log.debug("MACD = {}, Signal = {}, Histogram = {}", macd, signal, histogram);
        return new BigDecimal[]{macd, signal, histogram};
    }
    
    /**
     * 计算布林带
     * @return [Upper, Middle, Lower]
     */
    public BigDecimal[] calculateBollingerBands(List<Kline> klines, int period, double stdDevMultiplier) {
        if (klines == null || klines.size() < period) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        
        BigDecimal sma = calculateSMA(klines, period);
        
        // 计算标准差
        BigDecimal variance = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            BigDecimal diff = klines.get(i).getClosePrice().subtract(sma);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        double stdDev = Math.sqrt(variance.doubleValue());
        
        BigDecimal upper = sma.add(BigDecimal.valueOf(stdDev * stdDevMultiplier));
        BigDecimal lower = sma.subtract(BigDecimal.valueOf(stdDev * stdDevMultiplier));
        
        log.debug("布林带 - Upper: {}, Middle: {}, Lower: {}", upper, sma, lower);
        return new BigDecimal[]{upper, sma, lower};
    }
    
    /**
     * 判断金叉（快线上穿慢线）
     */
    public boolean isGoldenCross(List<Kline> klines, int fastPeriod, int slowPeriod) {
        if (klines == null || klines.size() < Math.max(fastPeriod, slowPeriod) + 1) {
            return false;
        }
        
        BigDecimal currentFast = calculateSMA(klines.subList(0, klines.size()), fastPeriod);
        BigDecimal currentSlow = calculateSMA(klines.subList(0, klines.size()), slowPeriod);
        
        BigDecimal prevFast = calculateSMA(klines.subList(1, klines.size()), fastPeriod);
        BigDecimal prevSlow = calculateSMA(klines.subList(1, klines.size()), slowPeriod);
        
        boolean goldenCross = prevFast.compareTo(prevSlow) < 0 && currentFast.compareTo(currentSlow) > 0;
        
        if (goldenCross) {
            log.info("检测到金叉信号! 快线: {}, 慢线: {}", currentFast, currentSlow);
        }
        
        return goldenCross;
    }
    
    /**
     * 判断死叉（快线下穿慢线）
     */
    public boolean isDeathCross(List<Kline> klines, int fastPeriod, int slowPeriod) {
        if (klines == null || klines.size() < Math.max(fastPeriod, slowPeriod) + 1) {
            return false;
        }
        
        BigDecimal currentFast = calculateSMA(klines.subList(0, klines.size()), fastPeriod);
        BigDecimal currentSlow = calculateSMA(klines.subList(0, klines.size()), slowPeriod);
        
        BigDecimal prevFast = calculateSMA(klines.subList(1, klines.size()), fastPeriod);
        BigDecimal prevSlow = calculateSMA(klines.subList(1, klines.size()), slowPeriod);
        
        boolean deathCross = prevFast.compareTo(prevSlow) > 0 && currentFast.compareTo(currentSlow) < 0;
        
        if (deathCross) {
            log.info("检测到死叉信号! 快线: {}, 慢线: {}", currentFast, currentSlow);
        }
        
        return deathCross;
    }
    
    // ==================== 高级技术指标 ====================
    
    /**
     * ATR - 平均真实波幅 (Average True Range)
     * 用于衡量市场波动性，是最重要的风险管理指标之一
     * 高ATR = 高波动性，低ATR = 低波动性
     */
    public BigDecimal calculateATR(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            return BigDecimal.ZERO;
        }
        
        List<BigDecimal> trueRanges = new ArrayList<>();
        
        for (int i = 0; i < period; i++) {
            Kline current = klines.get(i);
            Kline previous = klines.get(i + 1);
            
            // TR = max(H-L, abs(H-PC), abs(L-PC))
            BigDecimal hl = current.getHighPrice().subtract(current.getLowPrice());
            BigDecimal hpc = current.getHighPrice().subtract(previous.getClosePrice()).abs();
            BigDecimal lpc = current.getLowPrice().subtract(previous.getClosePrice()).abs();
            
            BigDecimal tr = hl.max(hpc).max(lpc);
            trueRanges.add(tr);
        }
        
        // ATR = SMA of True Range
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal tr : trueRanges) {
            sum = sum.add(tr);
        }
        
        BigDecimal atr = sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
        log.debug("ATR({}) = {}", period, atr);
        return atr;
    }
    
    /**
     * Stochastic Oscillator - 随机震荡指标
     * 范围0-100，>80超买，<20超卖
     * 比RSI更敏感，适合短线交易
     * @return [%K, %D]
     */
    public BigDecimal[] calculateStochastic(List<Kline> klines, int kPeriod, int dPeriod) {
        if (klines == null || klines.size() < kPeriod) {
            return new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(50)};
        }
        
        // 找到K周期内的最高价和最低价
        BigDecimal highest = klines.get(0).getHighPrice();
        BigDecimal lowest = klines.get(0).getLowPrice();
        
        for (int i = 1; i < kPeriod; i++) {
            highest = highest.max(klines.get(i).getHighPrice());
            lowest = lowest.min(klines.get(i).getLowPrice());
        }
        
        BigDecimal currentClose = klines.get(0).getClosePrice();
        
        // %K = 100 * (Close - Lowest) / (Highest - Lowest)
        BigDecimal range = highest.subtract(lowest);
        BigDecimal percentK;
        
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            percentK = BigDecimal.valueOf(50);
        } else {
            percentK = currentClose.subtract(lowest)
                    .divide(range, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        
        // %D = SMA of %K over D period (简化计算)
        BigDecimal percentD = percentK; // 简化版本
        
        log.debug("Stochastic - %K: {}, %D: {}", percentK, percentD);
        return new BigDecimal[]{percentK, percentD};
    }
    
    /**
     * ADX - 平均趋向指标 (Average Directional Index)
     * 衡量趋势强度，不管方向
     * >25 = 强趋势，<20 = 弱趋势/震荡
     * 这是最重要的趋势强度指标
     */
    public BigDecimal calculateADX(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            return BigDecimal.ZERO;
        }
        
        List<BigDecimal> plusDM = new ArrayList<>();
        List<BigDecimal> minusDM = new ArrayList<>();
        List<BigDecimal> trueRanges = new ArrayList<>();
        
        for (int i = 0; i < period; i++) {
            Kline current = klines.get(i);
            Kline previous = klines.get(i + 1);
            
            BigDecimal highDiff = current.getHighPrice().subtract(previous.getHighPrice());
            BigDecimal lowDiff = previous.getLowPrice().subtract(current.getLowPrice());
            
            // +DM and -DM
            BigDecimal plusDMValue = (highDiff.compareTo(lowDiff) > 0 && highDiff.compareTo(BigDecimal.ZERO) > 0) 
                    ? highDiff : BigDecimal.ZERO;
            BigDecimal minusDMValue = (lowDiff.compareTo(highDiff) > 0 && lowDiff.compareTo(BigDecimal.ZERO) > 0) 
                    ? lowDiff : BigDecimal.ZERO;
            
            plusDM.add(plusDMValue);
            minusDM.add(minusDMValue);
            
            // True Range
            BigDecimal hl = current.getHighPrice().subtract(current.getLowPrice());
            BigDecimal hpc = current.getHighPrice().subtract(previous.getClosePrice()).abs();
            BigDecimal lpc = current.getLowPrice().subtract(previous.getClosePrice()).abs();
            trueRanges.add(hl.max(hpc).max(lpc));
        }
        
        // 计算平均值
        BigDecimal avgPlusDM = plusDM.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        BigDecimal avgMinusDM = minusDM.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        BigDecimal avgTR = trueRanges.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        
        if (avgTR.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        // +DI and -DI
        BigDecimal plusDI = avgPlusDM.divide(avgTR, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        BigDecimal minusDI = avgMinusDM.divide(avgTR, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        
        // DX = 100 * |+DI - -DI| / |+DI + -DI|
        BigDecimal diDiff = plusDI.subtract(minusDI).abs();
        BigDecimal diSum = plusDI.add(minusDI);
        
        BigDecimal adx;
        if (diSum.compareTo(BigDecimal.ZERO) == 0) {
            adx = BigDecimal.ZERO;
        } else {
            adx = diDiff.divide(diSum, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        
        log.debug("ADX({}) = {}, +DI = {}, -DI = {}", period, adx, plusDI, minusDI);
        return adx;
    }
    
    /**
     * CCI - 商品通道指标 (Commodity Channel Index)
     * 范围通常在-100到+100之间
     * >+100 = 超买，<-100 = 超卖
     * 特别适合黄金等商品交易
     */
    public BigDecimal calculateCCI(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            return BigDecimal.ZERO;
        }
        
        // 计算典型价格 TP = (High + Low + Close) / 3
        List<BigDecimal> typicalPrices = new ArrayList<>();
        for (int i = 0; i < period; i++) {
            Kline kline = klines.get(i);
            BigDecimal tp = kline.getHighPrice()
                    .add(kline.getLowPrice())
                    .add(kline.getClosePrice())
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            typicalPrices.add(tp);
        }
        
        // SMA of TP
        BigDecimal smaTP = typicalPrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        
        // 平均绝对偏差 MAD
        BigDecimal mad = BigDecimal.ZERO;
        for (BigDecimal tp : typicalPrices) {
            mad = mad.add(tp.subtract(smaTP).abs());
        }
        mad = mad.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        
        if (mad.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        // CCI = (TP - SMA(TP)) / (0.015 * MAD)
        BigDecimal currentTP = typicalPrices.get(0);
        BigDecimal cci = currentTP.subtract(smaTP)
                .divide(mad.multiply(BigDecimal.valueOf(0.015)), 2, RoundingMode.HALF_UP);
        
        log.debug("CCI({}) = {}", period, cci);
        return cci;
    }
    
    /**
     * Williams %R - 威廉指标
     * 范围-100到0，>-20超买，<-80超卖
     * 与Stochastic相似但更敏感
     */
    public BigDecimal calculateWilliamsR(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period) {
            return BigDecimal.valueOf(-50);
        }
        
        BigDecimal highest = klines.get(0).getHighPrice();
        BigDecimal lowest = klines.get(0).getLowPrice();
        
        for (int i = 1; i < period; i++) {
            highest = highest.max(klines.get(i).getHighPrice());
            lowest = lowest.min(klines.get(i).getLowPrice());
        }
        
        BigDecimal currentClose = klines.get(0).getClosePrice();
        BigDecimal range = highest.subtract(lowest);
        
        BigDecimal williamsR;
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            williamsR = BigDecimal.valueOf(-50);
        } else {
            williamsR = highest.subtract(currentClose)
                    .divide(range, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(-100));
        }
        
        log.debug("Williams %R({}) = {}", period, williamsR);
        return williamsR;
    }
    
    /**
     * VWAP - 成交量加权平均价 (Volume Weighted Average Price)
     * 机构交易员最常用的指标
     * 价格>VWAP = 多头占优，价格<VWAP = 空头占优
     */
    public BigDecimal calculateVWAP(List<Kline> klines, int period) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        int actualPeriod = Math.min(period, klines.size());
        BigDecimal totalPV = BigDecimal.ZERO; // Price * Volume
        BigDecimal totalVolume = BigDecimal.ZERO;
        
        for (int i = 0; i < actualPeriod; i++) {
            Kline kline = klines.get(i);
            BigDecimal typicalPrice = kline.getHighPrice()
                    .add(kline.getLowPrice())
                    .add(kline.getClosePrice())
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            
            BigDecimal pv = typicalPrice.multiply(kline.getVolume());
            totalPV = totalPV.add(pv);
            totalVolume = totalVolume.add(kline.getVolume());
        }
        
        BigDecimal vwap;
        if (totalVolume.compareTo(BigDecimal.ZERO) == 0) {
            vwap = klines.get(0).getClosePrice();
        } else {
            vwap = totalPV.divide(totalVolume, 2, RoundingMode.HALF_UP);
        }
        
        log.debug("VWAP({}) = {}", period, vwap);
        return vwap;
    }
    
    /**
     * 一目均衡表 (Ichimoku Cloud) - 转换线
     * 日本最流行的技术指标系统
     * @return [转换线, 基准线, 先行带A, 先行带B]
     */
    public BigDecimal[] calculateIchimoku(List<Kline> klines) {
        if (klines == null || klines.size() < 52) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        
        // 转换线 (Tenkan-sen) = (9日最高 + 9日最低) / 2
        BigDecimal tenkan = calculateHL(klines, 9);
        
        // 基准线 (Kijun-sen) = (26日最高 + 26日最低) / 2
        BigDecimal kijun = calculateHL(klines, 26);
        
        // 先行带A (Senkou Span A) = (转换线 + 基准线) / 2
        BigDecimal senkouA = tenkan.add(kijun).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        
        // 先行带B (Senkou Span B) = (52日最高 + 52日最低) / 2
        BigDecimal senkouB = calculateHL(klines, 52);
        
        log.debug("Ichimoku - 转换: {}, 基准: {}, 先行A: {}, 先行B: {}", tenkan, kijun, senkouA, senkouB);
        return new BigDecimal[]{tenkan, kijun, senkouA, senkouB};
    }
    
    /**
     * 辅助方法：计算指定周期的(最高+最低)/2
     */
    private BigDecimal calculateHL(List<Kline> klines, int period) {
        BigDecimal highest = klines.get(0).getHighPrice();
        BigDecimal lowest = klines.get(0).getLowPrice();
        
        for (int i = 1; i < Math.min(period, klines.size()); i++) {
            highest = highest.max(klines.get(i).getHighPrice());
            lowest = lowest.min(klines.get(i).getLowPrice());
        }
        
        return highest.add(lowest).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
