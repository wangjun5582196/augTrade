package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.*;
import com.ltp.peter.augtrade.indicator.VWAPCalculator;
import com.ltp.peter.augtrade.indicator.SupertrendCalculator;
import com.ltp.peter.augtrade.indicator.OBVCalculator;
import com.ltp.peter.augtrade.market.MarketDataService;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略编排器
 * 
 * 负责协调和编排整个交易策略流程：
 * 1. 获取市场数据
 * 2. 计算所有技术指标
 * 3. 构建市场上下文
 * 4. 使用主策略生成交易信号
 * 
 * 这是策略系统的入口点，提供统一的接口供上层服务调用。
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class StrategyOrchestrator {
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired(required = false)
    private List<TechnicalIndicator<?>> indicators;
    
    @Autowired
    private CompositeStrategy compositeStrategy;
    
    // 可选：单独注入各个指标计算器以便精确控制
    @Autowired
    private RSICalculator rsiCalculator;
    
    @Autowired
    private WilliamsRCalculator williamsCalculator;
    
    @Autowired
    private ADXCalculator adxCalculator;
    
    @Autowired
    private MACDCalculator macdCalculator;
    
    @Autowired
    private BollingerBandsCalculator bollingerBandsCalculator;
    
    @Autowired
    private CandlePatternAnalyzer candlePatternAnalyzer;
    
    @Autowired
    private EMACalculator emaCalculator;
    
    @Autowired
    private VWAPCalculator vwapCalculator;
    
    @Autowired
    private SupertrendCalculator supertrendCalculator;
    
    @Autowired
    private OBVCalculator obvCalculator;
    
    /**
     * 生成交易信号（主入口）
     * 
     * @param symbol 交易品种
     * @return 交易信号
     */
    public TradingSignal generateSignal(String symbol) {
        return generateSignal(symbol, 100);
    }
    
    /**
     * 生成交易信号（可指定K线数量）
     * 
     * @param symbol 交易品种
     * @param klineCount K线数量
     * @return 交易信号
     */
    public TradingSignal generateSignal(String symbol, int klineCount) {
        log.info("[StrategyOrchestrator] 开始为 {} 生成交易信号", symbol);
        
        try {
            // 1. 获取K线数据
            List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", klineCount);
            
            if (klines == null || klines.isEmpty()) {
                log.warn("[StrategyOrchestrator] 无法获取 {} 的K线数据", symbol);
                return createErrorSignal(symbol, "无K线数据");
            }
            
            log.debug("[StrategyOrchestrator] 获取到 {} 根K线", klines.size());
            
            // 2. 获取当前价格（最新的K线在最前面）
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            
            // 3. 构建市场上下文
            MarketContext context = MarketContext.builder()
                    .symbol(symbol)
                    .klines(klines)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            // 4. 计算所有技术指标
            calculateAllIndicators(context);
            
            // 5. 使用组合策略生成信号
            TradingSignal signal = compositeStrategy.generateSignal(context);
            
            if (signal != null) {
                log.info("[StrategyOrchestrator] 生成信号: {} - {} (强度: {}, 得分: {})", 
                        signal.getType(), signal.getReason(), signal.getStrength(), signal.getScore());
            } else {
                log.warn("[StrategyOrchestrator] 策略返回空信号");
                return createErrorSignal(symbol, "策略返回空信号");
            }
            
            return signal;
            
        } catch (Exception e) {
            log.error("[StrategyOrchestrator] 生成交易信号时发生错误", e);
            return createErrorSignal(symbol, "系统异常: " + e.getMessage());
        }
    }
    
    /**
     * 使用自定义策略生成信号
     * 
     * @param symbol 交易品种
     * @param strategy 自定义策略
     * @return 交易信号
     */
    public TradingSignal generateSignalWithStrategy(String symbol, Strategy strategy) {
        return generateSignalWithStrategy(symbol, strategy, 100);
    }
    
    /**
     * 使用自定义策略生成信号（可指定K线数量）
     * 
     * @param symbol 交易品种
     * @param strategy 自定义策略
     * @param klineCount K线数量
     * @return 交易信号
     */
    public TradingSignal generateSignalWithStrategy(String symbol, Strategy strategy, int klineCount) {
        log.info("[StrategyOrchestrator] 使用 {} 策略为 {} 生成信号", strategy.getName(), symbol);
        
        try {
            // 1. 获取K线数据
            List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", klineCount);
            
            if (klines == null || klines.isEmpty()) {
                log.warn("[StrategyOrchestrator] 无法获取 {} 的K线数据", symbol);
                return createErrorSignal(symbol, "无K线数据");
            }
            
            // 2. 构建市场上下文（最新K线在最前面）
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            MarketContext context = MarketContext.builder()
                    .symbol(symbol)
                    .klines(klines)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            // 3. 计算所有技术指标
            calculateAllIndicators(context);
            
            // 4. 使用指定策略生成信号
            return strategy.generateSignal(context);
            
        } catch (Exception e) {
            log.error("[StrategyOrchestrator] 使用自定义策略生成信号时发生错误", e);
            return createErrorSignal(symbol, "系统异常: " + e.getMessage());
        }
    }
    
    /**
     * 计算所有技术指标并添加到上下文
     */
    private void calculateAllIndicators(MarketContext context) {
        List<Kline> klines = context.getKlines();
        
        try {
            // RSI - 🔥 已删除: 与Williams %R重复度85%,删除以简化策略
            // Double rsi = rsiCalculator.calculate(klines);
            // if (rsi != null) {
            //     context.addIndicator("RSI", rsi);
            //     log.debug("[StrategyOrchestrator] RSI = {}", rsi);
            // }
            
            // Williams %R
            Double williamsR = williamsCalculator.calculate(klines);
            if (williamsR != null) {
                context.addIndicator("WilliamsR", williamsR);
                log.debug("[StrategyOrchestrator] Williams %R = {}", williamsR);
            }
            
            // ADX
            Double adx = adxCalculator.calculate(klines);
            if (adx != null) {
                context.addIndicator("ADX", adx);
                log.debug("[StrategyOrchestrator] ADX = {}", adx);
            }
            
            // MACD - 🔥 已删除: 未被任何策略实际使用,删除以减少计算量
            // MACDResult macd = macdCalculator.calculate(klines);
            // if (macd != null) {
            //     context.addIndicator("MACD", macd);
            //     log.debug("[StrategyOrchestrator] MACD = {}, Signal = {}, Histogram = {}", 
            //             macd.getMacdLine(), macd.getSignalLine(), macd.getHistogram());
            // }
            
            // Bollinger Bands - 🔥 P0修复: 始终计算布林带,用于价格位置判断
            BollingerBands bb = bollingerBandsCalculator.calculate(klines);
            if (bb != null) {
                context.addIndicator("BollingerBands", bb);
                log.debug("[StrategyOrchestrator] BB: Upper = {}, Middle = {}, Lower = {} (ADX={})", 
                        bb.getUpper(), bb.getMiddle(), bb.getLower(), 
                        adx != null ? String.format("%.1f", adx) : "N/A");
            }
            
            // Candle Pattern
            CandlePattern pattern = candlePatternAnalyzer.calculate(klines);
            if (pattern != null && pattern.hasPattern()) {
                context.addIndicator("CandlePattern", pattern);
                log.info("[StrategyOrchestrator] K线形态: {} ({})", 
                        pattern.getType().getDescription(), pattern.getDirection().getDescription());
            }
            
            // EMA Trend (EMA20/EMA50)
            EMACalculator.EMATrend emaTrend = emaCalculator.calculateTrend(klines, 20, 50);
            if (emaTrend != null) {
                context.addIndicator("EMATrend", emaTrend);
                log.info("[StrategyOrchestrator] EMA趋势: {}", emaTrend.getTrendDescription());
            }
            
            // 🔥 新增-20260213: VWAP（日内短线核心基准价格）
            VWAPCalculator.VWAPResult vwap = vwapCalculator.calculate(klines);
            if (vwap != null) {
                context.addIndicator("VWAP", vwap);
                log.info("[StrategyOrchestrator] VWAP={}, 偏离={}%, 位置={}", 
                        String.format("%.2f", vwap.getVwap()),
                        String.format("%.3f", vwap.getDeviationPercent()),
                        vwap.getPositionDescription());
            }
            
            // 🔥 新增-20260213: Supertrend（ATR趋势跟踪+动态止损）
            SupertrendCalculator.SupertrendResult supertrend = supertrendCalculator.calculate(klines);
            if (supertrend != null) {
                context.addIndicator("Supertrend", supertrend);
                log.info("[StrategyOrchestrator] Supertrend={}, {}{}", 
                        String.format("%.2f", supertrend.getSupertrendValue()),
                        supertrend.getTrendDescription(),
                        supertrend.isTrendChanged() ? " ⚡翻转!" : "");
            }
            
            // 🔥 新增-20260213: OBV（成交量确认/背离检测）
            OBVCalculator.OBVResult obv = obvCalculator.calculate(klines);
            if (obv != null) {
                context.addIndicator("OBV", obv);
                log.info("[StrategyOrchestrator] OBV: {}", obv.getVolumeDescription());
            }
            
            log.info("[StrategyOrchestrator] 成功计算 {} 个技术指标（含VWAP/Supertrend/OBV）", context.getIndicators().size());
            
        } catch (Exception e) {
            log.error("[StrategyOrchestrator] 计算技术指标时发生错误", e);
        }
    }
    
    /**
     * 获取市场上下文（供外部使用）
     */
    public MarketContext getMarketContext(String symbol, int klineCount) {
        try {
            List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", klineCount);
            
            if (klines == null || klines.isEmpty()) {
                return null;
            }
            
            BigDecimal currentPrice = klines.get(0).getClosePrice();
            MarketContext context = MarketContext.builder()
                    .symbol(symbol)
                    .klines(klines)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            calculateAllIndicators(context);
            
            return context;
            
        } catch (Exception e) {
            log.error("[StrategyOrchestrator] 获取市场上下文时发生错误", e);
            return null;
        }
    }
    
    /**
     * 创建错误信号
     */
    private TradingSignal createErrorSignal(String symbol, String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .strategyName("StrategyOrchestrator")
                .reason(reason)
                .symbol(symbol)
                .build();
    }
    
    /**
     * 获取活跃策略列表
     */
    public List<Strategy> getActiveStrategies() {
        return compositeStrategy.getActiveStrategies();
    }
    
    /**
     * 获取策略总权重
     */
    public int getTotalStrategyWeight() {
        return compositeStrategy.getTotalWeight();
    }
}
