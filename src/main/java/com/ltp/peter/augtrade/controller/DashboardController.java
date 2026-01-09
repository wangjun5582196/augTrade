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
}
