package com.ltp.peter.augtrade.task;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

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
    private AdvancedTradingStrategyService advancedStrategyService;
    
    @Autowired
    private TradeExecutionService executionService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private MLRecordService mlRecordService;
    
    @Autowired
    private BybitTradingService bybitTradingService;
    
    @Autowired
    private com.ltp.peter.augtrade.service.core.strategy.StrategyOrchestrator strategyOrchestrator;
    
    @Autowired
    private PaperTradingService paperTradingService;
    
    @Autowired
    private com.ltp.peter.augtrade.mapper.TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private com.ltp.peter.augtrade.service.core.indicator.ATRCalculator atrCalculator;
    
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
    
    // 信号反转冷却期控制
    private LocalDateTime lastReversalTime = null;
    private static final int REVERSAL_COOLDOWN_SECONDS = 300; // 300秒冷却（5分钟）- 减少频繁反向交易
    
    // 持仓时间管理常量
    private static final int MAX_HOLDING_SECONDS = 1800; // 最大持仓30分钟
    private static final int MIN_HOLDING_SECONDS_DEFAULT = 300; // 默认5分钟
    private static final int MIN_HOLDING_SECONDS_PROFIT = 600; // 盈利时10分钟
    private static final int MIN_HOLDING_SECONDS_BIG_PROFIT = 900; // 大盈利时15分钟
    
    /**
     * Bybit数据采集任务 - 每60秒执行一次
     * 仅在Bybit启用时采集黄金K线数据
     */
    @Scheduled(fixedRate = 60000)
    public void collectMarketData() {
        // 如果启用Bybit，采集Bybit黄金数据
        if (bybitEnabled && bybitTradingService.isEnabled()) {
            collectBybitData();
        }
        // 否则不采集数据（避免无用的BTC数据）
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
                kline.setTimestamp(LocalDateTime.now());
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
     * 策略执行任务 - 每10秒执行一次
     * 使用Bybit交易黄金（XAUUSDT）
     */
    @Scheduled(fixedRate = 10000)
    public void executeStrategy() {
        if (!strategyEnabled) {
            return;
        }
        
        // 如果启用Bybit，使用Bybit交易
        if (bybitEnabled && bybitTradingService.isEnabled()) {
            executeBybitStrategy();
        } else {
            // 否则使用原来的方式
            executeOriginalStrategy();
        }
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
            
            // 2. 使用新架构的策略编排器（多策略投票）
            com.ltp.peter.augtrade.service.core.signal.TradingSignal tradingSignal = 
                    strategyOrchestrator.generateSignal(bybitSymbol);
            
            log.info("📊 新架构信号: {} (强度: {}, 得分: {}) - {}", 
                    tradingSignal.getType(), tradingSignal.getStrength(), 
                    tradingSignal.getScore(), tradingSignal.getReason());
            
            // 3. 检查冷却期
            if (lastReversalTime != null) {
                long secondsSinceReversal = Duration.between(lastReversalTime, LocalDateTime.now()).getSeconds();
                if (secondsSinceReversal < REVERSAL_COOLDOWN_SECONDS) {
                    log.info("⏸️ 冷却期中（剩余{}秒），暂不开新仓", REVERSAL_COOLDOWN_SECONDS - secondsSinceReversal);
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
                        lastReversalTime = LocalDateTime.now();
                        log.info("========================================");
                        return;
                    } else if (unrealizedPnL.compareTo(new BigDecimal(stopLossDollars).multiply(new BigDecimal("-0.5"))) < 0) {
                        log.warn("⏰ 超过最大持仓时间{}分钟且亏损${}，强制平仓止损", 
                                MAX_HOLDING_SECONDS / 60, unrealizedPnL);
                        paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                        lastReversalTime = LocalDateTime.now();
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
                
                // 持有多头，出现做空信号 → 平仓（增强版：盈利保护 + 信号强度检查）
                if (currentPosition.getSide().equals("LONG") && 
                    tradingSignal.getType() == com.ltp.peter.augtrade.service.core.signal.TradingSignal.SignalType.SELL) {
                    
                    // ✨ 盈利保护：盈利时不反转平仓
                    if (unrealizedPnL.compareTo(BigDecimal.ZERO) >= 0) {
                        log.info("💰 持仓盈利${}，忽略反转信号，让利润奔跑", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // ✨ 信号强度检查：只在强信号时反转
                    if (tradingSignal.getStrength() < 75) {
                        log.info("⚠️ 反转信号强度{}不足（需要≥75），持仓亏损${}但不平仓", 
                                tradingSignal.getStrength(), unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 只有亏损且强信号才反转
                    log.warn("⚠️ 信号反转！持有多头但出现强做空信号（强度{}），持仓亏损${}，持仓{}秒后平仓", 
                             tradingSignal.getStrength(), unrealizedPnL, holdingSeconds);
                    paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                    lastReversalTime = LocalDateTime.now();
                    log.info("🔒 启动{}秒冷却期，防止频繁交易", REVERSAL_COOLDOWN_SECONDS);
                    log.info("========================================");
                    return;
                }
                
                // 持有空头，出现做多信号 → 平仓（增强版：盈利保护 + 信号强度检查）
                if (currentPosition.getSide().equals("SHORT") && 
                    tradingSignal.getType() == com.ltp.peter.augtrade.service.core.signal.TradingSignal.SignalType.BUY) {
                    
                    // ✨ 盈利保护：盈利时不反转平仓
                    if (unrealizedPnL.compareTo(BigDecimal.ZERO) >= 0) {
                        log.info("💰 持仓盈利${}，忽略反转信号，让利润奔跑", unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // ✨ 信号强度检查：只在强信号时反转
                    if (tradingSignal.getStrength() < 75) {
                        log.info("⚠️ 反转信号强度{}不足（需要≥75），持仓亏损${}但不平仓", 
                                tradingSignal.getStrength(), unrealizedPnL);
                        log.info("========================================");
                        return;
                    }
                    
                    // 只有亏损且强信号才反转
                    log.warn("⚠️ 信号反转！持有空头但出现强做多信号（强度{}），持仓亏损${}，持仓{}秒后平仓", 
                             tradingSignal.getStrength(), unrealizedPnL, holdingSeconds);
                    paperTradingService.closePositionBySignalReversal(currentPosition, currentPrice);
                    lastReversalTime = LocalDateTime.now();
                    log.info("🔒 启动{}秒冷却期，防止频繁交易", REVERSAL_COOLDOWN_SECONDS);
                    log.info("========================================");
                    return;
                }
            }
            
            // 5. 根据信号执行交易
            if (tradingSignal.getType() == com.ltp.peter.augtrade.service.core.signal.TradingSignal.SignalType.BUY) {
                log.info("🔥 收到做多信号！准备做多黄金");
                executeBybitBuy(currentPrice);
            } else if (tradingSignal.getType() == com.ltp.peter.augtrade.service.core.signal.TradingSignal.SignalType.SELL) {
                log.info("📉 收到做空信号！准备做空黄金");
                executeBybitSell(currentPrice);
            } else {
                log.debug("⏸️ 保持观望，等待高质量信号");
            }
            
            log.info("策略执行完成");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("Bybit策略执行失败", e);
        }
    }
    
    /**
     * 通过Bybit做多黄金
     */
    private void executeBybitBuy(BigDecimal currentPrice) {
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
                        return;
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
                paperTradingService.openPosition(
                        bybitSymbol,
                        "LONG",
                        currentPrice,
                        new BigDecimal(bybitMinQty),
                        stopLoss,
                        takeProfit,
                        "AggressiveML"
                );
                
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
            }
                    
        } catch (Exception e) {
            log.error("❌ Bybit做多失败", e);
        }
    }
    
    /**
     * 通过Bybit做空黄金
     */
    private void executeBybitSell(BigDecimal currentPrice) {
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
                        return;
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
                paperTradingService.openPosition(
                        bybitSymbol,
                        "SHORT",
                        currentPrice,
                        new BigDecimal(bybitMinQty),
                        stopLoss,
                        takeProfit,
                        "AggressiveML"
                );
                
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
            }
                    
        } catch (Exception e) {
            log.error("❌ Bybit做空失败", e);
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
        
        try {
            // 检查是否有持仓
            if (!paperTradingService.hasOpenPosition()) {
                return;
            }
            
            // ✨ 修复：增强网络异常处理，确保止损止盈检查必定执行
            BigDecimal currentPrice = null;
            int retryCount = 0;
            int maxRetries = 3;
            
            while (currentPrice == null && retryCount < maxRetries) {
                try {
                    currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
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
     * 原始策略（BTC/币安）
     */
    private void executeOriginalStrategy() {
        try {
            log.info("========================================");
            log.info("【AI增强短线策略】开始执行 - 交易品种: {}", binanceSymbol);
            
            AdvancedTradingStrategyService.Signal signal = advancedStrategyService.mlEnhancedWilliamsStrategy(binanceSymbol);
            
            if (lastReversalTime != null) {
                long secondsSinceReversal = Duration.between(lastReversalTime, LocalDateTime.now()).getSeconds();
                if (secondsSinceReversal < REVERSAL_COOLDOWN_SECONDS) {
                    log.info("⏸️ 信号反转冷却期（{}秒），暂不开新仓", REVERSAL_COOLDOWN_SECONDS - secondsSinceReversal);
                    log.info("========================================");
                    return;
                }
            }
            
            switch (signal) {
                case BUY:
                    log.info("🔥 收到做多信号！");
                    executionService.executeBuy(binanceSymbol, minTradeAmount, "BTC短线策略-做多");
                    break;
                case SELL:
                    log.info("📉 收到做空信号！");
                    executionService.executeSellShort(binanceSymbol, minTradeAmount, "BTC短线策略-做空");
                    break;
                case HOLD:
                    log.debug("⏸️ 保持观望");
                    break;
            }
            
            log.info("策略执行完成");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("策略执行任务失败", e);
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
        String activeSymbol = (bybitEnabled && bybitTradingService.isEnabled()) ? bybitSymbol : binanceSymbol;
        
        try {
            String statistics = riskManagementService.getRiskStatistics(activeSymbol);
            log.info(statistics);
        } catch (Exception e) {
            log.error("风控统计任务失败", e);
        }
    }
    
    /**
     * 信号反转止损检查
     * 如果当前持仓方向与新信号相反，立即平仓止损
     */
    private void checkSignalReversal(String symbol, AdvancedTradingStrategyService.Signal signal) {
        if (signal == AdvancedTradingStrategyService.Signal.HOLD) {
            return; // 观望信号不处理
        }
        
        try {
            // 检查是否有持仓
            com.ltp.peter.augtrade.entity.Position longPosition = executionService.getOpenPosition(symbol, "LONG");
            com.ltp.peter.augtrade.entity.Position shortPosition = executionService.getOpenPosition(symbol, "SHORT");
            
            // 持有多头，但出现做空信号 → 立即平掉多头
            if (longPosition != null && signal == AdvancedTradingStrategyService.Signal.SELL) {
                log.warn("⚠️ 信号反转！持有多头但出现做空信号，立即平仓止损");
                executionService.executeSell(binanceSymbol, longPosition.getQuantity(), "信号反转止损");
                lastReversalTime = LocalDateTime.now(); // 记录反转时间
                log.info("🔒 启动30秒冷却期，防止立即开反向仓位");
                return;
            }
            
            // 持有空头，但出现做多信号 → 立即平掉空头
            if (shortPosition != null && signal == AdvancedTradingStrategyService.Signal.BUY) {
                log.warn("⚠️ 信号反转！持有空头但出现做多信号，立即平仓止损");
                executionService.executeBuyToCover(binanceSymbol, shortPosition.getQuantity(), "信号反转止损");
                lastReversalTime = LocalDateTime.now(); // 记录反转时间
                log.info("🔒 启动30秒冷却期，防止立即开反向仓位");
                return;
            }
        } catch (Exception e) {
            log.error("信号反转检查失败", e);
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
                log.info("⚠️  策略表现一般，胜率{:.1f}%，建议继续观察", winRate);
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
     * ✅ 新增：计算动态持仓保护期
     * 根据盈利情况和信号强度动态调整最小持仓时间
     * 
     * @param unrealizedPnL 未实现盈亏
     * @param signal 当前交易信号
     * @return 最小持仓时间（秒）
     */
    private int calculateMinHoldingTime(BigDecimal unrealizedPnL, 
                                       com.ltp.peter.augtrade.service.core.signal.TradingSignal signal) {
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
}
