package com.ltp.peter.augtrade.backtest;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ltp.peter.augtrade.entity.BacktestResult;
import com.ltp.peter.augtrade.entity.BacktestTrade;
import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.IndicatorService;
import com.ltp.peter.augtrade.indicator.MACDCalculator;
import com.ltp.peter.augtrade.indicator.MACDResult;
import com.ltp.peter.augtrade.mapper.BacktestResultMapper;
import com.ltp.peter.augtrade.mapper.BacktestTradeMapper;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 回测服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class BacktestService {
    
    @Autowired
    private BacktestResultMapper backtestResultMapper;
    
    @Autowired
    private BacktestTradeMapper backtestTradeMapper;
    
    @Autowired
    private KlineMapper klineMapper;
    
    @Autowired
    private IndicatorService indicatorService;
    
    @Autowired
    private MACDCalculator macdCalculator;
    
    @Autowired
    private com.ltp.peter.augtrade.indicator.ADXCalculator adxCalculator;
    
    @Autowired
    private com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator strategyOrchestrator;
    
    @Autowired
    private com.ltp.peter.augtrade.strategy.core.CompositeStrategy compositeStrategy;
    
    // 手续费率 (0.05% * 0.7 = 0.035%, VIP折扣)
    private static final BigDecimal FEE_RATE = new BigDecimal("0.00035");
    
    /**
     * 执行回测
     * 
     * @param symbol 交易对
     * @param interval K线周期
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param initialCapital 初始资金
     * @param strategyName 策略名称
     * @return 回测结果ID
     */
    @Transactional(rollbackFor = Exception.class)
    public String executeBacktest(String symbol, String interval, LocalDateTime startTime, 
                                   LocalDateTime endTime, BigDecimal initialCapital, String strategyName) {
        log.info("开始回测 - 交易对: {}, 周期: {}, 时间范围: {} ~ {}, 初始资金: {}, 策略: {}", 
                symbol, interval, startTime, endTime, initialCapital, strategyName);
        
        String backtestId = UUID.randomUUID().toString();
        
        try {
            // 获取历史K线数据
            QueryWrapper<Kline> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("symbol", symbol)
                       .eq("`interval`", interval)
                       .between("timestamp", startTime, endTime)
                       .orderByAsc("timestamp");
            List<Kline> klines = klineMapper.selectList(queryWrapper);
            
            if (klines == null || klines.size() < 50) {
                log.error("K线数据不足，无法进行回测");
                throw new RuntimeException("K线数据不足，至少需要50条数据");
            }
            
            log.info("加载了 {} 条K线数据", klines.size());
            
            // 先创建并保存回测结果记录（创建外键），状态设为RUNNING
            BacktestResult result = new BacktestResult();
            result.setBacktestId(backtestId);
            result.setSymbol(symbol);
            result.setInterval(interval);
            result.setStrategyName(strategyName);
            result.setStartTime(startTime);
            result.setEndTime(endTime);
            result.setInitialCapital(initialCapital);
            result.setStatus("RUNNING");
            result.setCreateTime(LocalDateTime.now());
            result.setUpdateTime(LocalDateTime.now());
            backtestResultMapper.insert(result);
            
            // 执行回测策略（此时可以安全地保存交易记录，因为父记录已存在）
            BacktestResult strategyResult = null;
            switch (strategyName) {
                case "SHORT_TERM":
                    strategyResult = executeShortTermBacktest(backtestId, symbol, interval, klines, initialCapital);
                    break;
                case "BREAKOUT":
                    strategyResult = executeBreakoutBacktest(backtestId, symbol, interval, klines, initialCapital);
                    break;
                case "RSI":
                    strategyResult = executeRSIBacktest(backtestId, symbol, interval, klines, initialCapital);
                    break;
                case "COMPOSITE":
                    strategyResult = executeCompositeBacktest(backtestId, symbol, interval, klines, initialCapital);
                    break;
                default:
                    log.error("未知的策略: {}", strategyName);
                    throw new RuntimeException("未知的策略: " + strategyName);
            }
            
            // 更新回测结果（保留初始记录的ID）
            strategyResult.setId(result.getId());  // 使用初始记录的主键ID
            strategyResult.setBacktestId(backtestId);
            strategyResult.setStrategyName(strategyName);
            strategyResult.setStartTime(startTime);
            strategyResult.setEndTime(endTime);
            strategyResult.setStatus("COMPLETED");
            strategyResult.setCreateTime(result.getCreateTime());  // 保留创建时间
            strategyResult.setUpdateTime(LocalDateTime.now());
            backtestResultMapper.updateById(strategyResult);
            
            log.info("回测完成 - 回测ID: {}, 总收益: {}, 收益率: {}%, 胜率: {}%", 
                    backtestId, result.getTotalProfit(), result.getReturnRate(), result.getWinRate());
            
            return backtestId;
            
        } catch (Exception e) {
            log.error("回测执行失败", e);
            // 保存失败记录
            BacktestResult failedResult = new BacktestResult();
            failedResult.setBacktestId(backtestId);
            failedResult.setSymbol(symbol);
            failedResult.setInterval(interval);
            failedResult.setStrategyName(strategyName);
            failedResult.setStartTime(startTime);
            failedResult.setEndTime(endTime);
            failedResult.setInitialCapital(initialCapital);
            failedResult.setStatus("FAILED");
            failedResult.setRemark(e.getMessage());
            failedResult.setCreateTime(LocalDateTime.now());
            backtestResultMapper.insert(failedResult);
            throw e;
        }
    }
    
    /**
     * 执行短线策略回测
     */
    private BacktestResult executeShortTermBacktest(String backtestId, String symbol, String interval,
                                                     List<Kline> klines, BigDecimal initialCapital) {
        log.info("执行短线策略回测");
        
        BigDecimal capital = initialCapital;
        BigDecimal maxCapital = initialCapital;
        List<BacktestTrade> trades = new ArrayList<>();
        
        // 持仓信息
        BacktestTrade openPosition = null;
        
        // 遍历K线数据进行模拟交易
        for (int i = 50; i < klines.size(); i++) {
            List<Kline> historicalData = klines.subList(0, i + 1);
            Kline currentKline = klines.get(i);
            BigDecimal currentPrice = currentKline.getClosePrice();
            
            // 如果有持仓，检查止盈止损
            if (openPosition != null) {
                boolean shouldExit = false;
                String exitReason = "";
                
                // 检查止盈
                if ("BUY".equals(openPosition.getSide()) && 
                    currentPrice.compareTo(openPosition.getTakeProfitPrice()) >= 0) {
                    shouldExit = true;
                    exitReason = "TAKE_PROFIT";
                } else if ("SELL".equals(openPosition.getSide()) && 
                          currentPrice.compareTo(openPosition.getTakeProfitPrice()) <= 0) {
                    shouldExit = true;
                    exitReason = "TAKE_PROFIT";
                }
                
                // 检查止损
                if ("BUY".equals(openPosition.getSide()) && 
                    currentPrice.compareTo(openPosition.getStopLossPrice()) <= 0) {
                    shouldExit = true;
                    exitReason = "STOP_LOSS";
                } else if ("SELL".equals(openPosition.getSide()) && 
                          currentPrice.compareTo(openPosition.getStopLossPrice()) >= 0) {
                    shouldExit = true;
                    exitReason = "STOP_LOSS";
                }
                
                // 平仓
                if (shouldExit) {
                    openPosition = closePosition(openPosition, currentPrice, currentKline.getTimestamp(), 
                                                 exitReason, capital);
                    capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
                    trades.add(openPosition);
                    
                    if (capital.compareTo(maxCapital) > 0) {
                        maxCapital = capital;
                    }
                    
                    openPosition = null;
                }
            }
            
            // 如果没有持仓，检查交易信号
            if (openPosition == null && i >= 100) {
                String signal = evaluateShortTermSignal(historicalData);
                
                if ("BUY".equals(signal) || "SELL".equals(signal)) {
                    openPosition = openPosition(backtestId, symbol, signal, currentPrice, 
                                               currentKline.getTimestamp(), capital);
                }
            }
        }
        
        // 如果回测结束时还有持仓，按最后价格平仓
        if (openPosition != null) {
            Kline lastKline = klines.get(klines.size() - 1);
            openPosition = closePosition(openPosition, lastKline.getClosePrice(), 
                                        lastKline.getTimestamp(), "END_OF_BACKTEST", capital);
            capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
            trades.add(openPosition);
        }
        
        // 保存交易记录
        for (BacktestTrade trade : trades) {
            trade.setCreateTime(LocalDateTime.now());
            backtestTradeMapper.insert(trade);
        }
        
        // 计算回测结果
        return calculateBacktestResult(symbol, interval, initialCapital, capital, maxCapital, trades);
    }
    
    /**
     * 执行突破策略回测
     */
    private BacktestResult executeBreakoutBacktest(String backtestId, String symbol, String interval,
                                                    List<Kline> klines, BigDecimal initialCapital) {
        log.info("执行突破策略回测");
        
        BigDecimal capital = initialCapital;
        BigDecimal maxCapital = initialCapital;
        List<BacktestTrade> trades = new ArrayList<>();
        BacktestTrade openPosition = null;
        
        for (int i = 50; i < klines.size(); i++) {
            List<Kline> historicalData = klines.subList(0, i + 1);
            Kline currentKline = klines.get(i);
            BigDecimal currentPrice = currentKline.getClosePrice();
            
            // 检查止盈止损
            if (openPosition != null) {
                boolean shouldExit = false;
                String exitReason = "";
                
                if ("BUY".equals(openPosition.getSide()) && 
                    currentPrice.compareTo(openPosition.getTakeProfitPrice()) >= 0) {
                    shouldExit = true;
                    exitReason = "TAKE_PROFIT";
                } else if ("BUY".equals(openPosition.getSide()) && 
                          currentPrice.compareTo(openPosition.getStopLossPrice()) <= 0) {
                    shouldExit = true;
                    exitReason = "STOP_LOSS";
                }
                
                if (shouldExit) {
                    openPosition = closePosition(openPosition, currentPrice, currentKline.getTimestamp(), 
                                                 exitReason, capital);
                    capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
                    trades.add(openPosition);
                    
                    if (capital.compareTo(maxCapital) > 0) {
                        maxCapital = capital;
                    }
                    
                    openPosition = null;
                }
            }
            
            // 检查突破信号
            if (openPosition == null && historicalData.size() >= 20) {
                String signal = evaluateBreakoutSignal(historicalData);
                
                if ("BUY".equals(signal)) {
                    openPosition = openPosition(backtestId, symbol, signal, currentPrice, 
                                               currentKline.getTimestamp(), capital);
                }
            }
        }
        
        // 处理剩余持仓
        if (openPosition != null) {
            Kline lastKline = klines.get(klines.size() - 1);
            openPosition = closePosition(openPosition, lastKline.getClosePrice(), 
                                        lastKline.getTimestamp(), "END_OF_BACKTEST", capital);
            capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
            trades.add(openPosition);
        }
        
        // 保存交易记录
        for (BacktestTrade trade : trades) {
            trade.setCreateTime(LocalDateTime.now());
            backtestTradeMapper.insert(trade);
        }
        
        return calculateBacktestResult(symbol, interval, initialCapital, capital, maxCapital, trades);
    }
    
    /**
     * 执行RSI策略回测
     */
    private BacktestResult executeRSIBacktest(String backtestId, String symbol, String interval,
                                              List<Kline> klines, BigDecimal initialCapital) {
        log.info("执行RSI策略回测");
        
        BigDecimal capital = initialCapital;
        BigDecimal maxCapital = initialCapital;
        List<BacktestTrade> trades = new ArrayList<>();
        BacktestTrade openPosition = null;
        
        for (int i = 50; i < klines.size(); i++) {
            List<Kline> historicalData = klines.subList(0, i + 1);
            Kline currentKline = klines.get(i);
            BigDecimal currentPrice = currentKline.getClosePrice();
            
            // 检查止盈止损
            if (openPosition != null) {
                boolean shouldExit = false;
                String exitReason = "";
                
                if ("BUY".equals(openPosition.getSide())) {
                    if (currentPrice.compareTo(openPosition.getTakeProfitPrice()) >= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    } else if (currentPrice.compareTo(openPosition.getStopLossPrice()) <= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    }
                } else if ("SELL".equals(openPosition.getSide())) {
                    if (currentPrice.compareTo(openPosition.getTakeProfitPrice()) <= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    } else if (currentPrice.compareTo(openPosition.getStopLossPrice()) >= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    }
                }
                
                if (shouldExit) {
                    openPosition = closePosition(openPosition, currentPrice, currentKline.getTimestamp(), 
                                                 exitReason, capital);
                    capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
                    trades.add(openPosition);
                    
                    if (capital.compareTo(maxCapital) > 0) {
                        maxCapital = capital;
                    }
                    
                    openPosition = null;
                }
            }
            
            // 检查RSI信号
            if (openPosition == null && historicalData.size() >= 14) {
                String signal = evaluateRSISignal(historicalData);
                
                if ("BUY".equals(signal) || "SELL".equals(signal)) {
                    openPosition = openPosition(backtestId, symbol, signal, currentPrice, 
                                               currentKline.getTimestamp(), capital);
                }
            }
        }
        
        // 处理剩余持仓
        if (openPosition != null) {
            Kline lastKline = klines.get(klines.size() - 1);
            openPosition = closePosition(openPosition, lastKline.getClosePrice(), 
                                        lastKline.getTimestamp(), "END_OF_BACKTEST", capital);
            capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
            trades.add(openPosition);
        }
        
        // 保存交易记录
        for (BacktestTrade trade : trades) {
            trade.setCreateTime(LocalDateTime.now());
            backtestTradeMapper.insert(trade);
        }
        
        return calculateBacktestResult(symbol, interval, initialCapital, capital, maxCapital, trades);
    }
    
    /**
     * 执行组合策略回测（使用StrategyOrchestrator）
     * 注意：不在此方法内保存交易记录，由主方法统一保存以避免外键约束问题
     */
    private BacktestResult executeCompositeBacktest(String backtestId, String symbol, String interval,
                                                     List<Kline> klines, BigDecimal initialCapital) {
        log.info("执行组合策略回测（多策略投票系统）- 信号强度阈值70");
        
        BigDecimal capital = initialCapital;
        BigDecimal maxCapital = initialCapital;
        List<BacktestTrade> trades = new ArrayList<>();
        BacktestTrade openPosition = null;
        
        // 配置止损止盈参数（与实盘配置一致）
        int stopLossDollars = 15;      // ✅ 每盎司$15，总止损$150
        int takeProfitDollars = 45;    // ✅ 每盎司$45，总止盈$450（盈亏比1:3）
        
        // 🔥 P0修复：添加冷却期机制
        int cooldownUntilIndex = -1;  // 冷却期结束的索引
        int intervalSeconds = getIntervalMinutes(interval) * 60;  // K线间隔秒数
        
        for (int i = 100; i < klines.size(); i++) {  // 需要至少100根K线
            Kline currentKline = klines.get(i);
            BigDecimal currentPrice = currentKline.getClosePrice();
            
            // 🔥 P0修复：检查最大持仓时间（30分钟强制平仓）
            if (openPosition != null) {
                Duration holdingDuration = Duration.between(
                    openPosition.getEntryTime(), 
                    currentKline.getTimestamp()
                );
                
                // 🔥 紧急修复：移除30分钟强制平仓（导致交易次数暴增16倍，手续费$197k！）
                // 保留原始的止损止盈机制，让策略自然平仓
                // 如果需要限制持仓时间，应该提高到2-4小时，而不是30分钟
                /*
                if (holdingDuration.toMinutes() >= 30) {
                    log.warn("⏰ 持仓超过30分钟，强制平仓");
                    openPosition = closePosition(openPosition, currentPrice, currentKline.getTimestamp(), 
                                                 "MAX_HOLDING_TIME", capital);
                    capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
                    trades.add(openPosition);
                    
                    if (capital.compareTo(maxCapital) > 0) {
                        maxCapital = capital;
                    }
                    
                    cooldownUntilIndex = i + (300 / intervalSeconds);
                    openPosition = null;
                    continue;
                }
                */
                
                // 检查止盈止损
                boolean shouldExit = false;
                String exitReason = "";
                
                if ("BUY".equals(openPosition.getSide())) {
                    if (currentPrice.compareTo(openPosition.getTakeProfitPrice()) >= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    } else if (currentPrice.compareTo(openPosition.getStopLossPrice()) <= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    }
                } else if ("SELL".equals(openPosition.getSide())) {
                    if (currentPrice.compareTo(openPosition.getTakeProfitPrice()) <= 0) {
                        shouldExit = true;
                        exitReason = "TAKE_PROFIT";
                    } else if (currentPrice.compareTo(openPosition.getStopLossPrice()) >= 0) {
                        shouldExit = true;
                        exitReason = "STOP_LOSS";
                    }
                }
                
                if (shouldExit) {
                    openPosition = closePosition(openPosition, currentPrice, currentKline.getTimestamp(), 
                                                 exitReason, capital);
                    capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
                    trades.add(openPosition);
                    
                    if (capital.compareTo(maxCapital) > 0) {
                        maxCapital = capital;
                    }
                    
                    // 移除冷却期（影响盈利机会）
                    // cooldownUntilIndex = i + (300 / intervalSeconds);
                    
                    openPosition = null;
                }
            }
            
            // 移除冷却期检查（让策略能立即抓住新机会）
            /*
            if (openPosition == null && i <= cooldownUntilIndex) {
                log.debug("🚫 冷却期中，跳过信号检查", i, cooldownUntilIndex);
                continue;
            }
            */
            
            // 检查组合策略信号
            if (openPosition == null) {
                String signal = evaluateCompositeSignal(symbol, klines, i);
                
                if ("BUY".equals(signal) || "SELL".equals(signal)) {
                    openPosition = openPositionWithDollarStopLoss(backtestId, symbol, signal, currentPrice, 
                                               currentKline.getTimestamp(), capital, stopLossDollars, takeProfitDollars);
                    log.info("✅ 开仓 - 方向: {}, 价格: {}, 止损: {}, 止盈: {}", 
                            signal, currentPrice, openPosition.getStopLossPrice(), openPosition.getTakeProfitPrice());
                }
            }
        }
        
        // 处理剩余持仓
        if (openPosition != null) {
            Kline lastKline = klines.get(klines.size() - 1);
            openPosition = closePosition(openPosition, lastKline.getClosePrice(), 
                                        lastKline.getTimestamp(), "END_OF_BACKTEST", capital);
            capital = capital.add(openPosition.getProfitLoss()).subtract(openPosition.getFee());
            trades.add(openPosition);
        }
        
        // 保存交易记录（现在可以安全保存，因为父记录已在主方法中创建）
        for (BacktestTrade trade : trades) {
            trade.setCreateTime(LocalDateTime.now());
            backtestTradeMapper.insert(trade);
        }
        
        // 计算并返回回测结果
        return calculateBacktestResult(symbol, interval, initialCapital, capital, maxCapital, trades);
    }
    
    /**
     * 评估组合策略信号（使用StrategyOrchestrator）
     */
    private String evaluateCompositeSignal(String symbol, List<Kline> allKlines, int currentIndex) {
        try {
            // 创建临时的K线数据（倒序，最新的在前面）
            List<Kline> reversedKlines = new ArrayList<>();
            for (int j = currentIndex; j >= 0 && j > currentIndex - 100; j--) {
                reversedKlines.add(allKlines.get(j));
            }
            
            if (reversedKlines.size() < 50) {
                return "HOLD";
            }
            
            // 构建市场上下文
            BigDecimal currentPrice = reversedKlines.get(0).getClosePrice();
            com.ltp.peter.augtrade.strategy.core.MarketContext context = 
                com.ltp.peter.augtrade.strategy.core.MarketContext.builder()
                    .symbol(symbol)
                    .klines(reversedKlines)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            // 计算所有技术指标
            calculateIndicatorsForBacktest(context);
            
            // 使用组合策略生成信号（使用已注入的实例）
            com.ltp.peter.augtrade.strategy.signal.TradingSignal signal = 
                compositeStrategy.generateSignal(context);
            
            if (signal == null) {
                return "HOLD";
            }
            
            // 🔥 关键修复：完全禁用做空（回测胜率仅21.39%，亏损$5,712）
            if (signal.isSell()) {
                log.debug("⚠️ 做空策略已禁用（回测胜率仅21.39%，严重拖累整体表现）");
                return "HOLD";
            }
            
            // 只做多
            if (signal.isBuy()) {
                log.info("✅ 做多信号 - 强度: {}, 得分: {}, 原因: {}", 
                        signal.getStrength(), signal.getScore(), signal.getReason());
                return "BUY";
            }
            
        } catch (Exception e) {
            log.error("计算组合策略信号失败", e);
        }
        
        return "HOLD";
    }
    
    /**
     * 为回测计算技术指标
     * 
     * 🔥 修复-20260129：添加ADX计算，避免"ADX数据缺失"错误
     */
    private void calculateIndicatorsForBacktest(com.ltp.peter.augtrade.strategy.core.MarketContext context) {
        List<Kline> klines = context.getKlines();
        
        try {
            // ADX - 🔥 重要：CompositeStrategy依赖ADX进行市场环境判断
            double adx = adxCalculator.calculate(klines);
            if (adx > 0) {
                context.addIndicator("ADX", adx);
                log.debug("回测ADX计算完成: {}", adx);
            } else {
                log.warn("⚠️ ADX计算返回0或负值，可能导致策略拒绝交易");
            }
            
            // RSI
            BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
            if (rsi != null) {
                context.addIndicator("RSI", rsi.doubleValue());
            }
            
            // Williams %R
            BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
            if (williamsR != null) {
                context.addIndicator("WilliamsR", williamsR.doubleValue());
            }
            
            // MACD
            MACDResult macdResult = macdCalculator.calculate(klines);
            if (macdResult != null) {
                context.addIndicator("MACD", macdResult);
            }
            
            // Bollinger Bands
            BigDecimal[] bb = indicatorService.calculateBollingerBands(klines, 20, 2.0);
            if (bb != null && bb.length >= 3) {
                com.ltp.peter.augtrade.indicator.BollingerBands bollingerBands = 
                    com.ltp.peter.augtrade.indicator.BollingerBands.builder()
                        .upper(bb[0].doubleValue())
                        .middle(bb[1].doubleValue())
                        .lower(bb[2].doubleValue())
                        .bandwidth(bb[0].subtract(bb[2]).doubleValue())
                        .build();
                context.addIndicator("BollingerBands", bollingerBands);
            }
            
        } catch (Exception e) {
            log.error("回测计算技术指标时发生错误", e);
        }
    }
    
    /**
     * 使用固定美元金额开仓（与实盘配置一致）
     */
    private BacktestTrade openPositionWithDollarStopLoss(String backtestId, String symbol, String side, 
                                                          BigDecimal price, LocalDateTime time, BigDecimal capital,
                                                          int stopLossDollars, int takeProfitDollars) {
        BacktestTrade trade = new BacktestTrade();
        trade.setBacktestId(backtestId);
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setEntryPrice(price);
        trade.setEntryTime(time);
        
        // 固定交易数量：10手黄金（与实盘一致）
        BigDecimal quantity = new BigDecimal("10");
        trade.setQuantity(quantity);
        
        // 使用固定美元金额止损止盈
        if ("BUY".equals(side)) {
            trade.setStopLossPrice(price.subtract(new BigDecimal(stopLossDollars)));
            trade.setTakeProfitPrice(price.add(new BigDecimal(takeProfitDollars)));
            trade.setSignalDescription("组合策略-做多");
        } else {
            trade.setStopLossPrice(price.add(new BigDecimal(stopLossDollars)));
            trade.setTakeProfitPrice(price.subtract(new BigDecimal(takeProfitDollars)));
            trade.setSignalDescription("组合策略-做空");
        }
        
        return trade;
    }
    
    /**
     * 评估短线策略信号
     */
    private String evaluateShortTermSignal(List<Kline> klines) {
        if (klines.size() < 50) {
            return "HOLD";
        }
        
        try {
            // 计算技术指标
            BigDecimal sma5 = indicatorService.calculateSMA(klines, 5);
            BigDecimal sma10 = indicatorService.calculateSMA(klines, 10);
            BigDecimal sma20 = indicatorService.calculateSMA(klines, 20);
            BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
            MACDResult macdResult = macdCalculator.calculate(klines);
            
            int buySignals = 0;
            int sellSignals = 0;
            
            // 买入信号 (降低门槛)
            if (sma5.compareTo(sma10) > 0 && sma10.compareTo(sma20) > 0) buySignals++;
            if (indicatorService.isGoldenCross(klines, 5, 10)) buySignals++;
            if (rsi.compareTo(BigDecimal.valueOf(40)) < 0) buySignals++;  // 放宽到40
            if (macdResult != null && macdResult.getHistogram() > 0) buySignals++;  // 只要柱状图为正即可
            
            // 卖出信号 (降低门槛)
            if (sma5.compareTo(sma10) < 0 && sma10.compareTo(sma20) < 0) sellSignals++;
            if (indicatorService.isDeathCross(klines, 5, 10)) sellSignals++;
            if (rsi.compareTo(BigDecimal.valueOf(60)) > 0) sellSignals++;  // 放宽到60
            if (macdResult != null && macdResult.getHistogram() < 0) sellSignals++;  // 只要柱状图为负即可
            
            // 降低触发门槛：1个信号即可触发
            if (buySignals >= 1 && sellSignals == 0) {
                log.info("触发买入信号：buySignals={}, sma5={}, sma10={}, sma20={}, rsi={}", 
                        buySignals, sma5, sma10, sma20, rsi);
                return "BUY";
            }
            if (sellSignals >= 1 && buySignals == 0) {
                log.info("触发卖出信号：sellSignals={}, sma5={}, sma10={}, sma20={}, rsi={}", 
                        sellSignals, sma5, sma10, sma20, rsi);
                return "SELL";
            }
            
        } catch (Exception e) {
            log.error("计算短线策略信号失败", e);
        }
        
        return "HOLD";
    }
    
    /**
     * 评估突破策略信号
     */
    private String evaluateBreakoutSignal(List<Kline> klines) {
        if (klines.size() < 20) {
            return "HOLD";
        }
        
        try {
            BigDecimal[] bollingerBands = indicatorService.calculateBollingerBands(klines, 20, 2.0);
            BigDecimal upperBand = bollingerBands[0];
            
            BigDecimal currentPrice = klines.get(klines.size() - 1).getClosePrice();
            BigDecimal prevPrice = klines.get(klines.size() - 2).getClosePrice();
            
            // 向上突破上轨
            if (prevPrice.compareTo(upperBand) <= 0 && currentPrice.compareTo(upperBand) > 0) {
                return "BUY";
            }
            
        } catch (Exception e) {
            log.error("计算突破策略信号失败", e);
        }
        
        return "HOLD";
    }
    
    /**
     * 评估RSI策略信号
     */
    private String evaluateRSISignal(List<Kline> klines) {
        if (klines.size() < 14) {
            return "HOLD";
        }
        
        try {
            BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
            
            // RSI超卖
            if (rsi.compareTo(BigDecimal.valueOf(30)) < 0) {
                return "BUY";
            }
            
            // RSI超买
            if (rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
                return "SELL";
            }
            
        } catch (Exception e) {
            log.error("计算RSI策略信号失败", e);
        }
        
        return "HOLD";
    }
    
    /**
     * 开仓
     */
    private BacktestTrade openPosition(String backtestId, String symbol, String side, 
                                       BigDecimal price, LocalDateTime time, BigDecimal capital) {
        BacktestTrade trade = new BacktestTrade();
        trade.setBacktestId(backtestId);
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setEntryPrice(price);
        trade.setEntryTime(time);
        
        // 计算交易数量（使用80%的资金）
        BigDecimal tradeCapital = capital.multiply(new BigDecimal("0.8"));
        BigDecimal quantity = tradeCapital.divide(price, 4, RoundingMode.DOWN);
        trade.setQuantity(quantity);
        
        // 计算止盈止损
        BigDecimal takeProfitPercent = new BigDecimal("0.02"); // 2%
        BigDecimal stopLossPercent = new BigDecimal("0.01"); // 1%
        
        if ("BUY".equals(side)) {
            trade.setTakeProfitPrice(price.multiply(BigDecimal.ONE.add(takeProfitPercent)));
            trade.setStopLossPrice(price.multiply(BigDecimal.ONE.subtract(stopLossPercent)));
            trade.setSignalDescription("买入信号");
        } else {
            trade.setTakeProfitPrice(price.multiply(BigDecimal.ONE.subtract(takeProfitPercent)));
            trade.setStopLossPrice(price.multiply(BigDecimal.ONE.add(stopLossPercent)));
            trade.setSignalDescription("卖出信号");
        }
        
        return trade;
    }
    
    /**
     * 平仓
     */
    private BacktestTrade closePosition(BacktestTrade trade, BigDecimal exitPrice, 
                                        LocalDateTime exitTime, String exitReason, BigDecimal capital) {
        trade.setExitPrice(exitPrice);
        trade.setExitTime(exitTime);
        trade.setExitReason(exitReason);
        
        // 计算持仓时长
        Duration duration = Duration.between(trade.getEntryTime(), exitTime);
        trade.setHoldingMinutes((int) duration.toMinutes());
        
        // 计算盈亏
        BigDecimal profitLoss;
        if ("BUY".equals(trade.getSide())) {
            profitLoss = exitPrice.subtract(trade.getEntryPrice()).multiply(trade.getQuantity());
        } else {
            profitLoss = trade.getEntryPrice().subtract(exitPrice).multiply(trade.getQuantity());
        }
        
        // 计算手续费（开仓+平仓）
        BigDecimal openFee = trade.getEntryPrice().multiply(trade.getQuantity()).multiply(FEE_RATE);
        BigDecimal closeFee = exitPrice.multiply(trade.getQuantity()).multiply(FEE_RATE);
        BigDecimal totalFee = openFee.add(closeFee);
        
        trade.setFee(totalFee);
        trade.setProfitLoss(profitLoss);
        
        // 计算盈亏率
        BigDecimal profitLossRate = profitLoss.divide(trade.getEntryPrice().multiply(trade.getQuantity()), 
                                                       4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        trade.setProfitLossRate(profitLossRate);
        
        return trade;
    }
    
    /**
     * 计算回测结果
     */
    private BacktestResult calculateBacktestResult(String symbol, String interval, 
                                                    BigDecimal initialCapital, BigDecimal finalCapital,
                                                    BigDecimal maxCapital, List<BacktestTrade> trades) {
        BacktestResult result = new BacktestResult();
        result.setSymbol(symbol);
        result.setInterval(interval);
        result.setInitialCapital(initialCapital);
        result.setFinalCapital(finalCapital);
        
        // 总收益
        BigDecimal totalProfit = finalCapital.subtract(initialCapital);
        result.setTotalProfit(totalProfit);
        
        // 收益率
        BigDecimal returnRate = totalProfit.divide(initialCapital, 4, RoundingMode.HALF_UP)
                                           .multiply(new BigDecimal("100"));
        result.setReturnRate(returnRate);
        
        // 交易统计
        result.setTotalTrades(trades.size());
        
        int profitableTrades = 0;
        int losingTrades = 0;
        BigDecimal totalProfitAmount = BigDecimal.ZERO;
        BigDecimal totalLossAmount = BigDecimal.ZERO;
        BigDecimal maxProfit = BigDecimal.ZERO;
        BigDecimal maxLoss = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        
        for (BacktestTrade trade : trades) {
            totalFee = totalFee.add(trade.getFee());
            
            if (trade.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                profitableTrades++;
                totalProfitAmount = totalProfitAmount.add(trade.getProfitLoss());
                if (trade.getProfitLoss().compareTo(maxProfit) > 0) {
                    maxProfit = trade.getProfitLoss();
                }
            } else {
                losingTrades++;
                totalLossAmount = totalLossAmount.add(trade.getProfitLoss().abs());
                if (trade.getProfitLoss().compareTo(maxLoss) < 0) {
                    maxLoss = trade.getProfitLoss();
                }
            }
        }
        
        result.setProfitableTrades(profitableTrades);
        result.setLosingTrades(losingTrades);
        result.setMaxProfit(maxProfit);
        result.setMaxLoss(maxLoss);
        result.setTotalFee(totalFee);
        
        // 胜率
        if (trades.size() > 0) {
            BigDecimal winRate = new BigDecimal(profitableTrades)
                                    .divide(new BigDecimal(trades.size()), 4, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100"));
            result.setWinRate(winRate);
        } else {
            result.setWinRate(BigDecimal.ZERO);
        }
        
        // 平均盈利和亏损
        if (profitableTrades > 0) {
            result.setAvgProfit(totalProfitAmount.divide(new BigDecimal(profitableTrades), 
                                                         2, RoundingMode.HALF_UP));
        } else {
            result.setAvgProfit(BigDecimal.ZERO);
        }
        
        if (losingTrades > 0) {
            result.setAvgLoss(totalLossAmount.divide(new BigDecimal(losingTrades), 
                                                     2, RoundingMode.HALF_UP));
        } else {
            result.setAvgLoss(BigDecimal.ZERO);
        }
        
        // 盈亏比
        if (result.getAvgLoss().compareTo(BigDecimal.ZERO) > 0) {
            result.setProfitLossRatio(result.getAvgProfit().divide(result.getAvgLoss(), 
                                                                   2, RoundingMode.HALF_UP));
        } else {
            result.setProfitLossRatio(BigDecimal.ZERO);
        }
        
        // 最大回撤
        BigDecimal maxDrawdown = maxCapital.subtract(finalCapital);
        if (maxDrawdown.compareTo(BigDecimal.ZERO) < 0) {
            maxDrawdown = BigDecimal.ZERO;
        }
        result.setMaxDrawdown(maxDrawdown);
        
        // 最大回撤率
        if (maxCapital.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxDrawdownRate = maxDrawdown.divide(maxCapital, 4, RoundingMode.HALF_UP)
                                                     .multiply(new BigDecimal("100"));
            result.setMaxDrawdownRate(maxDrawdownRate);
        } else {
            result.setMaxDrawdownRate(BigDecimal.ZERO);
        }
        
        // 夏普比率（简化计算，假设无风险利率为0）
        if (trades.size() > 1) {
            // 计算收益的标准差
            BigDecimal avgReturn = totalProfit.divide(new BigDecimal(trades.size()), 4, RoundingMode.HALF_UP);
            BigDecimal variance = BigDecimal.ZERO;
            
            for (BacktestTrade trade : trades) {
                BigDecimal diff = trade.getProfitLoss().subtract(avgReturn);
                variance = variance.add(diff.multiply(diff));
            }
            
            variance = variance.divide(new BigDecimal(trades.size()), 4, RoundingMode.HALF_UP);
            double stdDev = Math.sqrt(variance.doubleValue());
            
            if (stdDev > 0) {
                BigDecimal sharpeRatio = avgReturn.divide(new BigDecimal(stdDev), 2, RoundingMode.HALF_UP);
                result.setSharpeRatio(sharpeRatio);
            } else {
                result.setSharpeRatio(BigDecimal.ZERO);
            }
        } else {
            result.setSharpeRatio(BigDecimal.ZERO);
        }
        
        return result;
    }
    
    /**
     * 获取回测结果
     */
    public BacktestResult getBacktestResult(String backtestId) {
        QueryWrapper<BacktestResult> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("backtest_id", backtestId);
        return backtestResultMapper.selectOne(queryWrapper);
    }
    
    /**
     * 获取回测交易记录
     */
    public List<BacktestTrade> getBacktestTrades(String backtestId) {
        QueryWrapper<BacktestTrade> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("backtest_id", backtestId).orderByAsc("entry_time");
        return backtestTradeMapper.selectList(queryWrapper);
    }
    
    /**
     * 获取所有回测结果
     */
    public List<BacktestResult> getAllBacktestResults() {
        QueryWrapper<BacktestResult> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("create_time");
        return backtestResultMapper.selectList(queryWrapper);
    }
    
    /**
     * 计算当前盈亏（用于日志）
     */
    private BigDecimal calculateCurrentProfitLoss(BacktestTrade trade, BigDecimal currentPrice) {
        if ("BUY".equals(trade.getSide())) {
            return currentPrice.subtract(trade.getEntryPrice()).multiply(trade.getQuantity());
        } else {
            return trade.getEntryPrice().subtract(currentPrice).multiply(trade.getQuantity());
        }
    }
    
    /**
     * 获取K线周期对应的分钟数
     */
    private int getIntervalMinutes(String interval) {
        switch (interval) {
            case "1m":
                return 1;
            case "3m":
                return 3;
            case "5m":
                return 5;
            case "15m":
                return 15;
            case "30m":
                return 30;
            case "1h":
                return 60;
            case "2h":
                return 120;
            case "4h":
                return 240;
            case "1d":
                return 1440;
            default:
                log.warn("未知的K线周期: {}, 默认使用5分钟", interval);
                return 5;
        }
    }
}
