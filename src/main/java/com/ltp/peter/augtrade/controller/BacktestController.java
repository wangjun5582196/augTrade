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
