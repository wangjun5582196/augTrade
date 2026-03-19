package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.market.MarketDataService;
import com.ltp.peter.augtrade.trading.broker.BybitTradingService;
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
    private BybitTradingService bybitTradingService;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.trading.broker.BinanceFuturesTradingService binanceFuturesService;
    
    @Autowired
    private com.ltp.peter.augtrade.mapper.KlineMapper klineMapper;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.strategy.core.StrategyOrchestrator strategyOrchestrator;
    
    @Autowired(required = false)
    private com.ltp.peter.augtrade.indicator.ATRCalculator atrCalculator;
    
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
                    
                    // 🔥 优先使用币安获取实时价格
                    if (binanceFuturesService != null) {
                        try {
                            currentPrice = binanceFuturesService.getCurrentPrice(pos.getSymbol());
                            log.debug("从币安获取{}实时价格: {}", pos.getSymbol(), currentPrice);
                        } catch (Exception e) {
                            log.debug("从币安获取价格失败: {}", e.getMessage());
                        }
                    }
                    
                    // Fallback到Bybit
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        if (bybitTradingService != null) {
                            try {
                                currentPrice = bybitTradingService.getCurrentPrice(pos.getSymbol());
                            } catch (Exception e) {
                                log.debug("从Bybit获取价格失败: {}", e.getMessage());
                            }
                        }
                    }
                    
                    // 最后尝试MarketDataService
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
                    
                    // 🔥 优先从币安获取实时价格
                    if (binanceFuturesService != null) {
                        try {
                            currentPrice = binanceFuturesService.getCurrentPrice(position.getSymbol());
                            log.debug("从币安获取{}实时价格: {}", position.getSymbol(), currentPrice);
                        } catch (Exception e) {
                            log.debug("从币安获取价格失败，尝试Bybit: {}", e.getMessage());
                        }
                    }
                    
                    // Fallback到Bybit
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        if (bybitTradingService != null) {
                            try {
                                currentPrice = bybitTradingService.getCurrentPrice(position.getSymbol());
                                log.debug("从Bybit获取{}实时价格: {}", position.getSymbol(), currentPrice);
                            } catch (Exception e) {
                                log.warn("从Bybit获取价格失败，尝试其他方式: {}", e.getMessage());
                            }
                        }
                    }
                    
                    // 最后尝试MarketDataService
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
     * 获取黄金实时价格（优先币安，fallback到Bybit）
     */
    @GetMapping("/gold-price")
    public Map<String, Object> getGoldPrice() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String symbol = "XAUUSDT"; // 黄金永续合约
            BigDecimal price = null;
            String source = null;
            
            // 🔥 优先使用币安
            if (binanceFuturesService != null) {
                try {
                    price = binanceFuturesService.getCurrentPrice(symbol);
                    if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                        source = "Binance";
                        log.debug("从币安获取{}价格成功: {}", symbol, price);
                    }
                } catch (Exception e) {
                    log.debug("从币安获取价格失败，尝试Bybit: {}", e.getMessage());
                }
            }
            
            // Fallback到Bybit
            if (price == null && bybitTradingService != null) {
                try {
                    price = bybitTradingService.getCurrentPrice("XAUTUSDT");
                    if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                        source = "Bybit";
                        log.debug("从Bybit获取{}价格成功: {}", symbol, price);
                    }
                } catch (Exception e) {
                    log.warn("从Bybit获取价格也失败: {}", e.getMessage());
                }
            }
            
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                result.put("success", true);
                result.put("symbol", symbol);
                result.put("price", price.setScale(2, RoundingMode.HALF_UP));
                result.put("source", source);
                result.put("timestamp", System.currentTimeMillis());
            } else {
                result.put("success", false);
                result.put("message", "所有数据源获取价格失败");
            }
            
        } catch (Exception e) {
            log.error("获取黄金价格失败", e);
            result.put("success", false);
            result.put("message", "获取价格失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取黄金实时价格2（优先币安，fallback到Bybit）
     */
    @GetMapping("/gold-price2")
    public Map<String, Object> getGoldPrice2() {
        Map<String, Object> result = new HashMap<>();

        try {
            String symbol = "XAUUSDT"; // 黄金永续合约
            BigDecimal price = null;
            String source = null;
            
            // 🔥 优先使用币安
            if (binanceFuturesService != null) {
                try {
                    price = binanceFuturesService.getCurrentPrice(symbol);
                    if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                        source = "Binance";
                        log.debug("从币安获取{}价格成功: {}", symbol, price);
                    }
                } catch (Exception e) {
                    log.debug("从币安获取价格失败，尝试Bybit: {}", e.getMessage());
                }
            }
            
            // Fallback到Bybit
            if (price == null && bybitTradingService != null) {
                try {
                    price = bybitTradingService.getCurrentPrice("XAUUSD+");
                    if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                        source = "Bybit";
                        log.debug("从Bybit获取{}价格成功: {}", symbol, price);
                    }
                } catch (Exception e) {
                    log.warn("从Bybit获取价格也失败: {}", e.getMessage());
                }
            }

            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                result.put("success", true);
                result.put("symbol", symbol);
                result.put("price", price.setScale(2, RoundingMode.HALF_UP));
                result.put("source", source);
                result.put("timestamp", System.currentTimeMillis());
            } else {
                result.put("success", false);
                result.put("message", "所有数据源获取价格失败");
            }

        } catch (Exception e) {
            log.error("获取黄金价格失败", e);
            result.put("success", false);
            result.put("message", "获取价格失败: " + e.getMessage());
        }

        return result;
    }
    
    // ==================== 🔥 趋势判断API ====================
    
    /**
     * 获取当前市场趋势判断
     * 综合EMA趋势、ADX强度、布林带位置、价格动量等多维度判断
     * 返回：多头趋势 / 空头趋势 / 震荡
     */
    @GetMapping("/trend-analysis")
    public Map<String, Object> getTrendAnalysis() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String symbol = "XAUUSDT";
            
            if (strategyOrchestrator == null) {
                result.put("success", false);
                result.put("message", "策略服务未启用");
                return result;
            }
            
            // 获取市场上下文
            com.ltp.peter.augtrade.strategy.core.MarketContext context = 
                strategyOrchestrator.getMarketContext(symbol, 100);
            
            if (context == null) {
                result.put("success", false);
                result.put("message", "无法获取市场数据");
                return result;
            }
            
            // ====== 1. EMA趋势 ======
            com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend emaTrend = 
                context.getIndicator("EMATrend");
            
            String emaDirection = "NEUTRAL"; // BULLISH / BEARISH / NEUTRAL
            double emaGapPercent = 0;
            double ema20 = 0, ema50 = 0;
            
            if (emaTrend != null) {
                ema20 = emaTrend.getEmaShort();
                ema50 = emaTrend.getEmaLong();
                emaGapPercent = ema50 > 0 ? ((ema20 - ema50) / ema50) * 100 : 0;
                
                if (emaTrend.isUpTrend()) {
                    emaDirection = "BULLISH";
                } else if (emaTrend.isDownTrend()) {
                    emaDirection = "BEARISH";
                }
            }
            
            // ====== 2. ADX趋势强度 ======
            Double adx = context.getIndicator("ADX");
            String adxLevel = "UNKNOWN";
            if (adx != null) {
                if (adx >= 30) adxLevel = "STRONG";      // 强趋势
                else if (adx >= 20) adxLevel = "MODERATE"; // 中等趋势
                else adxLevel = "WEAK";                    // 弱/震荡
            }
            
            // ====== 3. 布林带位置 ======
            com.ltp.peter.augtrade.indicator.BollingerBands bb = 
                context.getIndicator("BollingerBands");
            
            double bbPosition = 50; // 百分比位置 (0=下轨, 50=中轨, 100=上轨)
            String bbZone = "MIDDLE";
            
            if (bb != null && context.getCurrentPrice() != null) {
                double price = context.getCurrentPrice().doubleValue();
                double range = bb.getUpper() - bb.getLower();
                if (range > 0) {
                    bbPosition = ((price - bb.getLower()) / range) * 100;
                }
                
                if (bbPosition > 80) bbZone = "UPPER";       // 上轨区域
                else if (bbPosition > 60) bbZone = "UPPER_MID"; // 中上区域
                else if (bbPosition > 40) bbZone = "MIDDLE";    // 中轨区域
                else if (bbPosition > 20) bbZone = "LOWER_MID"; // 中下区域
                else bbZone = "LOWER";                          // 下轨区域
            }
            
            // ====== 4. Williams %R 动量 ======
            Double williamsR = context.getIndicator("WilliamsR");
            String wrZone = "NEUTRAL";
            if (williamsR != null) {
                if (williamsR < -80) wrZone = "OVERSOLD";       // 超卖
                else if (williamsR > -20) wrZone = "OVERBOUGHT"; // 超买
                else wrZone = "NEUTRAL";
            }
            
            // ====== 5. 价格与EMA的关系 ======
            String priceVsEma = "NEUTRAL";
            double priceEmaGap = 0;
            if (context.getCurrentPrice() != null && ema20 > 0) {
                double price = context.getCurrentPrice().doubleValue();
                priceEmaGap = ((price - ema20) / ema20) * 100;
                if (price > ema20 && price > ema50) priceVsEma = "ABOVE_BOTH";
                else if (price < ema20 && price < ema50) priceVsEma = "BELOW_BOTH";
                else priceVsEma = "BETWEEN";
            }
            
            // ====== 综合趋势判断 ======
            int bullScore = 0;
            int bearScore = 0;
            List<String> bullReasons = new ArrayList<>();
            List<String> bearReasons = new ArrayList<>();
            
            // EMA金叉/死叉 (权重3)
            if ("BULLISH".equals(emaDirection)) {
                bullScore += 3;
                bullReasons.add("EMA20金叉EMA50（EMA20=" + String.format("%.1f", ema20) + " > EMA50=" + String.format("%.1f", ema50) + "）");
            } else if ("BEARISH".equals(emaDirection)) {
                bearScore += 3;
                bearReasons.add("EMA20死叉EMA50（EMA20=" + String.format("%.1f", ema20) + " < EMA50=" + String.format("%.1f", ema50) + "）");
            }
            
            // ADX趋势强度 (权重2)
            if ("STRONG".equals(adxLevel)) {
                if ("BULLISH".equals(emaDirection)) {
                    bullScore += 2;
                    bullReasons.add("ADX=" + String.format("%.1f", adx) + "（强趋势确认）");
                } else if ("BEARISH".equals(emaDirection)) {
                    bearScore += 2;
                    bearReasons.add("ADX=" + String.format("%.1f", adx) + "（强趋势确认）");
                }
            }
            
            // 价格vs EMA位置 (权重2)
            if ("ABOVE_BOTH".equals(priceVsEma)) {
                bullScore += 2;
                bullReasons.add("价格在EMA20和EMA50之上");
            } else if ("BELOW_BOTH".equals(priceVsEma)) {
                bearScore += 2;
                bearReasons.add("价格在EMA20和EMA50之下");
            }
            
            // 布林带位置 (权重1)
            if ("UPPER".equals(bbZone) || "UPPER_MID".equals(bbZone)) {
                bullScore += 1;
                bullReasons.add("价格在布林带上方区域（" + String.format("%.0f", bbPosition) + "%）");
            } else if ("LOWER".equals(bbZone) || "LOWER_MID".equals(bbZone)) {
                bearScore += 1;
                bearReasons.add("价格在布林带下方区域（" + String.format("%.0f", bbPosition) + "%）");
            }
            
            // Williams %R动量 (权重1)
            if ("OVERBOUGHT".equals(wrZone)) {
                bearScore += 1;
                bearReasons.add("Williams %R=" + String.format("%.1f", williamsR) + "（超买区，可能回调）");
            } else if ("OVERSOLD".equals(wrZone)) {
                bullScore += 1;
                bullReasons.add("Williams %R=" + String.format("%.1f", williamsR) + "（超卖区，可能反弹）");
            }
            
            // 最终判断
            String trend;       // BULLISH / BEARISH / RANGING
            String trendLabel;  // 显示名称
            String trendEmoji;
            int trendStrength;  // 趋势强度 0-100
            String trendColor;
            List<String> reasons;
            
            int totalScore = bullScore + bearScore;
            int netScore = bullScore - bearScore;
            
            if ("WEAK".equals(adxLevel) && Math.abs(netScore) <= 2) {
                // ADX弱 + 多空分歧不大 = 震荡
                trend = "RANGING";
                trendLabel = "震荡盘整";
                trendEmoji = "↔️";
                trendStrength = adx != null ? (int)(adx.doubleValue() * 2) : 20;
                trendColor = "#f59e0b"; // 黄色
                reasons = new ArrayList<>();
                reasons.add("ADX=" + String.format("%.1f", adx != null ? adx : 0) + "（趋势较弱）");
                reasons.add("多空信号冲突，方向不明确");
                if (bullReasons.size() > 0) reasons.add("多头信号: " + bullReasons.get(0));
                if (bearReasons.size() > 0) reasons.add("空头信号: " + bearReasons.get(0));
            } else if (netScore >= 3) {
                // 强多头
                trend = "BULLISH";
                trendLabel = "多头趋势";
                trendEmoji = "🔥📈";
                trendStrength = Math.min(100, netScore * 15 + (adx != null ? adx.intValue() : 0));
                trendColor = "#10b981"; // 绿色
                reasons = bullReasons;
            } else if (netScore >= 1) {
                // 弱多头
                trend = "BULLISH_WEAK";
                trendLabel = "偏多震荡";
                trendEmoji = "📈";
                trendStrength = Math.min(70, netScore * 12 + (adx != null ? (int)(adx.doubleValue() * 0.5) : 0));
                trendColor = "#6ee7b7"; // 浅绿
                reasons = bullReasons;
            } else if (netScore <= -3) {
                // 强空头
                trend = "BEARISH";
                trendLabel = "空头趋势";
                trendEmoji = "🔻📉";
                trendStrength = Math.min(100, Math.abs(netScore) * 15 + (adx != null ? adx.intValue() : 0));
                trendColor = "#ef4444"; // 红色
                reasons = bearReasons;
            } else if (netScore <= -1) {
                // 弱空头
                trend = "BEARISH_WEAK";
                trendLabel = "偏空震荡";
                trendEmoji = "📉";
                trendStrength = Math.min(70, Math.abs(netScore) * 12 + (adx != null ? (int)(adx.doubleValue() * 0.5) : 0));
                trendColor = "#fca5a5"; // 浅红
                reasons = bearReasons;
            } else {
                // 完全中性
                trend = "RANGING";
                trendLabel = "震荡盘整";
                trendEmoji = "↔️";
                trendStrength = 20;
                trendColor = "#f59e0b"; // 黄色
                reasons = new ArrayList<>();
                reasons.add("多空力量均衡，暂无明确方向");
            }
            
            // 交易建议
            String suggestion;
            if ("BULLISH".equals(trend)) {
                suggestion = "多头趋势明确，建议顺势做多，回调至EMA20附近可加仓";
            } else if ("BULLISH_WEAK".equals(trend)) {
                suggestion = "偏多但趋势不强，轻仓做多，严格止损";
            } else if ("BEARISH".equals(trend)) {
                suggestion = "空头趋势明确，建议观望或轻仓做空，反弹至EMA20可试空";
            } else if ("BEARISH_WEAK".equals(trend)) {
                suggestion = "偏空但趋势不强，建议观望为主";
            } else {
                suggestion = "震荡市场，建议等待方向明确后再入场，避免频繁交易";
            }
            
            // 组装结果
            result.put("success", true);
            result.put("trend", trend);
            result.put("trendLabel", trendLabel);
            result.put("trendEmoji", trendEmoji);
            result.put("trendStrength", Math.min(100, Math.max(0, trendStrength)));
            result.put("trendColor", trendColor);
            result.put("suggestion", suggestion);
            result.put("reasons", reasons);
            
            // 详细指标
            result.put("bullScore", bullScore);
            result.put("bearScore", bearScore);
            result.put("netScore", netScore);
            
            Map<String, Object> indicators = new LinkedHashMap<>();
            indicators.put("EMA20", ema20 > 0 ? String.format("%.2f", ema20) : "--");
            indicators.put("EMA50", ema50 > 0 ? String.format("%.2f", ema50) : "--");
            indicators.put("EMA方向", emaDirection.equals("BULLISH") ? "金叉 📈" : emaDirection.equals("BEARISH") ? "死叉 📉" : "平行 ↔️");
            indicators.put("EMA间距", String.format("%.3f%%", emaGapPercent));
            indicators.put("ADX", adx != null ? String.format("%.2f", adx) : "--");
            indicators.put("ADX级别", adxLevel.equals("STRONG") ? "强趋势 🔥" : adxLevel.equals("MODERATE") ? "中等 ⚡" : "弱/震荡 💤");
            indicators.put("Williams%R", williamsR != null ? String.format("%.2f", williamsR) : "--");
            indicators.put("WR区域", wrZone.equals("OVERSOLD") ? "超卖 🟢" : wrZone.equals("OVERBOUGHT") ? "超买 🔴" : "中性 ⚪");
            indicators.put("布林带位置", String.format("%.0f%%", bbPosition));
            indicators.put("布林带区域", bbZone);
            if (bb != null) {
                indicators.put("布林上轨", String.format("%.2f", bb.getUpper()));
                indicators.put("布林中轨", String.format("%.2f", bb.getMiddle()));
                indicators.put("布林下轨", String.format("%.2f", bb.getLower()));
            }
            indicators.put("当前价格", context.getCurrentPrice() != null ? context.getCurrentPrice().toPlainString() : "--");
            result.put("indicators", indicators);
            
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取趋势分析失败", e);
            result.put("success", false);
            result.put("message", "获取趋势分析失败: " + e.getMessage());
        }
        
        return result;
    }
    
    // ==================== 🔥 大趋势多时间框架分析 ====================

    /**
     * 获取黄金大趋势分析（多时间框架）
     * 从币安合约API直接获取1h/4h/1d K线，计算各时间框架的趋势方向
     */
    @GetMapping("/macro-trend")
    public Map<String, Object> getMacroTrend() {
        Map<String, Object> result = new HashMap<>();

        try {
            String symbol = "XAUUSDT";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // 获取三个时间框架的K线
            List<Map<String, Object>> timeframes = new ArrayList<>();
            timeframes.add(analyzeSingleTimeframe(client, mapper, symbol, "1h", 50, "1小时"));
            timeframes.add(analyzeSingleTimeframe(client, mapper, symbol, "4h", 50, "4小时"));
            timeframes.add(analyzeSingleTimeframe(client, mapper, symbol, "1d", 30, "日线"));

            // 综合判断：各时间框架加权投票
            // 日线权重3, 4小时权重2, 1小时权重1
            int[] weights = {1, 2, 3};
            int bullScore = 0, bearScore = 0;
            List<String> reasons = new ArrayList<>();

            for (int i = 0; i < timeframes.size(); i++) {
                Map<String, Object> tf = timeframes.get(i);
                String dir = (String) tf.get("direction");
                String label = (String) tf.get("label");
                int w = weights[i];

                if ("BULLISH".equals(dir)) {
                    bullScore += w;
                    reasons.add(label + "看多");
                } else if ("BEARISH".equals(dir)) {
                    bearScore += w;
                    reasons.add(label + "看空");
                } else {
                    reasons.add(label + "震荡");
                }
            }

            int netScore = bullScore - bearScore;
            int maxScore = 6; // 1+2+3

            String overallTrend;
            String overallLabel;
            String overallEmoji;
            String overallColor;
            int overallStrength;

            if (netScore >= 4) {
                overallTrend = "STRONG_BULL";
                overallLabel = "强势上涨";
                overallEmoji = "🚀📈";
                overallColor = "#10b981";
                overallStrength = Math.min(100, 60 + netScore * 7);
            } else if (netScore >= 2) {
                overallTrend = "BULL";
                overallLabel = "偏多上涨";
                overallEmoji = "📈";
                overallColor = "#6ee7b7";
                overallStrength = Math.min(80, 40 + netScore * 8);
            } else if (netScore <= -4) {
                overallTrend = "STRONG_BEAR";
                overallLabel = "强势下跌";
                overallEmoji = "🔻📉";
                overallColor = "#ef4444";
                overallStrength = Math.min(100, 60 + Math.abs(netScore) * 7);
            } else if (netScore <= -2) {
                overallTrend = "BEAR";
                overallLabel = "偏空下跌";
                overallEmoji = "📉";
                overallColor = "#fca5a5";
                overallStrength = Math.min(80, 40 + Math.abs(netScore) * 8);
            } else {
                overallTrend = "NEUTRAL";
                overallLabel = "方向不明";
                overallEmoji = "↔️";
                overallColor = "#f59e0b";
                overallStrength = 30;
            }

            // 综合建议
            String suggestion;
            boolean allAligned = (bullScore > 0 && bearScore == 0) || (bearScore > 0 && bullScore == 0);
            if ("STRONG_BULL".equals(overallTrend)) {
                suggestion = allAligned ? "多周期共振看多，趋势强劲，回调即机会" : "大方向偏多，但有分歧周期，注意节奏";
            } else if ("BULL".equals(overallTrend)) {
                suggestion = "大周期偏多，短线回调不改趋势，顺势为主";
            } else if ("STRONG_BEAR".equals(overallTrend)) {
                suggestion = allAligned ? "多周期共振看空，趋势明确，反弹即风险" : "大方向偏空，但有分歧周期，注意反弹";
            } else if ("BEAR".equals(overallTrend)) {
                suggestion = "大周期偏空，反弹力度有限，谨慎做多";
            } else {
                suggestion = "各时间框架方向不一致，建议等待大周期趋势明朗后再操作";
            }

            result.put("success", true);
            result.put("overallTrend", overallTrend);
            result.put("overallLabel", overallLabel);
            result.put("overallEmoji", overallEmoji);
            result.put("overallColor", overallColor);
            result.put("overallStrength", overallStrength);
            result.put("suggestion", suggestion);
            result.put("reasons", reasons);
            result.put("bullScore", bullScore);
            result.put("bearScore", bearScore);
            result.put("netScore", netScore);
            result.put("timeframes", timeframes);
            result.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("获取大趋势分析失败", e);
            result.put("success", false);
            result.put("message", "获取大趋势分析失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 分析单个时间框架的趋势
     */
    private Map<String, Object> analyzeSingleTimeframe(
            okhttp3.OkHttpClient client,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            String symbol, String interval, int limit, String label) {

        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("interval", interval);
        tf.put("label", label);

        try {
            String url = String.format(
                    "https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=%s&limit=%d",
                    symbol, interval, limit);

            okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    tf.put("direction", "UNKNOWN");
                    tf.put("error", "HTTP " + response.code());
                    return tf;
                }

                com.fasterxml.jackson.databind.JsonNode list = mapper.readTree(response.body().string());
                if (!list.isArray() || list.size() < 20) {
                    tf.put("direction", "UNKNOWN");
                    tf.put("error", "数据不足");
                    return tf;
                }

                // 按时间正序（API返回已是正序：旧→新）
                int size = list.size();

                // 提取收盘价数组
                double[] closes = new double[size];
                double[] highs = new double[size];
                double[] lows = new double[size];
                double latestVolume = 0;
                for (int i = 0; i < size; i++) {
                    closes[i] = list.get(i).get(4).asDouble();
                    highs[i] = list.get(i).get(2).asDouble();
                    lows[i] = list.get(i).get(3).asDouble();
                }
                latestVolume = list.get(size - 1).get(5).asDouble();

                double currentPrice = closes[size - 1];

                // 计算EMA20和EMA50
                double ema20 = calcEMA(closes, 20);
                double ema50 = calcEMA(closes, 50);

                // 价格变化
                double changeFromPrev = size >= 2 ? (closes[size - 1] - closes[size - 2]) : 0;
                double change5 = size >= 6 ? (closes[size - 1] - closes[size - 6]) : 0;
                double change10 = size >= 11 ? (closes[size - 1] - closes[size - 11]) : 0;
                double changePercent5 = size >= 6 && closes[size - 6] > 0
                        ? (change5 / closes[size - 6]) * 100 : 0;

                // 近期高低点
                double recentHigh = Double.MIN_VALUE;
                double recentLow = Double.MAX_VALUE;
                int lookback = Math.min(20, size);
                for (int i = size - lookback; i < size; i++) {
                    if (highs[i] > recentHigh) recentHigh = highs[i];
                    if (lows[i] < recentLow) recentLow = lows[i];
                }
                double priceInRange = recentHigh > recentLow
                        ? (currentPrice - recentLow) / (recentHigh - recentLow) * 100 : 50;

                // 判断方向
                String direction;
                String dirLabel;
                String dirEmoji;
                String dirColor;

                boolean emaGolden = ema20 > ema50;
                boolean priceAboveEma = currentPrice > ema20 && currentPrice > ema50;
                boolean priceBelowEma = currentPrice < ema20 && currentPrice < ema50;

                if (emaGolden && priceAboveEma && change5 > 0) {
                    direction = "BULLISH";
                    dirLabel = "上涨";
                    dirEmoji = "📈";
                    dirColor = "#10b981";
                } else if (!emaGolden && priceBelowEma && change5 < 0) {
                    direction = "BEARISH";
                    dirLabel = "下跌";
                    dirEmoji = "📉";
                    dirColor = "#ef4444";
                } else if (emaGolden || priceAboveEma) {
                    direction = "BULLISH";
                    dirLabel = "偏多";
                    dirEmoji = "📈";
                    dirColor = "#6ee7b7";
                } else if (!emaGolden || priceBelowEma) {
                    direction = "BEARISH";
                    dirLabel = "偏空";
                    dirEmoji = "📉";
                    dirColor = "#fca5a5";
                } else {
                    direction = "NEUTRAL";
                    dirLabel = "震荡";
                    dirEmoji = "↔️";
                    dirColor = "#f59e0b";
                }

                tf.put("direction", direction);
                tf.put("dirLabel", dirLabel);
                tf.put("dirEmoji", dirEmoji);
                tf.put("dirColor", dirColor);
                tf.put("currentPrice", String.format("%.2f", currentPrice));
                tf.put("ema20", String.format("%.2f", ema20));
                tf.put("ema50", String.format("%.2f", ema50));
                tf.put("emaGolden", emaGolden);
                tf.put("change5", String.format("%.2f", change5));
                tf.put("changePercent5", String.format("%.2f", changePercent5));
                tf.put("recentHigh", String.format("%.2f", recentHigh));
                tf.put("recentLow", String.format("%.2f", recentLow));
                tf.put("priceInRange", String.format("%.0f", priceInRange));
            }
        } catch (Exception e) {
            log.error("分析{}时间框架失败", label, e);
            tf.put("direction", "UNKNOWN");
            tf.put("error", e.getMessage());
        }

        return tf;
    }

    /**
     * 计算EMA（指数移动平均）
     */
    private double calcEMA(double[] data, int period) {
        if (data.length < period) return data[data.length - 1];
        double multiplier = 2.0 / (period + 1);
        // 初始SMA
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += data[i];
        }
        ema /= period;
        // EMA迭代
        for (int i = period; i < data.length; i++) {
            ema = (data[i] - ema) * multiplier + ema;
        }
        return ema;
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
            
            String symbol = "XAUUSDT";
            
            // 获取市场上下文
            com.ltp.peter.augtrade.strategy.core.MarketContext context = 
                strategyOrchestrator.getMarketContext(symbol, 100);
            
            if (context == null) {
                result.put("success", false);
                result.put("message", "无法获取市场数据");
                return result;
            }
            
            // 生成信号
            com.ltp.peter.augtrade.strategy.signal.TradingSignal signal = 
                strategyOrchestrator.generateSignal(symbol);
            
            if (signal == null) {
                result.put("success", false);
                result.put("message", "无法生成信号");
                return result;
            }
            
            // 获取技术指标
            Double adx = context.getIndicator("ADX");
            Double williamsR = context.getIndicator("WilliamsR");
            com.ltp.peter.augtrade.indicator.BollingerBands bb = 
                context.getIndicator("BollingerBands");
            com.ltp.peter.augtrade.indicator.CandlePattern pattern = 
                context.getIndicator("CandlePattern");
            com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend emaTrend = 
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
                List<com.ltp.peter.augtrade.strategy.core.Strategy> strategies = 
                    strategyOrchestrator.getActiveStrategies();
                
                for (com.ltp.peter.augtrade.strategy.core.Strategy strategy : strategies) {
                    try {
                        com.ltp.peter.augtrade.strategy.signal.TradingSignal strategySignal = 
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
            
            // 🔥 新增-20260309: 添加新指标数据
            Map<String, Object> newIndicators = new LinkedHashMap<>();
            
            // 1. 动量指标
            if (signal.getMomentum2() != null) {
                newIndicators.put("动量2", signal.getMomentum2().toPlainString());
            }
            if (signal.getMomentum5() != null) {
                newIndicators.put("动量5", signal.getMomentum5().toPlainString());
            }
            
            // 2. 成交量指标
            if (signal.getVolumeRatio() != null) {
                newIndicators.put("成交量比率", String.format("%.2f", signal.getVolumeRatio()));
            }
            
            // 3. 摆动点指标
            if (signal.getLastSwingHigh() != null) {
                newIndicators.put("摆动高点", signal.getLastSwingHigh().toPlainString());
            }
            if (signal.getLastSwingLow() != null) {
                newIndicators.put("摆动低点", signal.getLastSwingLow().toPlainString());
            }
            if (signal.getPricePosition() != null) {
                String posLabel = "ABOVE_SWING_HIGH".equals(signal.getPricePosition()) ? "突破高点 🚀" :
                                 "BELOW_SWING_LOW".equals(signal.getPricePosition()) ? "跌破低点 📉" : "区间内";
                newIndicators.put("价格位置", posLabel);
            }
            
            // 4. HMA指标
            if (signal.getHma20() != null) {
                newIndicators.put("HMA20", signal.getHma20().toPlainString());
            }
            if (signal.getHma20Slope() != null) {
                newIndicators.put("HMA斜率", String.format("%.4f%%", signal.getHma20Slope()));
            }
            
            // 5. 趋势确认
            if (signal.getTrendConfirmed() != null) {
                newIndicators.put("趋势确认", signal.getTrendConfirmed() ? "✅ 已确认" : "❌ 未确认");
            }
            
            // 6. 评分数据
            newIndicators.put("做多评分", String.valueOf(signal.getBuyScore()));
            newIndicators.put("做空评分", String.valueOf(signal.getSellScore()));
            
            result.put("newIndicators", newIndicators);
            result.put("buyReasons", signal.getBuyReasons());
            result.put("sellReasons", signal.getSellReasons());
            
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
            String symbol = "XAUUSDT";
            
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
     * 🔥 新增：获取收益率统计（年/月/7日）
     * 本金：$10,000
     */
    @GetMapping("/returns")
    public Map<String, Object> getReturns() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            BigDecimal principal = new BigDecimal("10000"); // 本金$10,000
            
            // 获取所有已成交订单
            List<TradeOrder> allOrders = tradeOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                    .and(wrapper -> wrapper
                        .eq(TradeOrder::getStatus, "FILLED")
                        .or()
                        .likeRight(TradeOrder::getStatus, "CLOSED"))
                    .isNotNull(TradeOrder::getProfitLoss)
                    .orderByAsc(TradeOrder::getExecutedTime)
            );
            
            if (allOrders.isEmpty()) {
                result.put("success", true);
                result.put("yearReturn", "0.00");
                result.put("monthReturn", "0.00");
                result.put("weekReturn", "0.00");
                result.put("message", "暂无交易数据");
                return result;
            }
            
            // 计算时间范围
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime yearStart = now.minusYears(1);
            LocalDateTime monthStart = now.minusMonths(1);
            LocalDateTime weekStart = now.minusWeeks(1);
            
            // 年度盈亏
            BigDecimal yearPnl = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(yearStart))
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 月度盈亏
            BigDecimal monthPnl = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(monthStart))
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 7日盈亏
            BigDecimal weekPnl = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(weekStart))
                .map(TradeOrder::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 计算收益率
            BigDecimal yearReturn = yearPnl.divide(principal, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            BigDecimal monthReturn = monthPnl.divide(principal, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            BigDecimal weekReturn = weekPnl.divide(principal, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            // 统计交易次数
            long yearTrades = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(yearStart))
                .count();
            long monthTrades = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(monthStart))
                .count();
            long weekTrades = allOrders.stream()
                .filter(o -> o.getExecutedTime() != null && o.getExecutedTime().isAfter(weekStart))
                .count();
            
            result.put("success", true);
            result.put("principal", principal.setScale(2, RoundingMode.HALF_UP));
            result.put("yearReturn", yearReturn.setScale(2, RoundingMode.HALF_UP));
            result.put("monthReturn", monthReturn.setScale(2, RoundingMode.HALF_UP));
            result.put("weekReturn", weekReturn.setScale(2, RoundingMode.HALF_UP));
            result.put("yearPnl", yearPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("monthPnl", monthPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("weekPnl", weekPnl.setScale(2, RoundingMode.HALF_UP));
            result.put("yearTrades", yearTrades);
            result.put("monthTrades", monthTrades);
            result.put("weekTrades", weekTrades);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取收益率统计失败", e);
            result.put("success", false);
            result.put("message", "获取收益率失败: " + e.getMessage());
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
