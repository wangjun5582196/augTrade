package com.ltp.peter.augtrade.scheduler;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.entity.PaperPosition;
import com.ltp.peter.augtrade.indicator.IndicatorService;
import com.ltp.peter.augtrade.market.MarketDataService;
import com.ltp.peter.augtrade.market.RealMarketDataService;
import com.ltp.peter.augtrade.ml.MLRecordService;
import com.ltp.peter.augtrade.notification.FeishuNotificationService;
import com.ltp.peter.augtrade.strategy.SimplifiedTrendStrategy;
import com.ltp.peter.augtrade.strategy.TradingStrategyFactory;
import com.ltp.peter.augtrade.strategy.TradingStrategyService;
import com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator;
import com.ltp.peter.augtrade.trading.broker.BybitTradingService;
import com.ltp.peter.augtrade.trading.execution.PaperTradingService;
import com.ltp.peter.augtrade.trading.execution.TradeExecutionService;
import com.ltp.peter.augtrade.trading.risk.RiskManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 交易定时任务调度器
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class TradingScheduler {
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private RealMarketDataService realMarketDataService;
    
    @Autowired
    private TradingStrategyService strategyService;
    
    @Autowired
    private TradeExecutionService executionService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private MLRecordService mlRecordService;
    
    @Autowired
    private BybitTradingService bybitTradingService;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.trading.broker.BinanceFuturesTradingService binanceFuturesService;
    
    @Autowired
    private StrategyOrchestrator strategyOrchestrator;
    
    @Autowired
    private SimplifiedTrendStrategy simplifiedTrendStrategy;
    
    @Autowired
    private TradingStrategyFactory strategyFactory;
    
    @Autowired
    private PaperTradingService paperTradingService;
    
    @Autowired
    private com.ltp.peter.augtrade.mapper.TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private com.ltp.peter.augtrade.indicator.ATRCalculator atrCalculator;
    
    @Autowired
    private FeishuNotificationService feishuNotificationService;

    @Autowired
    private IndicatorService indicatorService;

    @Autowired
    private com.ltp.peter.augtrade.indicator.EMACalculator emaCalculator;

    @Autowired
    private com.ltp.peter.augtrade.market.OkxKlineDataFetcher okxKlineDataFetcher;
    
    @Value("${trading.gold.symbol:XAUUSD}")
    private String symbol;
    
    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;
    
    @Value("${trading.gold.min-trade-amount:0.01}")
    private BigDecimal minTradeAmount;
    
    @Value("${trading.data-collector.source:mock}")
    private String dataSource;
    
    @Value("${trading.binance.symbol:PAXGUSDT}")
    private String binanceSymbol;
    
    @Value("${trading.binance.interval:1m}")
    private String binanceInterval;
    
    @Value("${bybit.api.enabled:false}")
    private boolean bybitEnabled;
    
    @Value("${bybit.gold.symbol:XAUTUSDT}")
    private String bybitSymbol;
    
    @Value("${bybit.gold.min-qty:0.01}")
    private String bybitMinQty;
    
    @Value("${bybit.risk.stop-loss-dollars:15}")
    private int stopLossDollars;
    
    @Value("${bybit.risk.take-profit-dollars:30}")
    private int takeProfitDollars;
    
    @Value("${bybit.api.paper-trading:false}")
    private boolean paperTrading;
    
    @Value("${bybit.risk.mode:fixed}")
    private String riskMode;
    
    @Value("${bybit.risk.atr-stop-loss-multiplier:1.5}")
    private double atrStopLossMultiplier;
    
    @Value("${bybit.risk.atr-take-profit-multiplier:3.0}")
    private double atrTakeProfitMultiplier;
    
    @Value("${bybit.risk.atr-min-threshold:2.0}")
    private double atrMinThreshold;
    
    @Value("${bybit.risk.atr-max-threshold:15.0}")
    private double atrMaxThreshold;
    
    // 🔥 币安配置
    @Value("${binance.api.enabled:false}")
    private boolean binanceEnabled;
    
    @Value("${binance.futures.symbol:XAUUSDT}")
    private String binanceFuturesSymbol;
    
    @Value("${binance.futures.max-order-amount:10}")
    private int binanceFuturesMaxQty;
    
    @Value("${binance.risk.mode:fixed}")
    private String binanceRiskMode;
    
    @Value("${binance.risk.stop-loss-dollars:8}")
    private int binanceStopLossDollars;
    
    @Value("${binance.risk.take-profit-dollars:15}")
    private int binanceTakeProfitDollars;
    
    @Value("${binance.risk.atr-stop-loss-multiplier:3.0}")
    private double binanceAtrStopLossMultiplier;
    
    @Value("${binance.risk.atr-take-profit-multiplier:4.0}")
    private double binanceAtrTakeProfitMultiplier;
    
    @Value("${binance.risk.atr-min-threshold:1.0}")
    private double binanceAtrMinThreshold;
    
    @Value("${binance.risk.atr-max-threshold:15.0}")
    private double binanceAtrMaxThreshold;
    
    // 🔥 P1修复-20260209: 冷却期从5分钟延长到10分钟
    // 数据分析：今日16笔交易平均22分钟一笔，#374→#375→#376连续追高间隔仅7分钟
    private LocalDateTime lastCloseTime = null;
    private static final int CLOSE_COOLDOWN_SECONDS = 600; // 🔥 从300秒→600秒（10分钟）- 大幅减少频繁交易
    
    // 🔥 P1修复-20260209: 每日交易次数限制从50降至20
    // 数据分析：今日6小时16笔过度交易，多数是追高止损的无效交易
    private static final int MAX_DAILY_TRADES = 20;  // 🔥 从50降至20笔/天
    private LocalDateTime dailyTradeResetTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
    private int dailyTradeCount = 0;
    
    // 🔥 P1修复-20260209: 最后一次开仓时间（用于最小开仓间隔控制）
    private LocalDateTime lastOpenTime = null;
    private static final int MIN_OPEN_INTERVAL_SECONDS = 1800;      // 做多间隔30分钟（防止追高）
    // 🔥 Fix B-20260320: 做空间隔缩短至15分钟，反弹做空窗口短不能错过
    private static final int MIN_OPEN_INTERVAL_SECONDS_SELL = 900;  // 做空间隔15分钟
    
    // 持仓时间管理常量 - ✨ 优化：增加持仓保护期，避免过早平仓
    private static final int MAX_HOLDING_SECONDS = 1800; // 最大持仓30分钟
    private static final int MIN_HOLDING_SECONDS_DEFAULT = 600; // 默认10分钟（从5分钟增加）
    private static final int MIN_HOLDING_SECONDS_PROFIT = 900; // 盈利时15分钟（从10分钟增加）
    private static final int MIN_HOLDING_SECONDS_BIG_PROFIT = 1200; // 大盈利时20分钟（从15分钟增加）
    
    /**
     * 数据采集任务 - 每300秒（5分钟）执行一次
     *
     * 数据源优先级：OKX（主）→ 币安（备）→ Bybit（备）
     *
     * OKX 每次取最近 3 根已完成K线：
     *   - 正常情况：2 根重复（唯一索引跳过），1 根新增
     *   - 上次失败：自动补上漏掉的，无需额外补缺逻辑
     *
     * 启动时额外触发一次补缺，填补服务重启期间的空白。
     */
    @Scheduled(fixedRate = 300000)
    public void collectMarketData() {
        // 等待启动数据加载完成（最多等待30秒）
        if (!com.ltp.peter.augtrade.scheduler.StartupDataLoader.isDataLoaded()) {
            if (!com.ltp.peter.augtrade.scheduler.StartupDataLoader.awaitDataLoaded(30)) {
                log.warn("⏸️ 等待启动数据加载超时，跳过本次数据采集");
                return;
            }
        }
        
        // 优先 OKX（无需 API Key，境内访问稳定，每次取 3 根自动补缺）
        String targetSymbol = binanceEnabled ? binanceFuturesSymbol
                : (bybitEnabled ? bybitSymbol : "XAUUSDT");
        int okxResult = okxKlineDataFetcher.collectLatestKlines(targetSymbol, 3);
        if (okxResult >= 0) {
            // OKX 成功，不需要其他源
            return;
        }
        log.warn("[Scheduler] OKX 采集失败，降级到备用数据源");

        // 备用：币安
        if (binanceEnabled && binanceFuturesService != null) {
            collectBinanceData();
        }
        // 备用：Bybit
        else if (bybitEnabled && bybitTradingService.isEnabled()) {
            collectBybitData();
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean startupRepairDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 启动时补缺：服务重启后填补空白期的 K 线（最多补 48 根 = 4 小时）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void repairKlineGapsOnStartup() {
        if (!startupRepairDone.compareAndSet(false, true)) return; // 只执行一次
        try {
            String targetSymbol = binanceEnabled ? binanceFuturesSymbol
                    : (bybitEnabled ? bybitSymbol : "XAUUSDT");

            // 查数据库最新K线时间戳
            java.util.List<com.ltp.peter.augtrade.entity.Kline> recent =
                    marketDataService.getLatestKlines(targetSymbol, "5m", 1);
            if (recent == null || recent.isEmpty()) return;

            long latestTs = recent.get(0).getTimestamp()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            long nowTs = System.currentTimeMillis();
            long gapMs = nowTs - latestTs;
            int gapBars = (int) (gapMs / (5 * 60 * 1000L)) - 1;

            if (gapBars >= 2) {
                log.info("[Startup] 检测到 {} 根K线缺口（约{}分钟），开始补缺...", gapBars, gapBars * 5);
                int repairCount = Math.min(gapBars + 2, 48);
                okxKlineDataFetcher.repairGap(targetSymbol, latestTs, repairCount);
            }
        } catch (Exception e) {
            log.warn("[Startup] 补缺执行失败: {}", e.getMessage());
        }
    }
    
    /**
     * 🔥 P0修复-20260316：采集币安黄金K线数据（从API获取真实OHLCV）
     *
     * 之前的实现只获取当前价格，导致OHLC全部相同、volume=0，
     * 被 validateKlineQuality 判定为无效数据，一整天无法开单。
     * 现在改为从币安合约API获取最新的真实5分钟K线。
     */
    private void collectBinanceData() {
        try {
            log.debug("从币安获取黄金K线数据: {}", binanceFuturesSymbol);

            // 从币安合约API获取最新1根真实K线
            String url = String.format(
                "https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=5m&limit=1",
                binanceFuturesSymbol
            );

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("币安K线API请求失败: HTTP {}", response.code());
                    // 降级：用价格快照
                    collectBinanceDataFallback();
                    return;
                }

                String responseBody = response.body().string();
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode list = mapper.readTree(responseBody);

                if (!list.isArray() || list.size() == 0) {
                    log.warn("币安K线API返回空数据");
                    collectBinanceDataFallback();
                    return;
                }

                // 解析K线: [开盘时间, 开盘价, 最高价, 最低价, 收盘价, 成交量, 收盘时间, 成交额, ...]
                com.fasterxml.jackson.databind.JsonNode item = list.get(0);
                long timestamp = item.get(0).asLong();

                Kline kline = new Kline();
                kline.setSymbol(binanceFuturesSymbol);
                kline.setInterval("5m");
                kline.setTimestamp(LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault()));
                kline.setOpenPrice(new BigDecimal(item.get(1).asText()));
                kline.setHighPrice(new BigDecimal(item.get(2).asText()));
                kline.setLowPrice(new BigDecimal(item.get(3).asText()));
                kline.setClosePrice(new BigDecimal(item.get(4).asText()));
                kline.setVolume(new BigDecimal(item.get(5).asText()));
                kline.setCreateTime(LocalDateTime.now());
                kline.setUpdateTime(LocalDateTime.now());

                marketDataService.saveKline(kline);
                log.info("✅ 币安黄金K线保存成功: O={} H={} L={} C={} V={}",
                        kline.getOpenPrice(), kline.getHighPrice(),
                        kline.getLowPrice(), kline.getClosePrice(), kline.getVolume());
            }
        } catch (Exception e) {
            log.error("币安K线数据采集失败，尝试降级", e);
            collectBinanceDataFallback();
        }
    }

    /**
     * 降级方案：当K线API不可用时，用当前价格作为快照
     */
    private void collectBinanceDataFallback() {
        try {
            BigDecimal currentPrice = binanceFuturesService.getCurrentPrice(binanceFuturesSymbol);
            Kline kline = new Kline();
            kline.setSymbol(binanceFuturesSymbol);
            kline.setInterval("5m");
            // 对齐到5分钟整点，避免产生带秒数的脏时间戳
            LocalDateTime now = LocalDateTime.now();
            kline.setTimestamp(now.withSecond(0).withNano(0)
                    .withMinute((now.getMinute() / 5) * 5));
            kline.setOpenPrice(currentPrice);
            kline.setHighPrice(currentPrice);
            kline.setLowPrice(currentPrice);
            kline.setClosePrice(currentPrice);
            kline.setVolume(BigDecimal.ZERO);
            kline.setCreateTime(LocalDateTime.now());
            kline.setUpdateTime(LocalDateTime.now());
            marketDataService.saveKline(kline);
            log.warn("⚠️ 降级：使用价格快照保存K线: {}", currentPrice);
        } catch (Exception e) {
            log.error("降级方案也失败", e);
        }
    }
    
    /**
     * 采集Bybit黄金K线数据
     */
    private void collectBybitData() {
        try {
            log.debug("从Bybit获取黄金K线数据: {}", bybitSymbol);
            
            // 从Bybit获取K线
            com.google.gson.JsonArray klines = bybitTradingService.getKlines(bybitSymbol, "5", 1);
            
            if (klines != null && klines.size() > 0) {
                com.google.gson.JsonArray klineData = klines.get(0).getAsJsonArray();
                
                // 解析K线数据 [timestamp, open, high, low, close, volume, turnover]
                Kline kline = new Kline();
                kline.setSymbol(bybitSymbol);
                kline.setInterval("5m");
                // Bybit K线数据：klineData.get(0) 是开盘时间戳（秒），用它对齐时间
                long bybitTs = klineData.get(0).getAsLong();
                kline.setTimestamp(LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(bybitTs), java.time.ZoneId.systemDefault()));
                kline.setOpenPrice(new BigDecimal(klineData.get(1).getAsString()));
                kline.setHighPrice(new BigDecimal(klineData.get(2).getAsString()));
                kline.setLowPrice(new BigDecimal(klineData.get(3).getAsString()));
                kline.setClosePrice(new BigDecimal(klineData.get(4).getAsString()));
                kline.setVolume(new BigDecimal(klineData.get(5).getAsString()));
                kline.setCreateTime(LocalDateTime.now());
                kline.setUpdateTime(LocalDateTime.now());
                
                marketDataService.saveKline(kline);
                log.info("✅ Bybit黄金K线保存成功: 价格={}", kline.getClosePrice());
            }
        } catch (Exception e) {
            log.error("Bybit数据采集失败", e);
        }
    }
    
    /**
     * 策略执行任务 - 🔥 P0修复：每60秒执行一次（从10秒延长，减少噪音交易）
     * 支持币安/Bybit交易黄金（XAUUSDT）
     */
    @Scheduled(fixedRate = 60000)
    public void executeStrategy() {
        if (!strategyEnabled) {
            return;
        }
        
        // 🔥 关键修复：等待启动数据加载完成后才开始交易
        if (!com.ltp.peter.augtrade.scheduler.StartupDataLoader.isDataLoaded()) {
            if (!com.ltp.peter.augtrade.scheduler.StartupDataLoader.awaitDataLoaded(60)) {
                log.warn("⏸️ 启动数据尚未加载完成，暂停交易策略执行");
                return;
            }
            log.info("✅ 启动数据已加载完成，开始执行交易策略");
        }

        // 交易时段过滤（Fix2-20260320）：禁止北京时间 22:00-06:00 交易
        // 数据分析：本周22:00-03:00共4单，全部亏损，合计-$539.80
        // 原因：深夜流动性差、波动随机、主力机构不参与，假突破概率极高
        // 允许时段：北京时间 06:00-22:00（覆盖亚盘+欧盘+美盘）
        int bjHour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        if (bjHour >= 22 || bjHour < 6) {
            log.debug("⏰ 北京时间 {}:xx 处于禁止交易时段(22:00-06:00)，跳过本轮策略", bjHour);
            return;
        }

        // 🔥 优先使用币安（如果启用）
        if (binanceEnabled && binanceFuturesService != null) {
            executeBinanceStrategy();
        }
        // 如果启用Bybit，使用Bybit交易
        else if (bybitEnabled && bybitTradingService.isEnabled()) {
            executeBybitStrategy();
        } else {
            log.warn("⚠️ 币安和Bybit均未启用，无法执行策略");
        }
    }
    
    /**
     * 🔥 新增：币安黄金交易策略
     */
    private void executeBinanceStrategy() {
        try {
            log.info("========================================");
            log.info("【币安黄金交易策略】开始执行 - 交易品种: {}", binanceFuturesSymbol);
            
            // 1. 获取当前价格
            BigDecimal currentPrice = binanceFuturesService.getCurrentPrice(binanceFuturesSymbol);
            log.info("当前黄金价格: ${}", currentPrice);

            // 2. 单次信号生成（修复：原来三重调用导致信号/数据不一致）
            // generateSignalWithContext 同时返回信号和上下文，供后续执行和数据记录复用
            com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator.SignalResult signalResult =
                    strategyOrchestrator.generateSignalWithContext(binanceFuturesSymbol);
            com.ltp.peter.augtrade.strategy.signal.TradingSignal tradingSignal = signalResult.getSignal();

            log.info("📊 策略输出: {} (强度:{}, 得分:{}) - {}",
                    tradingSignal.getType(), tradingSignal.getStrength(),
                    tradingSignal.getScore(), tradingSignal.getReason());
            
            // 3. 检查每日交易次数限制
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
            
            if (dailyTradeResetTime.isBefore(todayStart)) {
                log.info("📅 新的一天开始,重置每日交易计数器");
                dailyTradeCount = 0;
                dailyTradeResetTime = todayStart;
            }
            
            if (dailyTradeCount >= MAX_DAILY_TRADES) {
                log.warn("⛔ 今日交易次数已达上限({}/{}),暂停交易直到明天", 
                        dailyTradeCount, MAX_DAILY_TRADES);
                log.info("========================================");
                return;
            }
            
            // 4. 检查冷却期
            if (lastCloseTime != null) {
                long secondsSinceClose = Duration.between(lastCloseTime, LocalDateTime.now()).getSeconds();
                if (secondsSinceClose < CLOSE_COOLDOWN_SECONDS) {
                    log.info("⏸️ 平仓冷却期中（剩余{}秒），防止频繁开仓", CLOSE_COOLDOWN_SECONDS - secondsSinceClose);
                    log.info("========================================");
                    return;
                }
            }
            
            // 5. 检查信号反转并平仓
            if (paperTrading && paperTradingService.hasOpenPosition()) {
                handleSignalReversal(currentPrice, tradingSignal, binanceFuturesSymbol);
                if (!paperTradingService.hasOpenPosition()) {
                    log.info("========================================");
                    return;
                }
            }
            
            // 6. 检查最小开仓间隔（Fix B-20260320：做空间隔15分钟，做多间隔30分钟）
            if (lastOpenTime != null) {
                long secondsSinceLastOpen = Duration.between(lastOpenTime, LocalDateTime.now()).getSeconds();
                boolean isSellSignal = tradingSignal.getType() ==
                        com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL;
                int requiredInterval = isSellSignal ? MIN_OPEN_INTERVAL_SECONDS_SELL : MIN_OPEN_INTERVAL_SECONDS;
                if (secondsSinceLastOpen < requiredInterval) {
                    log.info("⏸️ 距离上次开仓仅{}秒（{}需要≥{}秒）",
                            secondsSinceLastOpen, isSellSignal ? "做空" : "做多", requiredInterval);
                    log.info("========================================");
                    return;
                }
            }

            // 7. 趋势反转检测
            if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
                if (detectTrendReversal(binanceFuturesSymbol)) {
                    log.warn("🚨 检测到趋势反转（EMA死叉），禁止做多！等待趋势明确");
                    log.info("========================================");
                    return;
                }
            }
            
            // 8. 市场状态识别（复用已有上下文，避免重复拉K线）
            MarketRegime regime = detectMarketRegime(signalResult.getContext());
            int requiredStrength = calculateRequiredStrength(regime, tradingSignal, signalResult.getContext());
            
            // 9. 根据信号执行交易
            if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
                if (tradingSignal.getStrength() < requiredStrength) {
                    log.info("⏸️ 做多信号强度{}不足（市场状态：{}，需要≥{}），暂不开仓", 
                            tradingSignal.getStrength(), regime, requiredStrength);
                } else {
                    log.info("🔥 收到高质量做多信号（强度{}，市场：{}）！准备做多黄金", 
                            tradingSignal.getStrength(), regime);
                    boolean success = executeBinanceBuy(currentPrice, signalResult);
                    if (success) {
                        dailyTradeCount++;
                        lastOpenTime = LocalDateTime.now();
                        log.info("📊 今日交易次数: {}/{}", dailyTradeCount, MAX_DAILY_TRADES);
                    }
                }
            } else if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
                // 做空使用与做多相同的强度门槛（原+15惩罚导致做空几乎不可能触发）
                int shortRequiredStrength = requiredStrength;

                if (tradingSignal.getStrength() < shortRequiredStrength) {
                    log.info("🚫 做空信号强度{}不足（市场：{}，需要≥{}），暂不开空仓",
                            tradingSignal.getStrength(), regime, shortRequiredStrength);
                } else {
                    log.warn("⚡ 收到做空信号（强度{}≥{}，市场：{}）！准备做空",
                            tradingSignal.getStrength(), shortRequiredStrength, regime);
                    boolean success = executeBinanceSell(currentPrice, signalResult);
                    if (success) {
                        dailyTradeCount++;
                        lastOpenTime = LocalDateTime.now();
                        log.info("📊 今日交易次数: {}/{}", dailyTradeCount, MAX_DAILY_TRADES);
                    }
                }
            } else {
                log.debug("⏸️ 保持观望，等待高质量信号（市场状态：{}）", regime);
            }
            
            log.info("策略执行完成");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("币安策略执行失败", e);
        }
    }
    
    /**
     * 🔥 新增：通过币安做多黄金
     */
    private boolean executeBinanceBuy(BigDecimal currentPrice,
            com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator.SignalResult signalResult) {
        try {
            // 计算止损止盈
            BigDecimal stopLoss;
            BigDecimal takeProfit;

            if ("atr".equalsIgnoreCase(binanceRiskMode)) {
                java.util.List<Kline> klines = marketDataService.getLatestKlines(binanceFuturesSymbol, "5m", 50);
                if (klines != null && klines.size() >= 15) {
                    if (!atrCalculator.isVolatilitySuitableForTrading(klines, binanceAtrMinThreshold, binanceAtrMaxThreshold)) {
                        log.warn("⚠️ 市场波动不适合交易，放弃开仓");
                        return false;
                    }

                    stopLoss = atrCalculator.calculateDynamicStopLoss(klines, currentPrice, "LONG", binanceAtrStopLossMultiplier);
                    takeProfit = atrCalculator.calculateDynamicTakeProfit(klines, currentPrice, "LONG", binanceAtrTakeProfitMultiplier);

                    if (stopLoss == null || takeProfit == null) {
                        stopLoss = currentPrice.subtract(new BigDecimal(binanceStopLossDollars));
                        takeProfit = currentPrice.add(new BigDecimal(binanceTakeProfitDollars));
                    }
                } else {
                    stopLoss = currentPrice.subtract(new BigDecimal(binanceStopLossDollars));
                    takeProfit = currentPrice.add(new BigDecimal(binanceTakeProfitDollars));
                }
            } else {
                stopLoss = currentPrice.subtract(new BigDecimal(binanceStopLossDollars));
                takeProfit = currentPrice.add(new BigDecimal(binanceTakeProfitDollars));
            }

            log.info("📊 止损: ${}, 止盈: ${}", stopLoss, takeProfit);

            if (paperTrading) {
                // 复用外层已生成的 signal+context，避免重复调用（修复三重信号生成问题）
                PaperPosition position = paperTradingService.openPosition(
                        binanceFuturesSymbol,
                        "LONG",
                        currentPrice,
                        new BigDecimal(binanceFuturesMaxQty),
                        stopLoss,
                        takeProfit,
                        "BinanceFutures",
                        signalResult.getSignal(),
                        signalResult.getContext()
                );

                if (position != null) {
                    log.info("✅ 币安模拟做多成功 - 持仓ID: {}", position.getPositionId());
                    return true;
                }
                return false;
            } else {
                log.warn("⚠️ 币安实盘交易需要额外配置，当前仅支持模拟交易");
                return false;
            }

        } catch (Exception e) {
            log.error("❌ 币安做多失败", e);
            return false;
        }
    }
    
    /**
     * 🔥 新增：通过币安做空黄金
     */
    private boolean executeBinanceSell(BigDecimal currentPrice,
            com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator.SignalResult signalResult) {
        try {
            // 计算止损止盈
            BigDecimal stopLoss;
            BigDecimal takeProfit;

            if ("atr".equalsIgnoreCase(binanceRiskMode)) {
                java.util.List<Kline> klines = marketDataService.getLatestKlines(binanceFuturesSymbol, "5m", 50);
                if (klines != null && klines.size() >= 15) {
                    if (!atrCalculator.isVolatilitySuitableForTrading(klines, binanceAtrMinThreshold, binanceAtrMaxThreshold)) {
                        log.warn("⚠️ 市场波动不适合交易，放弃开仓");
                        return false;
                    }

                    stopLoss = atrCalculator.calculateDynamicStopLoss(klines, currentPrice, "SHORT", binanceAtrStopLossMultiplier);
                    takeProfit = atrCalculator.calculateDynamicTakeProfit(klines, currentPrice, "SHORT", binanceAtrTakeProfitMultiplier);

                    if (stopLoss == null || takeProfit == null) {
                        stopLoss = currentPrice.add(new BigDecimal(binanceStopLossDollars));
                        takeProfit = currentPrice.subtract(new BigDecimal(binanceTakeProfitDollars));
                    }
                } else {
                    stopLoss = currentPrice.add(new BigDecimal(binanceStopLossDollars));
                    takeProfit = currentPrice.subtract(new BigDecimal(binanceTakeProfitDollars));
                }
            } else {
                stopLoss = currentPrice.add(new BigDecimal(binanceStopLossDollars));
                takeProfit = currentPrice.subtract(new BigDecimal(binanceTakeProfitDollars));
            }

            log.info("📊 止损: ${}, 止盈: ${}", stopLoss, takeProfit);

            if (paperTrading) {
                // 复用外层已生成的 signal+context，避免重复调用（修复三重信号生成问题）
                PaperPosition position = paperTradingService.openPosition(
                        binanceFuturesSymbol,
                        "SHORT",
                        currentPrice,
                        new BigDecimal(binanceFuturesMaxQty),
                        stopLoss,
                        takeProfit,
                        "BinanceFutures",
                        signalResult.getSignal(),
                        signalResult.getContext()
                );

                if (position != null) {
                    log.info("✅ 币安模拟做空成功 - 持仓ID: {}", position.getPositionId());
                    return true;
                }
                return false;
            } else {
                log.warn("⚠️ 币安实盘交易需要额外配置，当前仅支持模拟交易");
                return false;
            }

        } catch (Exception e) {
            log.error("❌ 币安做空失败", e);
            return false;
        }
    }
    
    /**
     * 🔥 新增：统一处理信号反转逻辑
     */
    private void handleSignalReversal(BigDecimal currentPrice, 
                                     com.ltp.peter.augtrade.strategy.signal.TradingSignal tradingSignal,
                                     String symbol) {
        com.ltp.peter.augtrade.entity.PaperPosition currentPosition = paperTradingService.getCurrentPosition();
        
        // 计算持仓时间
        long holdingSeconds = Duration.between(currentPosition.getOpenTime(), LocalDateTime.now()).getSeconds();
        BigDecimal unrealizedPnL = currentPosition.getUnrealizedPnL();
        
        // 最大持仓时间检查
        if (holdingSeconds > MAX_HOLDING_SECONDS) {
            if (unrealizedPnL.compareTo(BigDecimal.ZERO) > 0 || 
                unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.5"))) < 0) {
                log.warn("⏰ 超过最大持仓时间{}分钟，强制平仓", MAX_HOLDING_SECONDS / 60);
                paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                lastCloseTime = LocalDateTime.now();
                return;
            }
        }
        
        // 动态持仓保护期
        int minHoldingTime = calculateMinHoldingTime(unrealizedPnL, tradingSignal);
        
        if (holdingSeconds < minHoldingTime) {
            log.info("⏰ 持仓保护中：持仓{}秒 < 需要{}秒，忽略信号反转", holdingSeconds, minHoldingTime);
            return;
        }
        
        // 检查信号反转条件（严格）
        boolean shouldReverse = false;
        if (currentPosition.getSide().equals("LONG") && 
            tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
            shouldReverse = checkReverseConditions(unrealizedPnL, tradingSignal, currentPosition);
        } else if (currentPosition.getSide().equals("SHORT") && 
                   tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
            shouldReverse = checkReverseConditions(unrealizedPnL, tradingSignal, currentPosition);
        }
        
        if (shouldReverse) {
            log.warn("🚨 满足反转条件！信号强度{}，盈亏${}，平仓", 
                     tradingSignal.getStrength(), unrealizedPnL);
            paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
            lastCloseTime = LocalDateTime.now();
        }
    }
    
    /**
     * 🔥 新增：检查是否满足反转条件
     */
    private boolean checkReverseConditions(BigDecimal unrealizedPnL, 
                                          com.ltp.peter.augtrade.strategy.signal.TradingSignal signal,
                                          PaperPosition position) {
        // 规则1：盈利>$50时，禁止信号反转
        if (unrealizedPnL.compareTo(new BigDecimal("50")) > 0) {
            return false;
        }
        
        // 规则2：小幅盈亏(±$20)时，禁止信号反转
        if (unrealizedPnL.abs().compareTo(new BigDecimal("20")) < 0) {
            return false;
        }
        
        // 规则3：移动止损启动后，禁止信号反转
        if (position.getTrailingStopEnabled() != null && position.getTrailingStopEnabled()) {
            return false;
        }
        
        // 规则4：只在超强信号(≥90)且亏损>$30时才反转
        if (signal.getStrength() < 90) {
            return false;
        }
        
        if (unrealizedPnL.compareTo(new BigDecimal("-30")) > 0) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Bybit黄金交易策略
     */
    private void executeBybitStrategy() {
        try {
            log.info("========================================");
            log.info("【Bybit黄金交易策略】开始执行 - 交易品种: {}", bybitSymbol);
            
            // 1. 获取当前价格
            BigDecimal currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
            log.info("当前黄金价格: ${}", currentPrice);
            
            // 2. 🔥 使用策略工厂（根据配置自动选择策略）
            TradingStrategyFactory.Signal strategySignal = strategyFactory.generateSignal(bybitSymbol);
            
            log.info("📊 策略工厂输出: {} - {}", strategySignal, strategyFactory.getStrategyDescription());
            
            // 转换为TradingSignal格式（用于兼容现有代码）
            com.ltp.peter.augtrade.strategy.signal.TradingSignal tradingSignal = 
                    convertFactorySignalToTradingSignal(strategySignal, bybitSymbol);
            
            // 🔥 P0修复-20260115: 检查每日交易次数限制
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
            
            // 如果是新的一天,重置计数器
            if (dailyTradeResetTime.isBefore(todayStart)) {
                log.info("📅 新的一天开始,重置每日交易计数器");
                dailyTradeCount = 0;
                dailyTradeResetTime = todayStart;
            }
            
            // 检查是否超过每日交易限制
            if (dailyTradeCount >= MAX_DAILY_TRADES) {
                log.warn("⛔ 今日交易次数已达上限({}/{}),暂停交易直到明天", 
                        dailyTradeCount, MAX_DAILY_TRADES);
                log.info("========================================");
                return;
            }
            
            // 3. 检查冷却期（包括止损/止盈/信号反转的冷却）
            if (lastCloseTime != null) {
                long secondsSinceClose = Duration.between(lastCloseTime, LocalDateTime.now()).getSeconds();
                if (secondsSinceClose < CLOSE_COOLDOWN_SECONDS) {
                    log.info("⏸️ 平仓冷却期中（剩余{}秒），防止频繁开仓", CLOSE_COOLDOWN_SECONDS - secondsSinceClose);
                    log.info("========================================");
                    return;
                }
            }
            
            // 4. 检查信号反转并平仓（带动态持仓时间保护和最大持仓时间）
            if (paperTrading && paperTradingService.hasOpenPosition()) {
                com.ltp.peter.augtrade.entity.PaperPosition currentPosition = paperTradingService.getCurrentPosition();
                
                // 计算持仓时间
                long holdingSeconds = Duration.between(currentPosition.getOpenTime(), LocalDateTime.now()).getSeconds();
                BigDecimal unrealizedPnL = currentPosition.getUnrealizedPnL();
                
                // ✅ 优化1：最大持仓时间检查（30分钟强制平仓）
                if (holdingSeconds > MAX_HOLDING_SECONDS) {
                    if (unrealizedPnL.compareTo(BigDecimal.ZERO) > 0) {
                        log.warn("⏰ 超过最大持仓时间{}分钟且盈利${}，强制平仓保护利润", 
                                MAX_HOLDING_SECONDS / 60, unrealizedPnL);
                        paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                        lastCloseTime = LocalDateTime.now();
                        log.info("========================================");
                        return;
                    } else if (unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.5"))) < 0) {
                        log.warn("⏰ 超过最大持仓时间{}分钟且亏损${}，强制平仓止损", 
                                MAX_HOLDING_SECONDS / 60, unrealizedPnL);
                        paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                        lastCloseTime = LocalDateTime.now();
                        log.info("========================================");
                        return;
                    }
                }
                
                // ✅ 优化2：动态持仓保护期（根据盈利情况和ADX调整）
                int minHoldingTime = calculateMinHoldingTime(unrealizedPnL, tradingSignal);
                
                // 先判断大的，再判断小的
                if (unrealizedPnL.compareTo(new BigDecimal(takeProfitDollars).multiply(new BigDecimal("0.5"))) > 0) {
                    // 盈利>50%止盈目标
                    log.info("💰💰 盈利${}（已达50%止盈目标），持有{}秒，保护期{}秒", 
                            unrealizedPnL, holdingSeconds, minHoldingTime);
                } else if (unrealizedPnL.compareTo(new BigDecimal(takeProfitDollars).multiply(new BigDecimal("0.3"))) > 0) {
                    // 盈利>30%止盈目标
                    log.info("💎 盈利${}（达到30%止盈），持有{}秒，保护期{}秒，距离止盈${}", 
                            unrealizedPnL, holdingSeconds, minHoldingTime, 
                            new BigDecimal(takeProfitDollars).subtract(unrealizedPnL));
                } else if (unrealizedPnL.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("💰 盈利${}，持有{}秒，保护期{}秒", 
                            unrealizedPnL, holdingSeconds, minHoldingTime);
                } else if (unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.3"))) < 0) {
                    // 亏损>30%止损
                    log.warn("📉 亏损${}（已达30%止损），持有{}秒，保护期{}秒", 
                            unrealizedPnL, holdingSeconds, minHoldingTime);
                }
                
                if (holdingSeconds < minHoldingTime) {
                    log.info("⏰ 持仓保护中：持仓{}秒 < 需要{}秒，忽略信号反转", holdingSeconds, minHoldingTime);
                    log.info("========================================");
                    return;
                }
                
                // 🔥 P0修复：持有多头，出现做空信号 → 平仓（严格限制条件）
                if (currentPosition.getSide().equals("LONG") && 
                    tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
                    
                    // 🔥 规则1：盈利>$50时，禁止信号反转，只能止盈或移动止损
                    if (unrealizedPnL.compareTo(new BigDecimal("50")) > 0) {
                        log.info("💰 持仓盈利${}超过$50，禁止信号反转，等待止盈或移动止损", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则2：小幅盈亏(±$20)时，禁止信号反转，给予更多空间
                    if (unrealizedPnL.abs().compareTo(new BigDecimal("20")) < 0) {
                        log.info("⚠️ 盈亏${}在±$20内，禁止信号反转，给予更多空间", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则3：移动止损启动后，禁止信号反转
                    if (currentPosition.getTrailingStopEnabled() != null && currentPosition.getTrailingStopEnabled()) {
                        log.info("🔒 移动止损已启动，禁止信号反转，保护利润${}",unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则4：只在超强信号(≥90)且亏损>$30时才反转
                    if (tradingSignal.getStrength() < 90) {
                        log.info("⚠️ 反转信号强度{}不足（需要≥90），持仓盈亏${}但不平仓", 
                                tradingSignal.getStrength(), unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    if (unrealizedPnL.compareTo(new BigDecimal("-30")) > 0) {
                        log.info("⚠️ 亏损${}未达到$30，即使信号强度{}也不反转", 
                                unrealizedPnL, tradingSignal.getStrength());
                        log.info("========================================");
                        return;
                    }
                    
                    // 满足所有严格条件才反转：亏损>$30 且 信号强度≥90
                    log.warn("🚨 满足反转条件！持有多头，强做空信号（强度{}），亏损${}，持仓{}秒后平仓", 
                             tradingSignal.getStrength(), unrealizedPnL, holdingSeconds);
                    paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                    lastCloseTime = LocalDateTime.now();
                    log.info("🔒 启动{}秒冷却期，防止频繁交易", CLOSE_COOLDOWN_SECONDS);
                    log.info("========================================");
                    return;
                }
                
                // 🔥 P0修复：持有空头，出现做多信号 → 平仓（严格限制条件）
                if (currentPosition.getSide().equals("SHORT") && 
                    tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
                    
                    // 🔥 规则1：盈利>$50时，禁止信号反转
                    if (unrealizedPnL.compareTo(new BigDecimal("50")) > 0) {
                        log.info("💰 持仓盈利${}超过$50，禁止信号反转，等待止盈或移动止损", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则2：小幅盈亏(±$20)时，禁止信号反转
                    if (unrealizedPnL.abs().compareTo(new BigDecimal("20")) < 0) {
                        log.info("⚠️ 盈亏${}在±$20内，禁止信号反转，给予更多空间", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则3：移动止损启动后，禁止信号反转
                    if (currentPosition.getTrailingStopEnabled() != null && currentPosition.getTrailingStopEnabled()) {
                        log.info("🔒 移动止损已启动，禁止信号反转，保护利润${}",unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 🔥 规则4：只在超强信号(≥90)且亏损>$30时才反转
                    if (tradingSignal.getStrength() < 90) {
                        log.info("⚠️ 反转信号强度{}不足（需要≥90），持仓盈亏${}但不平仓", 
                                tradingSignal.getStrength(), unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    if (unrealizedPnL.compareTo(new BigDecimal("-30")) > 0) {
                        log.info("⚠️ 亏损${}未达到$30，即使信号强度{}也不反转", 
                                unrealizedPnL, tradingSignal.getStrength());
                        log.info("========================================");
                        return;
                    }
                    
                    // 满足所有严格条件才反转：亏损>$30 且 信号强度≥90
                    log.warn("🚨 满足反转条件！持有空头，强做多信号（强度{}），亏损${}，持仓{}秒后平仓", 
                             tradingSignal.getStrength(), unrealizedPnL, holdingSeconds);
                    paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                    lastCloseTime = LocalDateTime.now();
                    log.info("🔒 启动{}秒冷却期，防止频繁交易", CLOSE_COOLDOWN_SECONDS);
                    log.info("========================================");
                    return;
                }
            }
            
            // 🔥 P1修复-20260209 / Fix B-20260320：最小开仓间隔检查（做空15分钟，做多30分钟）
            if (lastOpenTime != null) {
                long secondsSinceLastOpen = Duration.between(lastOpenTime, LocalDateTime.now()).getSeconds();
                boolean isSellSignal = tradingSignal.getType() ==
                        com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL;
                int requiredInterval = isSellSignal ? MIN_OPEN_INTERVAL_SECONDS_SELL : MIN_OPEN_INTERVAL_SECONDS;
                if (secondsSinceLastOpen < requiredInterval) {
                    log.info("⏸️ 距离上次开仓仅{}秒（{}需要≥{}秒）",
                            secondsSinceLastOpen, isSellSignal ? "做空" : "做多", requiredInterval);
                    log.info("========================================");
                    return;
                }
            }
            
            // 🔥 P1修复-20260209：趋势反转检测（EMA死叉=停止做多）
            // 数据分析：#378大亏$242的根因是14:00后趋势由上转下未识别
            // 当EMA20下穿EMA50时（死叉），禁止做多
            if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
                if (detectTrendReversal(bybitSymbol)) {
                    log.warn("🚨 检测到趋势反转（EMA死叉），禁止做多！等待趋势明确");
                    log.info("========================================");
                    return;
                }
            }
            
            // 🔥 P0修复：添加震荡市识别
            MarketRegime regime = detectMarketRegime(bybitSymbol);
            int requiredStrength = calculateRequiredStrength(regime, tradingSignal);
            
            // 5. 根据信号执行交易（动态调整开仓门槛）
            if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY) {
                if (tradingSignal.getStrength() < requiredStrength) {
                    log.info("⏸️ 做多信号强度{}不足（市场状态：{}，需要≥{}），暂不开仓", 
                            tradingSignal.getStrength(), regime, requiredStrength);
                } else {
                    log.info("🔥 收到高质量做多信号（强度{}，市场：{}）！准备做多黄金", 
                            tradingSignal.getStrength(), regime);
                    // 🔥 修复：只在开仓成功时才增加计数器
                    boolean success = executeBybitBuy(currentPrice);
                    if (success) {
                        dailyTradeCount++;
                        lastOpenTime = LocalDateTime.now(); // 🔥 P1修复-20260209: 记录开仓时间
                        log.info("📊 今日交易次数: {}/{}", dailyTradeCount, MAX_DAILY_TRADES);
                    }
                }
            } else if (tradingSignal.getType() == com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL) {
                // 做空使用与做多相同的强度门槛（原+15惩罚导致做空几乎不可能触发）
                int shortRequiredStrength = requiredStrength;

                if (tradingSignal.getStrength() < shortRequiredStrength) {
                    log.info("🚫 做空信号强度{}不足（市场：{}，需要≥{}），暂不开空仓",
                            tradingSignal.getStrength(), regime, shortRequiredStrength);
                } else {
                    log.warn("⚡ 收到做空信号（强度{}≥{}，市场：{}）！准备做空",
                            tradingSignal.getStrength(), shortRequiredStrength, regime);
                    // 🔥 修复：只在开仓成功时才增加计数器
                    boolean success = executeBybitSell(currentPrice);
                    if (success) {
                        dailyTradeCount++;
                        lastOpenTime = LocalDateTime.now(); // 🔥 P1修复-20260209: 记录开仓时间
                        log.info("📊 今日交易次数: {}/{}", dailyTradeCount, MAX_DAILY_TRADES);
                    }
                }
            } else {
                log.debug("⏸️ 保持观望，等待高质量信号（市场状态：{}）", regime);
            }
            
            log.info("策略执行完成");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("Bybit策略执行失败", e);
        }
    }
    
    /**
     * 通过Bybit做多黄金
     * @return 是否成功开仓
     */
    private boolean executeBybitBuy(BigDecimal currentPrice) {
        try {
            // 计算止损止盈价格（支持固定和ATR动态模式）
            BigDecimal stopLoss;
            BigDecimal takeProfit;
            
            if ("atr".equalsIgnoreCase(riskMode)) {
                // ATR动态模式
                java.util.List<Kline> klines = marketDataService.getLatestKlines(bybitSymbol, "5m", 50);
                if (klines != null && klines.size() >= 15) {
                    // 检查ATR波动率是否适合交易
                    if (!atrCalculator.isVolatilitySuitableForTrading(klines, atrMinThreshold, atrMaxThreshold)) {
                        log.warn("⚠️ 市场波动不适合交易，放弃开仓");
                        return false;
                    }
                    
                    // 计算ATR动态止损止盈
                    stopLoss = atrCalculator.calculateDynamicStopLoss(klines, currentPrice, "LONG", atrStopLossMultiplier);
                    takeProfit = atrCalculator.calculateDynamicTakeProfit(klines, currentPrice, "LONG", atrTakeProfitMultiplier);
                    
                    if (stopLoss == null || takeProfit == null) {
                        log.warn("ATR计算失败，使用固定止损止盈");
                        stopLoss = currentPrice.subtract(new BigDecimal(stopLossDollars));
                        takeProfit = currentPrice.add(new BigDecimal(takeProfitDollars));
                    } else {
                        log.info("📊 使用ATR动态止损止盈 - 止损: ${}, 止盈: ${}", stopLoss, takeProfit);
                    }
                } else {
                    log.warn("K线数据不足，使用固定止损止盈");
                    stopLoss = currentPrice.subtract(new BigDecimal(stopLossDollars));
                    takeProfit = currentPrice.add(new BigDecimal(takeProfitDollars));
                }
            } else {
                // 固定模式
                stopLoss = currentPrice.subtract(new BigDecimal(stopLossDollars));
                takeProfit = currentPrice.add(new BigDecimal(takeProfitDollars));
                log.info("📊 使用固定止损止盈 - 止损: ${}, 止盈: ${}", stopLoss, takeProfit);
            }
            
            if (paperTrading) {
                // 🎯 模拟交易模式 - 开仓并持有
                // 🔥 P0修复-20260316: 使用 generateSignalWithContext 确保 signal 和 context 使用同一份数据
                com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator.SignalResult result =
                        strategyOrchestrator.generateSignalWithContext(bybitSymbol);

                PaperPosition position = paperTradingService.openPosition(
                        bybitSymbol,
                        "LONG",
                        currentPrice,
                        new BigDecimal(bybitMinQty),
                        stopLoss,
                        takeProfit,
                        "AggressiveML",
                        result.getSignal(),
                        result.getContext()
                );
                
                // 🔥 修复：检查是否成功开仓
                if (position != null) {
                    log.info("✅ 模拟做多成功 - 持仓ID: {}", position.getPositionId());
                    return true;
                } else {
                    log.warn("❌ 模拟做多失败 - 可能已有持仓");
                    return false;
                }
                
            } else {
                // 💰 真实交易模式
                String orderId = bybitTradingService.placeMarketOrder(
                    bybitSymbol,
                    "Buy",
                    bybitMinQty,
                    stopLoss.toPlainString(),
                    takeProfit.toPlainString()
                );
                
                log.info("✅ Bybit做多成功 - OrderId: {}, 数量: {}盎司, 止损: ${}, 止盈: ${}",
                        orderId, bybitMinQty, stopLoss, takeProfit);
                return true;
            }
                    
        } catch (Exception e) {
            log.error("❌ Bybit做多失败", e);
            return false;
        }
    }
    
    /**
     * 通过Bybit做空黄金
     * @return 是否成功开仓
     */
    private boolean executeBybitSell(BigDecimal currentPrice) {
        try {
            // 计算止损止盈价格（支持固定和ATR动态模式）
            BigDecimal stopLoss;
            BigDecimal takeProfit;
            
            if ("atr".equalsIgnoreCase(riskMode)) {
                // ATR动态模式
                java.util.List<Kline> klines = marketDataService.getLatestKlines(bybitSymbol, "5m", 50);
                if (klines != null && klines.size() >= 15) {
                    // 检查ATR波动率是否适合交易
                    if (!atrCalculator.isVolatilitySuitableForTrading(klines, atrMinThreshold, atrMaxThreshold)) {
                        log.warn("⚠️ 市场波动不适合交易，放弃开仓");
                        return false;
                    }
                    
                    // 计算ATR动态止损止盈
                    stopLoss = atrCalculator.calculateDynamicStopLoss(klines, currentPrice, "SHORT", atrStopLossMultiplier);
                    takeProfit = atrCalculator.calculateDynamicTakeProfit(klines, currentPrice, "SHORT", atrTakeProfitMultiplier);
                    
                    if (stopLoss == null || takeProfit == null) {
                        log.warn("ATR计算失败，使用固定止损止盈");
                        stopLoss = currentPrice.add(new BigDecimal(stopLossDollars));
                        takeProfit = currentPrice.subtract(new BigDecimal(takeProfitDollars));
                    } else {
                        log.info("📊 使用ATR动态止损止盈 - 止损: ${}, 止盈: ${}", stopLoss, takeProfit);
                    }
                } else {
                    log.warn("K线数据不足，使用固定止损止盈");
                    stopLoss = currentPrice.add(new BigDecimal(stopLossDollars));
                    takeProfit = currentPrice.subtract(new BigDecimal(takeProfitDollars));
                }
            } else {
                // 固定模式
                stopLoss = currentPrice.add(new BigDecimal(stopLossDollars));
                takeProfit = currentPrice.subtract(new BigDecimal(takeProfitDollars));
                log.info("📊 使用固定止损止盈 - 止损: ${}, 止盈: ${}", stopLoss, takeProfit);
            }
            
            if (paperTrading) {
                // 🎯 模拟交易模式 - 开仓并持有
                // 🔥 P0修复-20260316: 使用 generateSignalWithContext 确保 signal 和 context 使用同一份数据
                com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator.SignalResult result =
                        strategyOrchestrator.generateSignalWithContext(bybitSymbol);

                PaperPosition position = paperTradingService.openPosition(
                        bybitSymbol,
                        "SHORT",
                        currentPrice,
                        new BigDecimal(bybitMinQty),
                        stopLoss,
                        takeProfit,
                        "AggressiveML",
                        result.getSignal(),
                        result.getContext()
                );
                
                // 🔥 修复：检查是否成功开仓
                if (position != null) {
                    log.info("✅ 模拟做空成功 - 持仓ID: {}", position.getPositionId());
                    return true;
                } else {
                    log.warn("❌ 模拟做空失败 - 可能已有持仓");
                    return false;
                }
                
            } else {
                // 💰 真实交易模式
                String orderId = bybitTradingService.placeMarketOrder(
                    bybitSymbol,
                    "Sell",
                    bybitMinQty,
                    stopLoss.toPlainString(),
                    takeProfit.toPlainString()
                );
                
                log.info("✅ Bybit做空成功 - OrderId: {}, 数量: {}盎司, 止损: ${}, 止盈: ${}",
                        orderId, bybitMinQty, stopLoss, takeProfit);
                return true;
            }
                    
        } catch (Exception e) {
            log.error("❌ Bybit做空失败", e);
            return false;
        }
    }
    
    /**
     * 持仓监控任务 - 每5秒执行一次
     * 监控模拟持仓，检查止损止盈
     */
    @Scheduled(fixedRate = 5000)
    public void monitorPaperPositions() {
        if (!paperTrading) {
            return;
        }
        
        // 等待启动数据加载完成
        if (!com.ltp.peter.augtrade.scheduler.StartupDataLoader.isDataLoaded()) {
            return;
        }
        
        try {
            // 检查是否有持仓
            if (!paperTradingService.hasOpenPosition()) {
                return;
            }
            
            // 记录更新前的持仓状态
            boolean hadPosition = paperTradingService.hasOpenPosition();
            
            // 🔥 优先使用币安获取价格
            BigDecimal currentPrice = null;
            int retryCount = 0;
            int maxRetries = 3;
            
            while (currentPrice == null && retryCount < maxRetries) {
                try {
                    // 优先币安
                    if (binanceEnabled && binanceFuturesService != null) {
                        currentPrice = binanceFuturesService.getCurrentPrice(binanceFuturesSymbol);
                    }
                    // Fallback到Bybit
                    else if (bybitEnabled && bybitTradingService != null) {
                        currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
                    }
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        // 关键：使用持仓的currentPrice作为fallback
                        com.ltp.peter.augtrade.entity.PaperPosition pos = paperTradingService.getCurrentPosition();
                        if (pos != null && pos.getCurrentPrice() != null) {
                            currentPrice = pos.getCurrentPrice();
                            log.warn("⚠️ 获取价格失败{}次，使用上次价格: ${}", maxRetries, currentPrice);
                        } else {
                            log.error("❌ 无法获取价格且无缓存，跳过本次监控");
                            return;
                        }
                    } else {
                        log.debug("🔄 获取价格失败，重试{}/{}", retryCount, maxRetries);
                        try {
                            Thread.sleep(500);  // 等待500ms重试
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
            
            // 更新持仓价格，自动检查止损止盈
            paperTradingService.updatePositions(currentPrice);
            
            // ✨ 关键修复：检测是否因止损/止盈而平仓
            boolean hasPositionNow = paperTradingService.hasOpenPosition();
            if (hadPosition && !hasPositionNow) {
                // 持仓已被平掉（止损或止盈），启动冷却期
                lastCloseTime = LocalDateTime.now();
                log.info("🔒 检测到止损/止盈平仓，启动{}秒冷却期，防止立即开新仓", CLOSE_COOLDOWN_SECONDS);
            }
            
            // 显示持仓状态
            com.ltp.peter.augtrade.entity.PaperPosition position = paperTradingService.getCurrentPosition();
            if (position != null) {
                log.debug("💼 持仓监控 - {} | 入场: ${} | 当前: ${} | 未实现盈亏: ${}", 
                        position.getSide(), 
                        position.getEntryPrice(), 
                        currentPrice, 
                        position.getUnrealizedPnL());
            }
            
        } catch (Exception e) {
            log.error("持仓监控失败", e);
        }
    }
    
    
    /**
     * 止盈止损检查任务 - 每10秒执行一次
     * Bybit模式下暂不使用（Bybit自动处理止损止盈）
     */
    @Scheduled(fixedRate = 10000)
    public void checkStopLoss() {
        // Bybit模式下，止损止盈由Bybit服务器端处理
        if (bybitEnabled && bybitTradingService.isEnabled()) {
            log.debug("Bybit模式 - 止损止盈由服务器端处理");
            return;
        }
        
        // 原始模式下检查止损
        try {
            executionService.checkAndExecuteStopLoss(binanceSymbol);
        } catch (Exception e) {
            log.error("止盈止损检查任务失败", e);
        }
    }
    
    /**
     * 风控统计任务 - 每30秒执行一次
     */
    @Scheduled(fixedRate = 30000)
    public void logRiskStatistics() {
        // 根据交易模式选择symbol
        String activeSymbol = (binanceEnabled && binanceFuturesService != null) ? binanceFuturesSymbol : 
                             (bybitEnabled && bybitTradingService.isEnabled()) ? bybitSymbol : binanceSymbol;
        
        try {
            // 🔥 如果是模拟交易模式，使用PaperTradingService的数据
            if (paperTrading) {
                // 获取未实现盈亏
                BigDecimal unrealizedPnl = BigDecimal.ZERO;
                if (paperTradingService.hasOpenPosition()) {
                    com.ltp.peter.augtrade.entity.PaperPosition position = paperTradingService.getCurrentPosition();
                    if (position != null) {
                        unrealizedPnl = position.getUnrealizedPnL();
                    }
                }
                
                // 获取今日盈亏
                double todayProfitLoss = paperTradingService.getTotalProfit();
                int totalTrades = paperTradingService.getTotalTrades();
                int winTrades = paperTradingService.getWinTrades();
                int lossTrades = paperTradingService.getLossTrades();
                
                log.info("📊 风控统计 - 未实现盈亏: ${}, 今日盈亏: ${} ({}单: {}盈/{}亏)", 
                        String.format("%.2f", unrealizedPnl), 
                        String.format("%.2f", todayProfitLoss),
                        totalTrades, winTrades, lossTrades);
            } else {
                // 实盘交易使用RiskManagementService
                String statistics = riskManagementService.getRiskStatistics(activeSymbol);
                log.info(statistics);
            }
        } catch (Exception e) {
            log.error("风控统计任务失败", e);
        }
    }
    
    
    /**
     * ML模型表现统计任务 - 每小时执行一次
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void mlStatistics() {
        try {
            String statistics = mlRecordService.getTodayStatistics();
            log.info(statistics);
        } catch (Exception e) {
            log.error("ML统计任务执行失败", e);
        }
    }
    
    /**
     * 模拟交易统计报告 - 每小时执行一次
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void paperTradingReport() {
        if (!paperTrading) {
            return;
        }
        
        int totalTrades = paperTradingService.getTotalTrades();
        if (totalTrades == 0) {
            return;
        }
        
        log.info("========================================");
        log.info("【模拟交易小时报告】");
        log.info("========================================");
        
        String stats = paperTradingService.getStatistics();
        log.info(stats);
        
        int winTrades = paperTradingService.getWinTrades();
        int lossTrades = paperTradingService.getLossTrades();
        double totalProfit = paperTradingService.getTotalProfit();
        double winRate = (double) winTrades / totalTrades * 100;
        
        // 判断策略是否可行
        if (totalTrades >= 20) {
            if (winRate >= 55 && totalProfit > 0) {
                log.info("✅ 策略表现良好！胜率{}%，累计盈利${}", String.format("%.1f", winRate), String.format("%.2f", totalProfit));
                log.info("💡 建议：可以考虑启用真实交易（先小资金测试）");
            } else if (winRate >= 50 && totalProfit > 0) {
                log.info("⚠️  策略表现一般，胜率{}%，建议继续观察", String.format("%.1f", winRate));
            } else {
                log.info("❌ 策略表现不佳，胜率{}%，亏损${}", String.format("%.1f", winRate), String.format("%.2f", Math.abs(totalProfit)));
                log.info("💡 建议：优化策略参数或更换策略");
            }
        } else {
            log.info("📊 样本数量较少（{}单），需要更多数据评估", totalTrades);
        }
        
        log.info("========================================");
    }
    
    /**
     * 健康检查任务 - 每分钟执行一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void healthCheck() {
        if (paperTrading) {
            log.info("系统健康检查 - 🎯 模拟交易模式运行中");
        } else {
            log.info("系统健康检查 - 💰 真实交易模式运行中");
        }
    }
    
    /**
     * ✨ 新增：定期发送持仓和统计报告到飞书
     * 每30分钟执行一次，实时关注持仓情况
     */
    @Scheduled(cron = "0 */5 * * * ?")  // 每30分钟执行一次（如 10:00, 10:30, 11:00...）
    public void sendPeriodicReport() {
        //如果是周6或者周日，不发送报告
        if (LocalDateTime.now().getDayOfWeek() == DayOfWeek.SATURDAY || LocalDateTime.now().getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }


        if (!paperTrading) {
            return; // 只在模拟交易模式下发送
        }
        
        try {
            log.info("========================================");
            log.info("【定期报告】开始生成飞书报告");
            
            // 1. 检查是否有持仓
            boolean hasPosition = paperTradingService.hasOpenPosition();
            String positionInfo = "";
            
            if (hasPosition) {
                com.ltp.peter.augtrade.entity.PaperPosition position = paperTradingService.getCurrentPosition();
                if (position != null) {
                    // 🔥 优先使用币安获取当前价格
                    BigDecimal currentPrice = null;
                    try {
                        if (binanceEnabled && binanceFuturesService != null) {
                            currentPrice = binanceFuturesService.getCurrentPrice(binanceFuturesSymbol);
                        } else if (bybitEnabled && bybitTradingService != null) {
                            currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
                        }
                    } catch (Exception e) {
                        currentPrice = position.getCurrentPrice();
                    }
                    
                    // 计算持仓时间
                    long holdingSeconds = Duration.between(position.getOpenTime(), LocalDateTime.now()).getSeconds();
                    long hours = holdingSeconds / 3600;
                    long minutes = (holdingSeconds % 3600) / 60;
                    String holdingTime = String.format("%d小时%d分钟", hours, minutes);
                    
                    // 构建持仓信息
                    String direction = "LONG".equals(position.getSide()) ? "🔥 做多" : "📉 做空";
                    String profitStatus = position.getUnrealizedPnL().compareTo(BigDecimal.ZERO) > 0 ? 
                            "💰 盈利" : "📉 亏损";
                    
                    positionInfo = String.format(
                        "**品种**: %s\n" +
                        "**方向**: %s\n" +
                        "**入场价**: $%s\n" +
                        "**当前价**: $%s\n" +
                        "**止损价**: $%s\n" +
                        "**止盈价**: $%s\n" +
                        "**数量**: %s 盎司\n" +
                        "**未实现盈亏**: %s $%s\n" +
                        "**持仓时长**: %s",
                        position.getSymbol(),
                        direction,
                        position.getEntryPrice().toPlainString(),
                        currentPrice.toPlainString(),
                        position.getStopLossPrice().toPlainString(),
                        position.getTakeProfitPrice().toPlainString(),
                        position.getQuantity().toPlainString(),
                        profitStatus,
                        position.getUnrealizedPnL().abs().toPlainString(),
                        holdingTime
                    );
                }
            }
            
            // 2. 获取今日统计
            int totalTrades = paperTradingService.getTotalTrades();
            int winTrades = paperTradingService.getWinTrades();
            int lossTrades = paperTradingService.getLossTrades();
            double totalProfit = paperTradingService.getTotalProfit();
            
            String todayStats;
            if (totalTrades == 0) {
                todayStats = "**今日交易**: 0笔\n" +
                             "**提示**: 暂无交易记录";
            } else {
                double winRate = (double) winTrades / totalTrades * 100;
                String winRateEmoji = winRate >= 55 ? "✅" : (winRate >= 45 ? "⚠️" : "❌");
                String profitEmoji = totalProfit > 0 ? "✅" : "❌";
                
                todayStats = String.format(
                    "**总交易**: %d笔\n" +
                    "**盈利**: %d笔 | **亏损**: %d笔\n" +
                    "**胜率**: %s %.1f%%\n" +
                    "**累计盈亏**: %s $%.2f\n" +
                    "**平均每笔**: $%.2f",
                    totalTrades,
                    winTrades,
                    lossTrades,
                    winRateEmoji,
                    winRate,
                    profitEmoji,
                    totalProfit,
                    totalProfit / totalTrades
                );
            }
            
            // 3. 发送飞书通知
            feishuNotificationService.notifyPositionAndStats(hasPosition, positionInfo, todayStats);
            
            log.info("✅ 定期报告已发送到飞书");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("❌ 定期报告生成失败", e);
        }
    }
    
    /**
     * ✅ 新增：计算动态持仓保护期
     * 根据盈利情况和信号强度动态调整最小持仓时间
     * 
     * @param unrealizedPnL 未实现盈亏
     * @param signal 当前交易信号
     * @return 最小持仓时间（秒）
     */
    private int calculateMinHoldingTime(BigDecimal unrealizedPnL, 
                                       com.ltp.peter.augtrade.strategy.signal.TradingSignal signal) {
        // 基础保护期
        int minTime = MIN_HOLDING_SECONDS_DEFAULT;
        
        // 根据盈利情况调整
        if (unrealizedPnL.compareTo(new BigDecimal(takeProfitDollars).multiply(new BigDecimal("0.5"))) > 0) {
            // 盈利达到50%止盈目标 - 延长保护期至15分钟
            minTime = MIN_HOLDING_SECONDS_BIG_PROFIT;
            log.debug("💎 盈利达标，延长保护期至{}秒", minTime);
        } else if (unrealizedPnL.compareTo(new BigDecimal(takeProfitDollars).multiply(new BigDecimal("0.2"))) > 0) {
            // 盈利达到20%止盈目标 - 延长保护期至10分钟
            minTime = MIN_HOLDING_SECONDS_PROFIT;
            log.debug("💰 盈利中，延长保护期至{}秒", minTime);
        } else if (unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.5"))) < 0) {
            // 亏损达到50%止损 - 缩短保护期至2分钟，快速止损
            minTime = 120;
            log.debug("📉 亏损较大，缩短保护期至{}秒，准备快速止损", minTime);
        } else if (unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.3"))) < 0) {
            // 亏损达到30%止损 - 缩短保护期至3分钟
            minTime = 180;
            log.debug("⚠️ 亏损中，缩短保护期至{}秒", minTime);
        }
        
        // 根据信号强度微调
        if (signal != null && signal.getStrength() > 70) {
            // 强信号反转，减少20%保护时间
            minTime = (int) (minTime * 0.8);
            log.debug("🔥 强反转信号（强度{}），缩短保护期至{}秒", signal.getStrength(), minTime);
        }
        
        return minTime;
    }
    
    /**
     * ✅ 新增：优化冷却期计算
     * 根据上次交易结果动态调整冷却期
     * 
     * @param lastProfitLoss 上次交易盈亏
     * @return 冷却期（秒）
     */
    private int calculateCooldownPeriod(BigDecimal lastProfitLoss) {
        if (lastProfitLoss == null) {
            return 60; // 默认1分钟
        }
        
        BigDecimal profitThreshold = new BigDecimal(takeProfitDollars).multiply(new BigDecimal("0.3"));
        BigDecimal lossThreshold = new BigDecimal(stopLossDollars).multiply(new BigDecimal("0.5"));
        
        if (lastProfitLoss.compareTo(profitThreshold) > 0) {
            // 大盈利后短冷却期（30秒），趋势可能延续
            log.debug("✅ 上次盈利${}，缩短冷却期至30秒", String.format("%.2f", lastProfitLoss));
            return 30;
        } else if (lastProfitLoss.compareTo(lossThreshold.negate()) < 0) {
            // 大亏损后长冷却期（5分钟），避免连续亏损
            log.debug("❌ 上次亏损${}，延长冷却期至300秒", String.format("%.2f", lastProfitLoss));
            return 300;
        } else {
            // 默认1分钟冷却
            return 60;
        }
    }
    
    /**
     * 🔥 P0修复：市场状态枚举
     */
    public enum MarketRegime {
        STRONG_TREND,    // 强趋势 (ADX > 30)
        WEAK_TREND,      // 弱趋势 (ADX 20-30)
        RANGING          // 震荡市 (ADX < 20)
    }
    
    /**
     * 🔥 P0修复：检测市场状态
     * 使用与StrategyOrchestrator一致的ADX计算方法
     */
    private MarketRegime detectMarketRegime(String symbol) {
        return detectMarketRegime(strategyOrchestrator.getMarketContext(symbol, 100));
    }

    private MarketRegime detectMarketRegime(com.ltp.peter.augtrade.strategy.core.MarketContext context) {
        try {
            if (context == null) {
                log.warn("⚠️ 无法获取市场上下文，默认为弱趋势");
                return MarketRegime.WEAK_TREND;
            }

            // 从context获取ADX（与StrategyOrchestrator使用同一份数据）
            Double adxValue = context.getIndicator("ADX");
            
            if (adxValue == null) {
                log.warn("⚠️ ADX计算失败，默认为弱趋势");
                return MarketRegime.WEAK_TREND;
            }
            
            if (adxValue > 30) {
                log.info("📊 市场状态: 强趋势 (ADX={} > 30)", String.format("%.1f", adxValue));
                return MarketRegime.STRONG_TREND;
            } else if (adxValue >= 15) {
                log.info("📊 市场状态: 弱趋势 (ADX={}, 15-30)", String.format("%.1f", adxValue));
                return MarketRegime.WEAK_TREND;
            } else {
                log.warn("📊 市场状态: 极弱震荡 (ADX={} < 15) ⚠️ 高风险市场", String.format("%.1f", adxValue));
                return MarketRegime.RANGING;
            }
            
        } catch (Exception e) {
            log.error("❌ 检测市场状态失败", e);
            return MarketRegime.WEAK_TREND;
        }
    }
    
    /**
     * 🔥 P3修复-20260213：转换策略工厂Signal为TradingSignal
     * 问题：原来硬编码strength=70，丢失了CompositeStrategy的真实信号强度
     * 修复：从StrategyOrchestrator获取真实信号（含强度、得分等完整信息）
     */
    private com.ltp.peter.augtrade.strategy.signal.TradingSignal convertFactorySignalToTradingSignal(
            TradingStrategyFactory.Signal signal, String symbol) {
        
        // 🔥 优先从StrategyOrchestrator获取完整信号（含真实强度）
        try {
            com.ltp.peter.augtrade.strategy.signal.TradingSignal realSignal = 
                    strategyOrchestrator.generateSignal(symbol);
            if (realSignal != null) {
                log.debug("📊 使用真实信号强度: {} (类型:{}, 得分:{})", 
                        realSignal.getStrength(), realSignal.getType(), realSignal.getScore());
                return realSignal;
            }
        } catch (Exception e) {
            log.warn("⚠️ 获取真实信号失败，使用策略工厂信号", e);
        }
        
        // Fallback: 使用策略工厂的简单信号
        com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType type;
        if (signal == TradingStrategyFactory.Signal.BUY) {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY;
        } else if (signal == TradingStrategyFactory.Signal.SELL) {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL;
        } else {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.HOLD;
        }
        
        return com.ltp.peter.augtrade.strategy.signal.TradingSignal.builder()
                .type(type)
                .strength(70)
                .score(70)
                .symbol(symbol)
                .strategyName(strategyFactory.getActiveStrategyName())
                .reason(strategyFactory.getStrategyDescription())
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    /**
     * 🔥 旧方法：转换SimplifiedTrendStrategy信号为TradingSignal（保留用于其他地方）
     */
    private com.ltp.peter.augtrade.strategy.signal.TradingSignal convertToTradingSignal(
            SimplifiedTrendStrategy.Signal signal, String symbol) {
        
        java.util.List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 50);
        int strength = 0;
        String explanation = "无交易信号";
        
        if (klines != null && klines.size() >= 50) {
            strength = simplifiedTrendStrategy.getSignalStrength(klines);
            explanation = simplifiedTrendStrategy.getSignalExplanation(klines, signal);
        }
        
        com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType type;
        if (signal == SimplifiedTrendStrategy.Signal.BUY) {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.BUY;
        } else if (signal == SimplifiedTrendStrategy.Signal.SELL) {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.SELL;
        } else {
            type = com.ltp.peter.augtrade.strategy.signal.TradingSignal.SignalType.HOLD;
        }
        
        // 使用Builder模式创建TradingSignal
        return com.ltp.peter.augtrade.strategy.signal.TradingSignal.builder()
                .type(type)
                .strength(strength)
                .score(strength)
                .symbol(symbol)
                .strategyName("SimplifiedTrend")
                .reason(explanation)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    /**
     * 🔥 修复：根据市场状态和信号类型计算所需信号强度
     * 细化ADX区间，降低门槛让策略能够正常开仓
     */
    private int calculateRequiredStrength(MarketRegime regime,
                                         com.ltp.peter.augtrade.strategy.signal.TradingSignal signal) {
        return calculateRequiredStrength(regime, signal, null);
    }

    private int calculateRequiredStrength(MarketRegime regime,
                                         com.ltp.peter.augtrade.strategy.signal.TradingSignal signal,
                                         com.ltp.peter.augtrade.strategy.core.MarketContext existingContext) {
        int baseStrength;

        // 获取精确的ADX值用于细分判断（优先复用已有context，避免重复拉数据）
        try {
            Double adxValue = existingContext != null ? existingContext.getIndicator("ADX") : null;
            
            if (adxValue != null) {
                // 🔥 细分ADX区间，提供更精细的门槛控制
                if (adxValue >= 30) {
                    // 强趋势 (ADX ≥ 30)
                    baseStrength = 20;
                    log.debug("💪 强趋势市场 (ADX={}), 门槛: {} 分", String.format("%.1f", adxValue), baseStrength);
                } else if (adxValue >= 25) {
                    // 中等偏强趋势 (ADX 25-30)
                    baseStrength = 22;
                    log.debug("📈 中等偏强趋势 (ADX={}), 门槛: {} 分", String.format("%.1f", adxValue), baseStrength);
                } else if (adxValue >= 20) {
                    // 中等趋势 (ADX 20-25) - 当前ADX=22.37匹配这里
                    baseStrength = 25;
                    log.debug("📊 中等趋势 (ADX={}), 门槛: {} 分", String.format("%.1f", adxValue), baseStrength);
                } else if (adxValue >= 15) {
                    // 弱趋势 (ADX 15-20)
                    baseStrength = 30;
                    log.debug("⚠️ 弱趋势 (ADX={}), 门槛: {} 分", String.format("%.1f", adxValue), baseStrength);
                } else {
                    // 极弱震荡 (ADX < 15)
                    baseStrength = 40;
                    log.warn("🚨 极弱震荡 (ADX={}), 高门槛: {} 分", String.format("%.1f", adxValue), baseStrength);
                }
            } else {
                // 无法获取ADX，使用regime判断
                baseStrength = getFallbackStrength(regime);
            }
        } catch (Exception e) {
            log.warn("⚠️ 获取ADX失败，使用备用逻辑", e);
            baseStrength = getFallbackStrength(regime);
        }
        
        return baseStrength;
    }
    
    /**
     * 🔥 P1修复-20260209：趋势反转检测
     * 检测EMA死叉（EMA20下穿EMA50），当检测到时禁止做多
     * 
     * @param symbol 交易品种
     * @return true=检测到趋势反转（死叉），false=趋势正常
     */
    private boolean detectTrendReversal(String symbol) {
        try {
            java.util.List<Kline> klines = marketDataService.getLatestKlines(symbol, "5m", 60);
            if (klines == null || klines.size() < 55) {
                return false; // 数据不足，不阻止交易
            }
            
            com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend trend = 
                    emaCalculator.calculateTrend(klines, 20, 50);
            
            if (trend == null) {
                return false;
            }
            
            // 检测死叉：EMA20 < EMA50（下降趋势）
            if (trend.isDownTrend()) {
                // 🔥 P3修复-20260213: 增加Supertrend交叉验证
                // 问题：单独依赖EMA死叉导致错过39分超强信号
                // 修复：如果Supertrend仍然看涨，说明短期下穿可能是假信号
                try {
                    com.ltp.peter.augtrade.strategy.core.MarketContext ctx = 
                            strategyOrchestrator.getMarketContext(symbol, 100);
                    if (ctx != null) {
                        com.ltp.peter.augtrade.indicator.SupertrendCalculator.SupertrendResult st = 
                                ctx.getIndicator("Supertrend");
                        if (st != null && st.isUpTrend()) {
                            log.warn("⚠️ EMA死叉但Supertrend仍看涨(ST={})，可能是假死叉，允许做多", 
                                    String.format("%.2f", st.getSupertrendValue()));
                            return false; // Supertrend否决EMA死叉
                        }
                    }
                } catch (Exception e) {
                    log.debug("Supertrend验证失败，使用EMA判断");
                }
                
                log.warn("🚨 EMA死叉+Supertrend确认！EMA20={} < EMA50={}，趋势反转为下降", 
                        String.format("%.2f", trend.getEmaShort()), 
                        String.format("%.2f", trend.getEmaLong()));
                return true;
            }
            
            // 🔥 P3修复-20260213: 移除"即将死叉"阻止逻辑
            // 原问题：EMA20=4954.12 > EMA50=4950.14（仍是金叉），仅因差距0.08%就禁止做多
            //         导致6个策略一致同意的39分超强BUY信号被拦截
            // 修复：EMA20>EMA50时不应阻止做多，只记录警告
            double emaGap = (trend.getEmaShort() - trend.getEmaLong()) / trend.getEmaLong() * 100;
            if (emaGap > 0 && emaGap < 0.1) {
                log.warn("⚠️ EMA间距较窄（{}%），趋势可能减弱，但仍为金叉，不阻止做多", String.format("%.4f", emaGap));
                // 🔥 不再返回true！金叉状态下不应阻止做多
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("趋势反转检测失败", e);
            return false; // 异常时不阻止交易
        }
    }
    
    /**
     * 🔥 备用逻辑：当无法获取ADX时使用regime判断
     */
    private int getFallbackStrength(MarketRegime regime) {
        switch (regime) {
            case STRONG_TREND:
                return 20;
            case WEAK_TREND:
                return 25;  // 从30降低到25
            case RANGING:
                return 40;
            default:
                return 25;
        }
    }
}
