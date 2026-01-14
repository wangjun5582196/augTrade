package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易仪表板控制器
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/dashboard")
public class DashboardController {
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private PositionMapper positionMapper;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.service.BybitTradingService bybitTradingService;
    
    @Autowired
    private com.ltp.peter.augtrade.mapper.KlineMapper klineMapper;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.service.core.strategy.StrategyOrchestrator strategyOrchestrator;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.service.core.indicator.ATRCalculator atrCalculator;
    
    /**
     * 获取仪表板概览数据
     */
    @GetMapping("/overview")
    public Map<String, Object> getDashboardOverview() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取所有已成交订单（FILLED或CLOSED开头的状态）
            List<TradeOrder> allOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .orderByDesc(TradeOrder::getExecutedTime)
            );
            
            // 计算总盈亏
            BigDecimal totalProfitLoss = allOrders.stream()
                .filter(order -> order.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 计算今日盈亏
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            BigDecimal todayProfitLoss = allOrders.stream()
                .filter(order -> order.getExecutedTime() != null && order.getExecutedTime().isAfter(todayStart))
                .filter(order -> order.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 计算总手续费
            BigDecimal totalFees = allOrders.stream()
                .filter(order -> order.getFee() != null)
                .map(TradeOrder::getFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 获取开仓持仓
            List<Position> openPositions = positionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                    .eq(Position::getStatus, "OPEN")
            );
            
            // 计算未实现盈亏（基于实时价格重新计算）
            BigDecimal unrealizedPnl = BigDecimal.ZERO;
            for (Position pos : openPositions) {
                try {
                    BigDecimal currentPrice = null;
                    
                    // 获取实时价格
                    if (bybitTradingService != null) {
                        try {
                            currentPrice = bybitTradingService.getCurrentPrice(pos.getSymbol());
                        } catch (Exception e) {
                            log.debug("从Bybit获取价格失败: {}", e.getMessage());
                        }
                    }
                    
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        currentPrice = marketDataService.getCurrentPrice(pos.getSymbol());
                    }
                    
                    if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                        // 重新计算盈亏
                        BigDecimal priceDiff = "LONG".equals(pos.getDirection()) 
                            ? currentPrice.subtract(pos.getAvgPrice())
                            : pos.getAvgPrice().subtract(currentPrice);
                        
                        BigDecimal positionPnl = priceDiff.multiply(pos.getQuantity());
                        unrealizedPnl = unrealizedPnl.add(positionPnl);
                    }
                } catch (Exception e) {
                    log.warn("计算持仓{}盈亏失败", pos.getSymbol(), e);
                }
            }
            
            // 统计交易次数
            int totalTrades = allOrders.size();
            int todayTrades = (int) allOrders.stream()
                .filter(order -> order.getExecutedTime() != null && order.getExecutedTime().isAfter(todayStart))
                .count();
            
            // 统计总胜率
            long winTrades = allOrders.stream()
                .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                .count();
            
            double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0.0;
            
            // 统计今日胜率和盈亏分布
            List<TradeOrder> todayOrders = allOrders.stream()
                .filter(order -> order.getExecutedTime() != null && order.getExecutedTime().isAfter(todayStart))
                .collect(Collectors.toList());
            
            long todayWinTrades = todayOrders.stream()
                .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                .count();
            
            long todayLossTrades = todayOrders.stream()
                .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0)
                .count();
            
            double todayWinRate = todayTrades > 0 ? (todayWinTrades * 100.0 / todayTrades) : 0.0;
            
            // 计算今日盈利和亏损金额（用于饼图）
            BigDecimal todayProfit = todayOrders.stream()
                .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal todayLoss = todayOrders.stream()
                .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 组装数据
            result.put("success", true);
            result.put("totalProfitLoss", totalProfitLoss.setScale(2, RoundingMode.HALF_UP));
            result.put("todayProfitLoss", todayProfitLoss.setScale(2, RoundingMode.HALF_UP));
            result.put("unrealizedPnl", unrealizedPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("totalFees", totalFees.setScale(2, RoundingMode.HALF_UP));
            result.put("totalTrades", totalTrades);
            result.put("todayTrades", todayTrades);
            result.put("openPositionsCount", openPositions.size());
            result.put("winRate", String.format("%.2f", winRate));
            result.put("todayWinRate", String.format("%.2f", todayWinRate));
            result.put("todayWinTrades", todayWinTrades);
            result.put("todayLossTrades", todayLossTrades);
            result.put("todayProfit", todayProfit.setScale(2, RoundingMode.HALF_UP));
            result.put("todayLoss", todayLoss.abs().setScale(2, RoundingMode.HALF_UP));
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取仪表板概览数据失败", e);
            result.put("success", false);
            result.put("message", "获取数据失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取持仓列表
     */
    @GetMapping("/positions")
    public Map<String, Object> getPositions() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<Position> positions = positionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                    .eq(Position::getStatus, "OPEN")
                    .orderByDesc(Position::getCreateTime)
            );
            
            // 获取实时价格并计算未实现盈亏
            for (Position position : positions) {
                try {
                    BigDecimal currentPrice = null;
                    
                    // 优先从Bybit获取实时价格
                    if (bybitTradingService != null) {
                        try {
                            currentPrice = bybitTradingService.getCurrentPrice(position.getSymbol());
                            log.debug("从Bybit获取{}实时价格: {}", position.getSymbol(), currentPrice);
                        } catch (Exception e) {
                            log.warn("从Bybit获取价格失败，尝试其他方式: {}", e.getMessage());
                        }
                    }
                    
                    // 如果Bybit获取失败，使用MarketDataService
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        currentPrice = marketDataService.getCurrentPrice(position.getSymbol());
                    }
                    
                    if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                        position.setCurrentPrice(currentPrice);
                        
                        // 计算未实现盈亏
                        BigDecimal priceDiff = "LONG".equals(position.getDirection()) 
                            ? currentPrice.subtract(position.getAvgPrice())
                            : position.getAvgPrice().subtract(currentPrice);
                        
                        BigDecimal unrealizedPnl = priceDiff.multiply(position.getQuantity());
                        position.setUnrealizedPnl(unrealizedPnl);
                        
                        log.debug("持仓 {} - 均价: {}, 当前价: {}, 盈亏: {}", 
                            position.getSymbol(), position.getAvgPrice(), currentPrice, unrealizedPnl);
                    } else {
                        log.warn("无法获取 {} 的有效价格", position.getSymbol());
                    }
                } catch (Exception e) {
                    log.error("更新持仓 {} 价格失败", position.getSymbol(), e);
                }
            }
            
            result.put("success", true);
            result.put("count", positions.size());
            result.put("data", positions);
            
        } catch (Exception e) {
            log.error("获取持仓列表失败", e);
            result.put("success", false);
            result.put("message", "获取持仓失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取最近交易记录
     */
    @GetMapping("/recent-trades")
    public Map<String, Object> getRecentTrades(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<TradeOrder> orders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .orderByDesc(TradeOrder::getExecutedTime)
                    .last("LIMIT " + limit)
            );
            
            result.put("success", true);
            result.put("count", orders.size());
            result.put("data", orders);
            
        } catch (Exception e) {
            log.error("获取最近交易记录失败", e);
            result.put("success", false);
            result.put("message", "获取交易记录失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取策略执行统计
     */
    @GetMapping("/strategy-stats")
    public Map<String, Object> getStrategyStats() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<TradeOrder> allOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
            );
            
            // 按策略分组统计
            Map<String, List<TradeOrder>> strategyGroups = allOrders.stream()
                .collect(Collectors.groupingBy(
                    order -> order.getStrategyName() != null ? order.getStrategyName() : "未知策略"
                ));
            
            List<Map<String, Object>> strategyStats = new ArrayList<>();
            
            for (Map.Entry<String, List<TradeOrder>> entry : strategyGroups.entrySet()) {
                String strategyName = entry.getKey();
                List<TradeOrder> orders = entry.getValue();
                
                BigDecimal totalPnl = orders.stream()
                    .filter(order -> order.getProfitLoss() != null)
                    .map(TradeOrder::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                long winCount = orders.stream()
                    .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .count();
                
                long lossCount = orders.stream()
                    .filter(order -> order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0)
                    .count();
                
                double winRate = orders.size() > 0 ? (winCount * 100.0 / orders.size()) : 0.0;
                
                Map<String, Object> stat = new HashMap<>();
                stat.put("strategyName", strategyName);
                stat.put("totalTrades", orders.size());
                stat.put("winTrades", winCount);
                stat.put("lossTrades", lossCount);
                stat.put("winRate", String.format("%.2f", winRate));
                stat.put("totalPnl", totalPnl.setScale(2, RoundingMode.HALF_UP));
                
                strategyStats.add(stat);
            }
            
            // 按总盈亏排序
            strategyStats.sort((a, b) -> {
                BigDecimal pnlA = (BigDecimal) a.get("totalPnl");
                BigDecimal pnlB = (BigDecimal) b.get("totalPnl");
                return pnlB.compareTo(pnlA);
            });
            
            result.put("success", true);
            result.put("count", strategyStats.size());
            result.put("data", strategyStats);
            
        } catch (Exception e) {
            log.error("获取策略统计失败", e);
            result.put("success", false);
            result.put("message", "获取策略统计失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取每日盈亏统计
     */
    @GetMapping("/daily-pnl")
    public Map<String, Object> getDailyPnl(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(days);
            
            List<TradeOrder> orders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, startDate)
                    .orderByAsc(TradeOrder::getExecutedTime)
            );
            
            // 按日期分组
            Map<LocalDate, List<TradeOrder>> dailyGroups = orders.stream()
                .collect(Collectors.groupingBy(
                    order -> order.getExecutedTime().toLocalDate()
                ));
            
            List<Map<String, Object>> dailyStats = new ArrayList<>();
            
            for (int i = days - 1; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                List<TradeOrder> dailyOrders = dailyGroups.getOrDefault(date, new ArrayList<>());
                
                BigDecimal dailyPnl = dailyOrders.stream()
                    .filter(order -> order.getProfitLoss() != null)
                    .map(TradeOrder::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                Map<String, Object> stat = new HashMap<>();
                stat.put("date", date.toString());
                stat.put("pnl", dailyPnl.setScale(2, RoundingMode.HALF_UP));
                stat.put("trades", dailyOrders.size());
                
                dailyStats.add(stat);
            }
            
            result.put("success", true);
            result.put("count", dailyStats.size());
            result.put("data", dailyStats);
            
        } catch (Exception e) {
            log.error("获取每日盈亏统计失败", e);
            result.put("success", false);
            result.put("message", "获取每日盈亏失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取黄金K线数据
     */
    @GetMapping("/gold-kline")
    public Map<String, Object> getGoldKline(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "15") String interval,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 如果没有指定symbol，尝试自动检测数据库中的黄金标的
            if (symbol == null || symbol.isEmpty()) {
                // 按优先级尝试多个可能的黄金标的符号
                String[] possibleSymbols = {"XAUTUSDT", "XAUUSDT", "XAUUSD"};
                for (String trySymbol : possibleSymbols) {
                    long count = klineMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ltp.peter.augtrade.entity.Kline>()
                            .eq(com.ltp.peter.augtrade.entity.Kline::getSymbol, trySymbol)
                            .eq(com.ltp.peter.augtrade.entity.Kline::getInterval, interval)
                    );
                    if (count > 0) {
                        symbol = trySymbol;
                        log.info("自动检测到K线数据标的: {}, 数据量: {}", symbol, count);
                        break;
                    }
                }
                
                // 如果还是没找到，使用默认值
                if (symbol == null || symbol.isEmpty()) {
                    symbol = "XAUTUSDT";
                    log.warn("未找到K线数据，使用默认标的: {}", symbol);
                }
            }
            
            // 查询K线数据
            List<com.ltp.peter.augtrade.entity.Kline> klines = klineMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ltp.peter.augtrade.entity.Kline>()
                    .eq(com.ltp.peter.augtrade.entity.Kline::getSymbol, symbol)
                    .eq(com.ltp.peter.augtrade.entity.Kline::getInterval, interval)
                    .orderByDesc(com.ltp.peter.augtrade.entity.Kline::getTimestamp)
                    .last("LIMIT " + limit)
            );
            
            // 反转顺序（从旧到新）
            Collections.reverse(klines);
            
            // 转换为图表需要的格式
            List<Map<String, Object>> chartData = new ArrayList<>();
            for (com.ltp.peter.augtrade.entity.Kline kline : klines) {
                Map<String, Object> data = new HashMap<>();
                data.put("time", kline.getTimestamp().toString());
                data.put("open", kline.getOpenPrice());
                data.put("high", kline.getHighPrice());
                data.put("low", kline.getLowPrice());
                data.put("close", kline.getClosePrice());
                data.put("volume", kline.getVolume());
                chartData.add(data);
            }
            
            result.put("success", true);
            result.put("symbol", symbol);
            result.put("interval", interval);
            result.put("count", chartData.size());
            result.put("data", chartData);
            
        } catch (Exception e) {
            log.error("获取黄金K线数据失败", e);
            result.put("success", false);
            result.put("message", "获取K线数据失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取黄金实时价格（从Bybit）
     */
    @GetMapping("/gold-price")
    public Map<String, Object> getGoldPrice() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String symbol = "XAUTUSDT"; // 黄金永续合约
            
            if (bybitTradingService != null) {
                BigDecimal price = bybitTradingService.getCurrentPrice(symbol);
                
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    result.put("success", true);
                    result.put("symbol", symbol);
                    result.put("price", price.setScale(2, RoundingMode.HALF_UP));
                    result.put("timestamp", System.currentTimeMillis());
                    
                    log.debug("获取{}价格成功: {}", symbol, price);
                } else {
                    result.put("success", false);
                    result.put("message", "获取价格失败");
                }
            } else {
                result.put("success", false);
                result.put("message", "Bybit服务未启用");
            }
            
        } catch (Exception e) {
            log.error("获取黄金价格失败", e);
            result.put("success", false);
            result.put("message", "获取价格失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取黄金实时价格2（从Bybit）
     */
    @GetMapping("/gold-price2")
    public Map<String, Object> getGoldPrice2() {
        Map<String, Object> result = new HashMap<>();

        try {
            String symbol = "XAUUSD+"; // 黄金永续合约

            if (bybitTradingService != null) {
                BigDecimal price = bybitTradingService.getCurrentPrice(symbol);

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    result.put("success", true);
                    result.put("symbol", symbol);
                    result.put("price", price.setScale(2, RoundingMode.HALF_UP));
                    result.put("timestamp", System.currentTimeMillis());

                    log.debug("获取{}价格成功: {}", symbol, price);
                } else {
                    result.put("success", false);
                    result.put("message", "获取价格失败");
                }
            } else {
                result.put("success", false);
                result.put("message", "Bybit服务未启用");
            }

        } catch (Exception e) {
            log.error("获取黄金价格失败", e);
            result.put("success", false);
            result.put("message", "获取价格失败: " + e.getMessage());
        }

        return result;
    }
    
    // ==================== 🔥 Dashboard增强方案 - P0核心API ====================
    
    /**
     * P0-1: 获取当前实时信号监控
     * 展示当前策略信号、市场状态、能否开仓等信息
     */
    @GetMapping("/current-signal")
    public Map<String, Object> getCurrentSignal() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (strategyOrchestrator == null) {
                result.put("success", false);
                result.put("message", "策略服务未启用");
                return result;
            }
            
            String symbol = "XAUTUSDT";
            
            // 获取市场上下文
            com.ltp.peter.augtrade.service.core.strategy.MarketContext context = 
                strategyOrchestrator.getMarketContext(symbol, 100);
            
            if (context == null) {
                result.put("success", false);
                result.put("message", "无法获取市场数据");
                return result;
            }
            
            // 生成信号
            com.ltp.peter.augtrade.service.core.signal.TradingSignal signal = 
                strategyOrchestrator.generateSignal(symbol);
            
            if (signal == null) {
                result.put("success", false);
                result.put("message", "无法生成信号");
                return result;
            }
            
            // 获取技术指标
            Double adx = context.getIndicator("ADX");
            Double williamsR = context.getIndicator("WilliamsR");
            com.ltp.peter.augtrade.service.core.indicator.BollingerBands bb = 
                context.getIndicator("BollingerBands");
            com.ltp.peter.augtrade.service.core.indicator.CandlePattern pattern = 
                context.getIndicator("CandlePattern");
            com.ltp.peter.augtrade.service.core.indicator.EMACalculator.EMATrend emaTrend = 
                context.getIndicator("EMATrend");
            
            // 计算价格在布林带的位置百分比
            Double pricePosition = null;
            if (bb != null && context.getCurrentPrice() != null) {
                double price = context.getCurrentPrice().doubleValue();
                double range = bb.getUpper() - bb.getLower();
                if (range > 0) {
                    pricePosition = ((price - bb.getLower()) / range) * 100;
                }
            }
            
            // 判断市场状态
            String marketRegime = "UNKNOWN";
            if (adx != null) {
                if (adx >= 28) {
                    marketRegime = "STRONG_TREND";
                } else if (adx >= 15) {
                    marketRegime = "WEAK_TREND";
                } else {
                    marketRegime = "RANGING";
                }
            }
            
            // 获取策略投票详情
            List<Map<String, Object>> votes = new ArrayList<>();
            if (strategyOrchestrator != null) {
                List<com.ltp.peter.augtrade.service.core.strategy.Strategy> strategies = 
                    strategyOrchestrator.getActiveStrategies();
                
                for (com.ltp.peter.augtrade.service.core.strategy.Strategy strategy : strategies) {
                    try {
                        com.ltp.peter.augtrade.service.core.signal.TradingSignal strategySignal = 
                            strategy.generateSignal(context);
                        
                        Map<String, Object> vote = new HashMap<>();
                        vote.put("strategy", strategy.getName());
                        vote.put("vote", strategySignal != null ? strategySignal.getType().toString() : "HOLD");
                        vote.put("strength", strategySignal != null ? strategySignal.getStrength() : 0);
                        vote.put("weight", strategy.getWeight());
                        vote.put("enabled", strategy.isEnabled());
                        
                        votes.add(vote);
                    } catch (Exception e) {
                        log.warn("获取策略{}投票失败: {}", strategy.getName(), e.getMessage());
                    }
                }
            }
            
            // 判断能否开仓
            boolean canOpen = signal.isValid() && !signal.isHold();
            String rejectReason = null;
            if (!canOpen) {
                rejectReason = signal.getReason();
            }
            
            // 组装结果
            result.put("success", true);
            result.put("signal", signal.getType().toString());
            result.put("strength", signal.getStrength());
            result.put("score", signal.getScore());
            result.put("votes", votes);
            result.put("marketRegime", marketRegime);
            result.put("adx", adx != null ? String.format("%.2f", adx) : null);
            result.put("williamsR", williamsR != null ? String.format("%.2f", williamsR) : null);
            result.put("pricePosition", pricePosition != null ? String.format("%.1f", pricePosition) : null);
            result.put("bollingerBands", bb != null ? Map.of(
                "upper", String.format("%.2f", bb.getUpper()),
                "middle", String.format("%.2f", bb.getMiddle()),
                "lower", String.format("%.2f", bb.getLower())
            ) : null);
            result.put("candlePattern", pattern != null && pattern.hasPattern() ? 
                pattern.getType().getDescription() + " (" + pattern.getDirection().getDescription() + ")" : "无");
            result.put("candlePatternStrength", pattern != null ? pattern.getStrength() : 0);
            result.put("emaTrend", emaTrend != null ? emaTrend.getTrendDescription() : "未知");
            result.put("canOpen", canOpen);
            result.put("rejectReason", rejectReason);
            result.put("reason", signal.getReason());
            result.put("currentPrice", context.getCurrentPrice());
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取当前信号失败", e);
            result.put("success", false);
            result.put("message", "获取信号失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * P0-2: 获取交易质量分析
     * 按信号强度和市场状态分组统计交易表现
     */
    @GetMapping("/trade-quality")
    public Map<String, Object> getTradeQuality() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取今日交易
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, todayStart)
                    .orderByDesc(TradeOrder::getExecutedTime)
            );
            
            // 按信号强度分组
            Map<String, Map<String, Object>> byStrength = new HashMap<>();
            String[] strengthRanges = {"90-100", "70-89", "50-69", "<50"};
            
            for (String range : strengthRanges) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("count", 0);
                stats.put("winCount", 0);
                stats.put("lossCount", 0);
                stats.put("winRate", 0.0);
                stats.put("totalPnl", BigDecimal.ZERO);
                byStrength.put(range, stats);
            }
            
            for (TradeOrder order : todayOrders) {
                Integer strength = order.getSignalStrength();
                if (strength == null) continue;
                
                String range;
                if (strength >= 90) range = "90-100";
                else if (strength >= 70) range = "70-89";
                else if (strength >= 50) range = "50-69";
                else range = "<50";
                
                Map<String, Object> stats = byStrength.get(range);
                stats.put("count", (int)stats.get("count") + 1);
                
                if (order.getProfitLoss() != null) {
                    BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                    stats.put("totalPnl", pnl.add(order.getProfitLoss()));
                    
                    if (order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                        stats.put("winCount", (int)stats.get("winCount") + 1);
                    } else if (order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0) {
                        stats.put("lossCount", (int)stats.get("lossCount") + 1);
                    }
                }
            }
            
            // 计算胜率
            for (Map<String, Object> stats : byStrength.values()) {
                int count = (int) stats.get("count");
                int winCount = (int) stats.get("winCount");
                if (count > 0) {
                    stats.put("winRate", String.format("%.1f", (winCount * 100.0 / count)));
                }
                BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                stats.put("totalPnl", pnl.setScale(2, RoundingMode.HALF_UP));
            }
            
            // 按市场状态分组
            Map<String, Map<String, Object>> byMarket = new HashMap<>();
            String[] marketStates = {"STRONG_TREND", "WEAK_TREND", "RANGING", "UNKNOWN"};
            
            for (String state : marketStates) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("count", 0);
                stats.put("winCount", 0);
                stats.put("lossCount", 0);
                stats.put("winRate", 0.0);
                stats.put("totalPnl", BigDecimal.ZERO);
                byMarket.put(state, stats);
            }
            
            for (TradeOrder order : todayOrders) {
                String marketRegime = order.getMarketRegime();
                if (marketRegime == null || marketRegime.isEmpty()) {
                    marketRegime = "UNKNOWN";
                }
                
                Map<String, Object> stats = byMarket.get(marketRegime);
                if (stats == null) {
                    stats = byMarket.get("UNKNOWN");
                }
                
                stats.put("count", (int)stats.get("count") + 1);
                
                if (order.getProfitLoss() != null) {
                    BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                    stats.put("totalPnl", pnl.add(order.getProfitLoss()));
                    
                    if (order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                        stats.put("winCount", (int)stats.get("winCount") + 1);
                    } else if (order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0) {
                        stats.put("lossCount", (int)stats.get("lossCount") + 1);
                    }
                }
            }
            
            // 计算胜率
            for (Map<String, Object> stats : byMarket.values()) {
                int count = (int) stats.get("count");
                int winCount = (int) stats.get("winCount");
                if (count > 0) {
                    stats.put("winRate", String.format("%.1f", (winCount * 100.0 / count)));
                }
                BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                stats.put("totalPnl", pnl.setScale(2, RoundingMode.HALF_UP));
            }
            
            result.put("success", true);
            result.put("totalTrades", todayOrders.size());
            result.put("byStrength", byStrength);
            result.put("byMarket", byMarket);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取交易质量分析失败", e);
            result.put("success", false);
            result.put("message", "获取交易质量失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * P0-3: 获取风险指标监控
     * ATR、持仓时长、回撤等风险指标
     */
    @GetMapping("/risk-metrics")
    public Map<String, Object> getRiskMetrics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String symbol = "XAUTUSDT";
            
            // 获取当前ATR
            Double currentATR = null;
            if (atrCalculator != null) {
                try {
                    List<com.ltp.peter.augtrade.entity.Kline> klines = 
                        marketDataService.getLatestKlines(symbol, "5m", 100);
                    if (klines != null && !klines.isEmpty()) {
                        currentATR = atrCalculator.calculate(klines);
                    }
                } catch (Exception e) {
                    log.warn("计算ATR失败: {}", e.getMessage());
                }
            }
            
            // 获取当前持仓
            List<Position> openPositions = positionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                    .eq(Position::getStatus, "OPEN")
            );
            
            // 计算持仓时长
            Long currentHoldingTime = null;
            if (!openPositions.isEmpty()) {
                Position pos = openPositions.get(0);
                if (pos.getCreateTime() != null) {
                    currentHoldingTime = java.time.Duration.between(
                        pos.getCreateTime(), LocalDateTime.now()
                    ).getSeconds();
                }
            }
            
            // 计算今日最大回撤
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, todayStart)
                    .orderByAsc(TradeOrder::getExecutedTime)
            );
            
            BigDecimal maxDrawdown = BigDecimal.ZERO;
            BigDecimal peak = BigDecimal.ZERO;
            BigDecimal cumPnl = BigDecimal.ZERO;
            
            for (TradeOrder order : todayOrders) {
                if (order.getProfitLoss() != null) {
                    cumPnl = cumPnl.add(order.getProfitLoss());
                    if (cumPnl.compareTo(peak) > 0) {
                        peak = cumPnl;
                    }
                    BigDecimal drawdown = peak.subtract(cumPnl);
                    if (drawdown.compareTo(maxDrawdown) > 0) {
                        maxDrawdown = drawdown;
                    }
                }
            }
            
            // 组装结果
            result.put("success", true);
            result.put("currentATR", currentATR != null ? String.format("%.2f", currentATR) : null);
            result.put("dynamicStopLoss", currentATR != null ? String.format("%.2f", currentATR * 2) : null);
            result.put("dynamicTakeProfit", currentATR != null ? String.format("%.2f", currentATR * 3) : null);
            result.put("riskRewardRatio", currentATR != null ? "1.5:1" : null);
            result.put("currentHoldingTime", currentHoldingTime);
            result.put("minHoldingTime", 600); // 10分钟
            result.put("maxHoldingTime", 1800); // 30分钟
            result.put("todayMaxDrawdown", maxDrawdown.setScale(2, RoundingMode.HALF_UP));
            result.put("todayMaxDrawdownPercent", maxDrawdown.doubleValue() / 2000 * 100); // 假设初始资金$2000
            result.put("riskBudgetRemaining", BigDecimal.valueOf(500).subtract(maxDrawdown).setScale(2, RoundingMode.HALF_UP));
            result.put("hasOpenPosition", !openPositions.isEmpty());
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取风险指标失败", e);
            result.put("success", false);
            result.put("message", "获取风险指标失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * P0-4: 获取做多vs做空对比分析
     */
    @GetMapping("/direction-analysis")
    public Map<String, Object> getDirectionAnalysis() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取今日交易
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, todayStart)
            );
            
            // 分组统计
            Map<String, Object> longStats = new HashMap<>();
            Map<String, Object> shortStats = new HashMap<>();
            
            List<TradeOrder> longOrders = todayOrders.stream()
                .filter(o -> "BUY".equals(o.getSide()))
                .collect(Collectors.toList());
            
            List<TradeOrder> shortOrders = todayOrders.stream()
                .filter(o -> "SELL".equals(o.getSide()))
                .collect(Collectors.toList());
            
            // 做多统计
            longStats.put("count", longOrders.size());
            long longWins = longOrders.stream()
                .filter(o -> o.getProfitLoss() != null && o.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                .count();
            longStats.put("winCount", longWins);
            longStats.put("winRate", longOrders.size() > 0 ? 
                String.format("%.1f", (longWins * 100.0 / longOrders.size())) : "0.0");
            
            BigDecimal longTotalPnl = longOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            longStats.put("totalPnl", longTotalPnl.setScale(2, RoundingMode.HALF_UP));
            
            BigDecimal longAvgPnl = longOrders.size() > 0 ? 
                longTotalPnl.divide(BigDecimal.valueOf(longOrders.size()), 2, RoundingMode.HALF_UP) : 
                BigDecimal.ZERO;
            longStats.put("avgPnl", longAvgPnl);
            
            BigDecimal longMaxProfit = longOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            longStats.put("maxProfit", longMaxProfit.setScale(2, RoundingMode.HALF_UP));
            
            BigDecimal longMaxLoss = longOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            longStats.put("maxLoss", longMaxLoss.setScale(2, RoundingMode.HALF_UP));
            
            // 做空统计
            shortStats.put("count", shortOrders.size());
            long shortWins = shortOrders.stream()
                .filter(o -> o.getProfitLoss() != null && o.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                .count();
            shortStats.put("winCount", shortWins);
            shortStats.put("winRate", shortOrders.size() > 0 ? 
                String.format("%.1f", (shortWins * 100.0 / shortOrders.size())) : "0.0");
            
            BigDecimal shortTotalPnl = shortOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            shortStats.put("totalPnl", shortTotalPnl.setScale(2, RoundingMode.HALF_UP));
            
            BigDecimal shortAvgPnl = shortOrders.size() > 0 ? 
                shortTotalPnl.divide(BigDecimal.valueOf(shortOrders.size()), 2, RoundingMode.HALF_UP) : 
                BigDecimal.ZERO;
            shortStats.put("avgPnl", shortAvgPnl);
            
            BigDecimal shortMaxProfit = shortOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            shortStats.put("maxProfit", shortMaxProfit.setScale(2, RoundingMode.HALF_UP));
            
            BigDecimal shortMaxLoss = shortOrders.stream()
                .filter(o -> o.getProfitLoss() != null)
                .map(TradeOrder::getProfitLoss)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            shortStats.put("maxLoss", shortMaxLoss.setScale(2, RoundingMode.HALF_UP));
            
            result.put("success", true);
            result.put("long", longStats);
            result.put("short", shortStats);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取方向分析失败", e);
            result.put("success", false);
            result.put("message", "获取方向分析失败: " + e.getMessage());
        }
        
        return result;
    }
    
    // ==================== 🔥 Dashboard增强方案 - P1次要API ====================
    
    /**
     * P1-1: 获取持仓时长分析
     * 按持仓时长分组统计交易表现
     */
    @GetMapping("/holding-time-analysis")
    public Map<String, Object> getHoldingTimeAnalysis() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取今日交易
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, todayStart)
            );
            
            // 按持仓时长分组
            Map<String, Map<String, Object>> byDuration = new LinkedHashMap<>();
            String[] durationRanges = {"<5分钟", "5-15分钟", "15-30分钟", ">30分钟"};
            
            for (String range : durationRanges) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("count", 0);
                stats.put("winCount", 0);
                stats.put("lossCount", 0);
                stats.put("winRate", "0.0");
                stats.put("totalPnl", BigDecimal.ZERO);
                stats.put("avgPnl", BigDecimal.ZERO);
                byDuration.put(range, stats);
            }
            
            // 统计每笔交易
            long minDuration = Long.MAX_VALUE;
            long maxDuration = 0;
            long totalDuration = 0;
            int validCount = 0;
            
            for (TradeOrder order : todayOrders) {
                if (order.getCreateTime() == null || order.getUpdateTime() == null) {
                    continue;
                }
                
                // 计算持仓时长（秒）
                long duration = java.time.Duration.between(
                    order.getCreateTime(), order.getUpdateTime()
                ).getSeconds();
                
                totalDuration += duration;
                validCount++;
                
                if (duration < minDuration) minDuration = duration;
                if (duration > maxDuration) maxDuration = duration;
                
                // 确定分组
                String range;
                if (duration < 300) { // <5分钟
                    range = "<5分钟";
                } else if (duration < 900) { // 5-15分钟
                    range = "5-15分钟";
                } else if (duration < 1800) { // 15-30分钟
                    range = "15-30分钟";
                } else { // >30分钟
                    range = ">30分钟";
                }
                
                Map<String, Object> stats = byDuration.get(range);
                stats.put("count", (int)stats.get("count") + 1);
                
                if (order.getProfitLoss() != null) {
                    BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                    stats.put("totalPnl", pnl.add(order.getProfitLoss()));
                    
                    if (order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                        stats.put("winCount", (int)stats.get("winCount") + 1);
                    } else if (order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0) {
                        stats.put("lossCount", (int)stats.get("lossCount") + 1);
                    }
                }
            }
            
            // 计算胜率和平均盈亏
            for (Map<String, Object> stats : byDuration.values()) {
                int count = (int) stats.get("count");
                int winCount = (int) stats.get("winCount");
                BigDecimal totalPnl = (BigDecimal) stats.get("totalPnl");
                
                if (count > 0) {
                    stats.put("winRate", String.format("%.1f", (winCount * 100.0 / count)));
                    stats.put("avgPnl", totalPnl.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
                }
                stats.put("totalPnl", totalPnl.setScale(2, RoundingMode.HALF_UP));
            }
            
            // 计算平均持仓时长
            long avgDuration = validCount > 0 ? totalDuration / validCount : 0;
            
            result.put("success", true);
            result.put("byDuration", byDuration);
            result.put("avgDuration", avgDuration);
            result.put("minDuration", minDuration == Long.MAX_VALUE ? 0 : minDuration);
            result.put("maxDuration", maxDuration);
            result.put("totalTrades", validCount);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取持仓时长分析失败", e);
            result.put("success", false);
            result.put("message", "获取持仓时长分析失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * P1-2: 获取开仓时机分析
     * 按小时统计交易表现
     */
    @GetMapping("/timing-analysis")
    public Map<String, Object> getTimingAnalysis() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取今日交易
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .ge(TradeOrder::getExecutedTime, todayStart)
            );
            
            // 按时间段分组
            Map<String, Map<String, Object>> byTimeSlot = new LinkedHashMap<>();
            String[] timeSlots = {
                "00:00-02:00", "02:00-04:00", "04:00-06:00", "06:00-08:00",
                "08:00-10:00", "10:00-12:00", "12:00-14:00", "14:00-16:00",
                "16:00-18:00", "18:00-20:00", "20:00-22:00", "22:00-24:00"
            };
            
            for (String slot : timeSlots) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("count", 0);
                stats.put("winCount", 0);
                stats.put("lossCount", 0);
                stats.put("winRate", "0.0");
                stats.put("totalPnl", BigDecimal.ZERO);
                byTimeSlot.put(slot, stats);
            }
            
            // 统计每笔交易
            for (TradeOrder order : todayOrders) {
                if (order.getExecutedTime() == null) {
                    continue;
                }
                
                int hour = order.getExecutedTime().getHour();
                
                // 确定时间段
                String slot = String.format("%02d:00-%02d:00", 
                    (hour / 2) * 2, ((hour / 2) * 2 + 2) % 24);
                
                Map<String, Object> stats = byTimeSlot.get(slot);
                if (stats == null) continue;
                
                stats.put("count", (int)stats.get("count") + 1);
                
                if (order.getProfitLoss() != null) {
                    BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                    stats.put("totalPnl", pnl.add(order.getProfitLoss()));
                    
                    if (order.getProfitLoss().compareTo(BigDecimal.ZERO) > 0) {
                        stats.put("winCount", (int)stats.get("winCount") + 1);
                    } else if (order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0) {
                        stats.put("lossCount", (int)stats.get("lossCount") + 1);
                    }
                }
            }
            
            // 计算胜率
            for (Map<String, Object> stats : byTimeSlot.values()) {
                int count = (int) stats.get("count");
                int winCount = (int) stats.get("winCount");
                if (count > 0) {
                    stats.put("winRate", String.format("%.1f", (winCount * 100.0 / count)));
                }
                BigDecimal pnl = (BigDecimal) stats.get("totalPnl");
                stats.put("totalPnl", pnl.setScale(2, RoundingMode.HALF_UP));
            }
            
            // 找出最佳和最差时段
            String bestSlot = null;
            String worstSlot = null;
            BigDecimal bestPnl = new BigDecimal("-999999");
            BigDecimal worstPnl = new BigDecimal("999999");
            
            for (Map.Entry<String, Map<String, Object>> entry : byTimeSlot.entrySet()) {
                int count = (int) entry.getValue().get("count");
                if (count == 0) continue;
                
                BigDecimal pnl = (BigDecimal) entry.getValue().get("totalPnl");
                if (pnl.compareTo(bestPnl) > 0) {
                    bestPnl = pnl;
                    bestSlot = entry.getKey();
                }
                if (pnl.compareTo(worstPnl) < 0) {
                    worstPnl = pnl;
                    worstSlot = entry.getKey();
                }
            }
            
            result.put("success", true);
            result.put("byTimeSlot", byTimeSlot);
            result.put("bestSlot", bestSlot);
            result.put("bestPnl", bestPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("worstSlot", worstSlot);
            result.put("worstPnl", worstPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取开仓时机分析失败", e);
            result.put("success", false);
            result.put("message", "获取开仓时机分析失败: " + e.getMessage());
        }
        
        return result;
    }

}
