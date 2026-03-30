package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.*;
import com.ltp.peter.augtrade.indicator.VWAPCalculator;
import com.ltp.peter.augtrade.indicator.SupertrendCalculator;
import com.ltp.peter.augtrade.indicator.OBVCalculator;
import com.ltp.peter.augtrade.indicator.KeyLevelCalculator;
import com.ltp.peter.augtrade.market.MarketDataService;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    
    @Value("${trading.strategy.active:composite}")
    private String activeStrategy;

    @Autowired
    private CompositeStrategy compositeStrategy;

    @Autowired
    private SRRejectionScalpingStrategy srRejectionScalpingStrategy;
    
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

    @Autowired
    private KeyLevelCalculator keyLevelCalculator;

    @Autowired(required = false)
    private HMACalculator hmaCalculator;

    /**
     * 🔥 P0修复-20260316: 信号+上下文联合结果
     *
     * 解决问题：之前 TradingScheduler 分别调用 getMarketContext(100根) 和 generateSignal(200根)，
     * 导致保存到DB的指标值与实际决策使用的不一致
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SignalResult {
        private TradingSignal signal;
        private MarketContext context;
    }

    /**
     * 🔥 P0修复-20260316: 一次性生成信号并返回配套的MarketContext
     *
     * 确保 signal 和 context 使用完全相同的数据集，避免指标值不一致
     */
    public SignalResult generateSignalWithContext(String symbol) {
        return generateSignalWithContext(symbol, 8640); // 8640根=30天，覆盖长线宏观趋势
    }

    /**
     * 🔥 P0修复-20260316: 一次性生成信号并返回配套的MarketContext
     */
    public SignalResult generateSignalWithContext(String symbol, int klineCount) {
        log.info("[StrategyOrchestrator] 开始为 {} 生成交易信号（含上下文）", symbol);

        try {
            List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", klineCount);

            if (klines == null || klines.isEmpty()) {
                log.warn("[StrategyOrchestrator] 无法获取 {} 的K线数据", symbol);
                return new SignalResult(createErrorSignal(symbol, "无K线数据"), null);
            }

            if (!validateKlineQuality(klines)) {
                log.error("[StrategyOrchestrator] ❌ K线数据质量不合格，拒绝生成信号");
                return new SignalResult(createErrorSignal(symbol, "K线数据质量不合格"), null);
            }

            BigDecimal currentPrice = klines.get(0).getClosePrice();
            MarketContext context = MarketContext.builder()
                    .symbol(symbol)
                    .klines(klines)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();

            calculateAllIndicators(context);

            Strategy activeStrategyBean = resolveActiveStrategy();
            log.info("[StrategyOrchestrator] 使用策略: {}", activeStrategyBean.getName());
            TradingSignal signal = activeStrategyBean.generateSignal(context);

            if (signal == null) {
                signal = createErrorSignal(symbol, "策略返回空信号");
            } else {
                log.info("[StrategyOrchestrator] 生成信号: {} - {} (强度: {}, 得分: {})",
                        signal.getType(), signal.getReason(), signal.getStrength(), signal.getScore());
            }

            return new SignalResult(signal, context);

        } catch (Exception e) {
            log.error("[StrategyOrchestrator] 生成交易信号时发生错误", e);
            return new SignalResult(createErrorSignal(symbol, "系统异常: " + e.getMessage()), null);
        }
    }

    /**
     * 生成交易信号（主入口）
     *
     * @param symbol 交易品种
     * @return 交易信号
     */
    public TradingSignal generateSignal(String symbol) {
        return generateSignal(symbol, 8640); // 8640根K线（30天），覆盖长线宏观趋势计算
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

            // 🔥 P0新增-20260316: K线数据质量校验
            // 防止在volume=0或flat candle（OHLC相同）等无效数据上做出交易决策
            if (!validateKlineQuality(klines)) {
                log.error("[StrategyOrchestrator] ❌ K线数据质量不合格，拒绝生成信号");
                return createErrorSignal(symbol, "K线数据质量不合格（存在大量无效K线）");
            }

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
            
            // 5. 使用激活策略生成信号
            TradingSignal signal = resolveActiveStrategy().generateSignal(context);
            
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
            
            // 🔥 P1新增-20260316: 多时间框架宏观趋势
            calculateMacroTrend(context);

            // 🔥 新增-重构v3: HMA趋势（供 Layer2 冲突检测）
            if (hmaCalculator != null) {
                HMACalculator.HMAResult hma = hmaCalculator.calculate(klines);
                if (hma != null) {
                    context.addIndicator("HMA", hma);
                    log.info("[StrategyOrchestrator] HMA20={} 斜率={} 趋势={}",
                            String.format("%.2f", hma.getHma20()), String.format("%.4f", hma.getSlope()), hma.getTrend());
                }
            }

            // 🔥 新增-重构v3: 关键支撑/阻力位（Layer 1 价格结构）
            KeyLevelCalculator.KeyLevelResult keyLevels = keyLevelCalculator.calculate(klines);
            if (keyLevels != null) {
                context.addIndicator("KeyLevels", keyLevels);
            }

            log.info("[StrategyOrchestrator] 成功计算 {} 个技术指标（含VWAP/Supertrend/OBV/MacroTrend/KeyLevels）", context.getIndicators().size());
            
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
     * 🔥 P0修复-20260316: K线数据质量校验
     *
     * 检查最近的K线是否存在质量问题：
     * - 同时满足 volume=0 且 flat candle（OHLC完全相同）才算无效
     *   （单独的volume=0或flat candle在流动性低的品种上是正常现象）
     *
     * 只检查最近5根K线（最近25分钟），避免历史遗留的坏数据误拦截
     * 如果最近5根全部无效才拒绝交易（说明数据源真的有故障）
     */
    private boolean validateKlineQuality(List<Kline> klines) {
        int checkCount = Math.min(5, klines.size());
        int invalidCount = 0;

        for (int i = 0; i < checkCount; i++) {
            Kline kline = klines.get(i);

            // 同时满足volume=0和flat candle才算真正无效
            boolean zeroVolume = kline.getVolume() != null
                    && kline.getVolume().compareTo(BigDecimal.ZERO) == 0;

            boolean flatCandle = kline.getOpenPrice().compareTo(kline.getHighPrice()) == 0
                    && kline.getHighPrice().compareTo(kline.getLowPrice()) == 0
                    && kline.getLowPrice().compareTo(kline.getClosePrice()) == 0;

            if (zeroVolume && flatCandle) {
                invalidCount++;
            }
        }

        if (invalidCount > 0) {
            log.warn("[StrategyOrchestrator] ⚠️ K线质量: 最近{}根中有{}根无效（volume=0且flat candle）",
                    checkCount, invalidCount);
        }

        // 最近5根全部无效才拒绝（数据源真的有故障）
        if (invalidCount >= checkCount) {
            log.error("[StrategyOrchestrator] ❌ 最近{}根K线全部无效，数据源可能故障",
                    checkCount);
            return false;
        }

        return true;
    }

    /**
     * 🔥 P1新增-20260316: 计算多时间框架宏观趋势
     *
     * 三窗口设计：
     *   5h  (60根)   — 短期冲量方向
     *   24h (288根)  — 区分"真上涨"与"大跌中的反弹"
     *   7d  (2016根) — 月级别大趋势：> +3% = MACRO_BULL，< -3% = MACRO_BEAR
     */
    private void calculateMacroTrend(MarketContext context) {
        List<Kline> klines = context.getKlines();

        // 5小时窗口（60根5分钟K线）
        int lookback5h = Math.min(60, klines.size() - 1);
        if (lookback5h < 30) {
            log.debug("[StrategyOrchestrator] K线不足30根，跳过宏观趋势计算");
            return;
        }

        BigDecimal currentPrice = klines.get(0).getClosePrice();

        // 5小时价格变化
        BigDecimal price5hAgo = klines.get(lookback5h).getClosePrice();
        double priceChangePercent = currentPrice.subtract(price5hAgo)
                .divide(price5hAgo, 6, RoundingMode.HALF_UP)
                .doubleValue() * 100;

        // 统计5小时内涨跌K线比例
        int upCount = 0;
        int downCount = 0;
        for (int i = 0; i < lookback5h; i++) {
            BigDecimal close = klines.get(i).getClosePrice();
            BigDecimal prevClose = klines.get(i + 1).getClosePrice();
            int cmp = close.compareTo(prevClose);
            if (cmp > 0) upCount++;
            else if (cmp < 0) downCount++;
        }

        // 24小时参照系，区分"真宏观上涨"和"下跌中的反弹"
        int lookback24h = Math.min(288, klines.size() - 1);
        double priceChange24h = 0.0;
        if (lookback24h >= 60) {
            BigDecimal price24hAgo = klines.get(lookback24h).getClosePrice();
            priceChange24h = currentPrice.subtract(price24hAgo)
                    .divide(price24hAgo, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }

        // 7天参照系（2016根5分钟K线），识别中期趋势
        int lookback7d = Math.min(2016, klines.size() - 1);
        double priceChange7d = 0.0;
        if (lookback7d >= 288) {
            BigDecimal price7dAgo = klines.get(lookback7d).getClosePrice();
            priceChange7d = currentPrice.subtract(price7dAgo)
                    .divide(price7dAgo, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }

        // 30天参照系（8640根5分钟K线），识别长线牛市/熊市
        int lookback30d = Math.min(8640, klines.size() - 1);
        double priceChange30d = 0.0;
        if (lookback30d >= 2016) {
            BigDecimal price30dAgo = klines.get(lookback30d).getClosePrice();
            priceChange30d = currentPrice.subtract(price30dAgo)
                    .divide(price30dAgo, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }

        // 优先级：7d中趋势 > 24h+7d联合信号 > 5h短趋势
        // 30d仅作辅助参考（记录于context），不作硬性拦截
        String macroTrend;
        if (priceChange7d > 1.5) {
            macroTrend = "MACRO_BULL";
        } else if (priceChange7d < -1.5) {
            macroTrend = "MACRO_BEAR";
        } else if (priceChange24h > 0.5 && priceChange7d > 0) {
            macroTrend = "MACRO_BULL";
            log.info("[StrategyOrchestrator] 📈 24h+7d联合上涨(24h:+{}% 7d:+{}%)，识别为MACRO_BULL",
                    String.format("%.2f", priceChange24h),
                    String.format("%.2f", priceChange7d));
        } else if (priceChange24h < -0.5 && priceChange7d < 0) {
            macroTrend = "MACRO_BEAR";
            log.info("[StrategyOrchestrator] 📉 24h+7d联合下跌(24h:{}% 7d:{}%)，识别为MACRO_BEAR",
                    String.format("%.2f", priceChange24h),
                    String.format("%.2f", priceChange7d));
        } else if (priceChangePercent > 0.3 && upCount > downCount) {
            if (lookback24h >= 60 && priceChange24h < -0.5) {
                macroTrend = "MACRO_REBOUND";
                log.info("[StrategyOrchestrator] 🔄 识别为下跌反弹（5h:+{}% 但 24h:{}%），允许做空",
                        String.format("%.2f", priceChangePercent),
                        String.format("%.2f", priceChange24h));
            } else {
                macroTrend = "MACRO_UP";
            }
        } else if (priceChangePercent < -0.3 && downCount > upCount) {
            macroTrend = "MACRO_DOWN";
        } else {
            macroTrend = "MACRO_NEUTRAL";
        }

        context.addIndicator("MacroTrend", macroTrend);
        context.addIndicator("MacroPriceChange", priceChangePercent);
        context.addIndicator("MacroPriceChange24h", priceChange24h);
        context.addIndicator("MacroPriceChange7d", priceChange7d);
        context.addIndicator("MacroPriceChange30d", priceChange30d);

        log.info("[StrategyOrchestrator] 📊 宏观趋势(5h/24h/7d/30d): {} (5h:{}%, 24h:{}%, 7d:{}%, 30d:{}%)",
                macroTrend,
                String.format("%.2f", priceChangePercent),
                String.format("%.2f", priceChange24h),
                String.format("%.2f", priceChange7d),
                String.format("%.2f", priceChange30d));
    }

    /**
     * 创建错误信号
     */
    /**
     * 根据 trading.strategy.active 配置返回对应的策略 Bean
     */
    private Strategy resolveActiveStrategy() {
        if ("sr_rejection".equalsIgnoreCase(activeStrategy)) {
            return srRejectionScalpingStrategy;
        }
        return compositeStrategy;
    }

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
