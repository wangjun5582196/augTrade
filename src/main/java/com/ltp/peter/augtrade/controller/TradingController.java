package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.service.MarketDataService;
import com.ltp.peter.augtrade.service.RiskManagementService;
import com.ltp.peter.augtrade.service.TradeExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易平台REST API控制器
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/trading")
public class TradingController {
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private KlineMapper klineMapper;
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private PositionMapper positionMapper;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private TradeExecutionService tradeExecutionService;
    
    /**
     * 手动执行买入测试
     */
    @PostMapping("/test/buy")
    public Map<String, Object> testBuy(
            @RequestParam(defaultValue = "XAUUSD") String symbol,
            @RequestParam(defaultValue = "0.01") BigDecimal quantity) {
        
        Map<String, Object> result = new HashMap<>();
        try {
            com.ltp.peter.augtrade.entity.TradeOrder order = tradeExecutionService.executeBuy(symbol, quantity, "手动测试");
            if (order != null) {
                result.put("success", true);
                result.put("message", "买入订单创建成功");
                result.put("order", order);
            } else {
                result.put("success", false);
                result.put("message", "买入失败，可能是风控限制");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "买入异常: " + e.getMessage());
            log.error("手动买入失败", e);
        }
        return result;
    }
    
    /**
     * 手动执行卖出测试
     */
    @PostMapping("/test/sell")
    public Map<String, Object> testSell(
            @RequestParam(defaultValue = "XAUUSD") String symbol,
            @RequestParam(defaultValue = "0.01") BigDecimal quantity) {
        
        Map<String, Object> result = new HashMap<>();
        try {
            com.ltp.peter.augtrade.entity.TradeOrder order = tradeExecutionService.executeSell(symbol, quantity, "手动测试");
            if (order != null) {
                result.put("success", true);
                result.put("message", "卖出订单创建成功");
                result.put("order", order);
            } else {
                result.put("success", false);
                result.put("message", "卖出失败，可能没有持仓");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "卖出异常: " + e.getMessage());
            log.error("手动卖出失败", e);
        }
        return result;
    }
    
    /**
     * 获取当前价格
     */
    @GetMapping("/price/{symbol}")
    public Map<String, Object> getCurrentPrice(@PathVariable String symbol) {
        BigDecimal price = marketDataService.getCurrentPrice(symbol);
        
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("price", price);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }
    
    /**
     * 获取最新K线数据
     */
    @GetMapping("/klines/{symbol}")
    public Map<String, Object> getKlines(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "100") int limit) {
        
        List<Kline> klines = marketDataService.getLatestKlines(symbol, interval, limit);
        
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("count", klines.size());
        result.put("data", klines);
        
        return result;
    }
    
    /**
     * 获取所有订单
     */
    @GetMapping("/orders")
    public Map<String, Object> getAllOrders() {
        List<TradeOrder> orders = tradeOrderMapper.selectList(null);
        
        Map<String, Object> result = new HashMap<>();
        result.put("count", orders.size());
        result.put("data", orders);
        
        return result;
    }
    
    /**
     * 获取指定状态的订单
     */
    @GetMapping("/orders/status/{status}")
    public Map<String, Object> getOrdersByStatus(@PathVariable String status) {
        List<TradeOrder> orders = tradeOrderMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getStatus, status)
                .orderByDesc(TradeOrder::getCreateTime)
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("count", orders.size());
        result.put("data", orders);
        
        return result;
    }
    
    /**
     * 获取所有持仓
     */
    @GetMapping("/positions")
    public Map<String, Object> getAllPositions() {
        List<Position> positions = positionMapper.selectList(null);
        
        Map<String, Object> result = new HashMap<>();
        result.put("count", positions.size());
        result.put("data", positions);
        
        return result;
    }
    
    /**
     * 获取开仓持仓
     */
    @GetMapping("/positions/open")
    public Map<String, Object> getOpenPositions() {
        List<Position> positions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, "OPEN")
                .orderByDesc(Position::getCreateTime)
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("count", positions.size());
        result.put("data", positions);
        
        return result;
    }
    
    /**
     * 获取风控统计
     */
    @GetMapping("/risk/statistics")
    public Map<String, Object> getRiskStatistics(@RequestParam String symbol) {
        String statistics = riskManagementService.getRiskStatistics(symbol);
        
        Map<String, Object> result = new HashMap<>();
        result.put("statistics", statistics);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }
    
    /**
     * 一键平仓所有持仓
     */
    @PostMapping("/close-all")
    public Map<String, Object> closeAllPositions() {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("收到一键平仓请求");
            
            // 获取所有开仓持仓
            List<Position> openPositions = positionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                    .eq(Position::getStatus, "OPEN")
            );
            
            if (openPositions.isEmpty()) {
                result.put("success", true);
                result.put("message", "没有需要平仓的持仓");
                result.put("closedCount", 0);
                return result;
            }
            
            int closedCount = 0;
            StringBuilder errors = new StringBuilder();
            
            // 遍历平仓
            for (Position position : openPositions) {
                try {
                    log.info("平仓持仓: {} - {}", position.getSymbol(), position.getId());
                    
                    // 执行卖出平仓
                    TradeOrder closeOrder = tradeExecutionService.executeSell(
                        position.getSymbol(), 
                        position.getQuantity(), 
                        "一键平仓"
                    );
                    
                    if (closeOrder != null) {
                        closedCount++;
                        log.info("✅ 平仓成功: {} - 订单号: {}", position.getSymbol(), closeOrder.getOrderNo());
                    } else {
                        errors.append(position.getSymbol()).append("平仓失败; ");
                        log.warn("⚠️ 平仓失败: {}", position.getSymbol());
                    }
                } catch (Exception e) {
                    errors.append(position.getSymbol()).append("异常: ").append(e.getMessage()).append("; ");
                    log.error("❌ 平仓异常: {}", position.getSymbol(), e);
                }
            }
            
            result.put("success", true);
            result.put("message", "平仓完成");
            result.put("closedCount", closedCount);
            result.put("totalCount", openPositions.size());
            
            if (errors.length() > 0) {
                result.put("errors", errors.toString());
            }
            
            log.info("一键平仓完成 - 成功: {}/{}", closedCount, openPositions.size());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "一键平仓失败: " + e.getMessage());
            log.error("一键平仓异常", e);
        }
        
        return result;
    }
    
    /**
     * 平掉指定持仓
     */
    @PostMapping("/close/{positionId}")
    public Map<String, Object> closePosition(@PathVariable String positionId) {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("收到平仓请求 - PositionID: {}", positionId);
            
            // 查找持仓
            Position position = positionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                    .eq(Position::getId, positionId)
                    .eq(Position::getStatus, "OPEN")
            );
            
            if (position == null) {
                result.put("success", false);
                result.put("message", "持仓不存在或已平仓");
                return result;
            }
            
            // 执行平仓
            TradeOrder closeOrder = tradeExecutionService.executeSell(
                position.getSymbol(),
                position.getQuantity(),
                "手动平仓"
            );
            
            if (closeOrder != null) {
                result.put("success", true);
                result.put("message", "平仓成功");
                result.put("order", closeOrder);
                log.info("✅ 持仓平仓成功 - PositionID: {}, OrderNo: {}", positionId, closeOrder.getOrderNo());
            } else {
                result.put("success", false);
                result.put("message", "平仓失败");
                log.warn("⚠️ 持仓平仓失败 - PositionID: {}", positionId);
            }
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "平仓异常: " + e.getMessage());
            log.error("❌ 平仓异常 - PositionID: {}", positionId, e);
        }
        
        return result;
    }
    
    /**
     * 系统健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "AugTrade");
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }
}
