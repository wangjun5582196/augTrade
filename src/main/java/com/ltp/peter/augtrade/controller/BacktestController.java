package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.BacktestResult;
import com.ltp.peter.augtrade.entity.BacktestTrade;
import com.ltp.peter.augtrade.backtest.BacktestService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测控制器
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/backtest")
@CrossOrigin(origins = "*")
public class BacktestController {
    
    @Autowired
    private BacktestService backtestService;

    @Autowired
    private com.ltp.peter.augtrade.mapper.KlineMapper klineMapper;

    /**
     * 执行回测
     */
    @PostMapping("/execute")
    public Map<String, Object> executeBacktest(@RequestBody BacktestRequest request) {
        log.info("收到回测请求: {}", request);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 解析日期字符串
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime startTime = LocalDateTime.parse(request.getStartTime(), formatter);
            LocalDateTime endTime = LocalDateTime.parse(request.getEndTime(), formatter);
            
            String backtestId = backtestService.executeBacktest(
                request.getSymbol(),
                request.getInterval(),
                startTime,
                endTime,
                request.getInitialCapital(),
                request.getStrategyName()
            );
            
            response.put("success", true);
            response.put("backtestId", backtestId);
            response.put("message", "回测执行成功");
            
        } catch (Exception e) {
            log.error("回测执行失败", e);
            response.put("success", false);
            response.put("message", "回测执行失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 获取回测结果
     */
    @GetMapping("/result/{backtestId}")
    public Map<String, Object> getBacktestResult(@PathVariable String backtestId) {
        log.info("获取回测结果: {}", backtestId);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            BacktestResult result = backtestService.getBacktestResult(backtestId);
            
            if (result != null) {
                response.put("success", true);
                response.put("result", result);
            } else {
                response.put("success", false);
                response.put("message", "回测结果不存在");
            }
            
        } catch (Exception e) {
            log.error("获取回测结果失败", e);
            response.put("success", false);
            response.put("message", "获取回测结果失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 获取回测交易记录
     */
    @GetMapping("/trades/{backtestId}")
    public Map<String, Object> getBacktestTrades(@PathVariable String backtestId) {
        log.info("获取回测交易记录: {}", backtestId);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<BacktestTrade> trades = backtestService.getBacktestTrades(backtestId);
            
            response.put("success", true);
            response.put("trades", trades);
            response.put("total", trades.size());
            
        } catch (Exception e) {
            log.error("获取回测交易记录失败", e);
            response.put("success", false);
            response.put("message", "获取回测交易记录失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 获取所有回测结果列表
     */
    @GetMapping("/list")
    public Map<String, Object> getAllBacktestResults() {
        log.info("获取所有回测结果列表");
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<BacktestResult> results = backtestService.getAllBacktestResults();
            
            response.put("success", true);
            response.put("results", results);
            response.put("total", results.size());
            
        } catch (Exception e) {
            log.error("获取回测结果列表失败", e);
            response.put("success", false);
            response.put("message", "获取回测结果列表失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 获取回测期间的 K 线数据（用于图表展示）
     * 自动计算首末交易时间范围，返回最多 5000 根 K 线
     */
    @GetMapping("/klines/{backtestId}")
    public Map<String, Object> getKlinesForBacktest(@PathVariable String backtestId) {
        Map<String, Object> response = new HashMap<>();
        try {
            BacktestResult result = backtestService.getBacktestResult(backtestId);
            if (result == null) {
                response.put("success", false);
                response.put("message", "回测结果不存在");
                return response;
            }

            List<BacktestTrade> trades = backtestService.getBacktestTrades(backtestId);

            java.time.LocalDateTime rangeStart = result.getStartTime();
            java.time.LocalDateTime rangeEnd   = result.getEndTime();

            if (!trades.isEmpty()) {
                java.time.LocalDateTime firstEntry = trades.stream()
                        .map(BacktestTrade::getEntryTime)
                        .min(java.time.LocalDateTime::compareTo)
                        .orElse(result.getStartTime());
                java.time.LocalDateTime lastExit = trades.stream()
                        .map(t -> t.getExitTime() != null ? t.getExitTime() : t.getEntryTime())
                        .max(java.time.LocalDateTime::compareTo)
                        .orElse(result.getEndTime());

                long minPerBar = intervalToMinutes(result.getInterval());
                rangeStart = firstEntry.minusMinutes(minPerBar * 120);
                rangeEnd   = lastExit.plusMinutes(minPerBar * 60);
            }

            final java.time.LocalDateTime finalStart = rangeStart;
            final java.time.LocalDateTime finalEnd   = rangeEnd;

            List<com.ltp.peter.augtrade.entity.Kline> klines = klineMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ltp.peter.augtrade.entity.Kline>()
                            .eq(com.ltp.peter.augtrade.entity.Kline::getSymbol, result.getSymbol())
                            .eq(com.ltp.peter.augtrade.entity.Kline::getInterval, result.getInterval())
                            .ge(com.ltp.peter.augtrade.entity.Kline::getTimestamp, finalStart)
                            .le(com.ltp.peter.augtrade.entity.Kline::getTimestamp, finalEnd)
                            .orderByAsc(com.ltp.peter.augtrade.entity.Kline::getTimestamp)
                            .last("LIMIT 5000")
            );

            List<Map<String, Object>> chartData = new java.util.ArrayList<>();
            for (com.ltp.peter.augtrade.entity.Kline k : klines) {
                Map<String, Object> d = new HashMap<>();
                d.put("t", k.getTimestamp().toString());
                d.put("o", k.getOpenPrice());
                d.put("h", k.getHighPrice());
                d.put("l", k.getLowPrice());
                d.put("c", k.getClosePrice());
                d.put("v", k.getVolume() != null ? k.getVolume() : 0);
                chartData.add(d);
            }

            response.put("success", true);
            response.put("klines", chartData);
            response.put("symbol", result.getSymbol());
            response.put("interval", result.getInterval());
            response.put("totalKlines", chartData.size());

        } catch (Exception e) {
            log.error("获取K线数据失败", e);
            response.put("success", false);
            response.put("message", "获取K线数据失败: " + e.getMessage());
        }
        return response;
    }

    private long intervalToMinutes(String interval) {
        if (interval == null) return 5;
        switch (interval) {
            case "1m":  return 1;
            case "5m":  return 5;
            case "15m": return 15;
            case "30m": return 30;
            case "1h":  return 60;
            case "4h":  return 240;
            case "1d":  return 1440;
            default:    return 5;
        }
    }

    /**
     * 回测请求DTO
     */
    @Data
    public static class BacktestRequest {
        private String symbol;
        private String interval;
        private String startTime;  // 改为String类型接收
        private String endTime;    // 改为String类型接收
        private BigDecimal initialCapital;
        private String strategyName;
    }
}
