package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.service.PaperTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模拟交易查询控制器
 * 提供REST API查询交易记录
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/paper-trading")
public class PaperTradingController {
    
    @Autowired
    private PaperTradingService paperTradingService;
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    /**
     * 获取当前持仓
     */
    @GetMapping("/current-position")
    public Map<String, Object> getCurrentPosition() {
        Map<String, Object> result = new HashMap<>();
        
        if (paperTradingService.hasOpenPosition()) {
            com.ltp.peter.augtrade.entity.PaperPosition position = paperTradingService.getCurrentPosition();
            result.put("hasPosition", true);
            result.put("position", position);
        } else {
            result.put("hasPosition", false);
            result.put("message", "当前无持仓");
        }
        
        return result;
    }
    
    /**
     * 获取所有交易记录
     */
    @GetMapping("/trades")
    public Map<String, Object> getAllTrades() {
        List<TradeOrder> allTrades = tradeOrderMapper.selectList(null).stream()
                .filter(order -> "AggressiveML".equals(order.getStrategyName()))
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .collect(Collectors.toList());
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", allTrades.size());
        result.put("trades", allTrades);
        
        return result;
    }
    
    /**
     * 获取持仓中的订单
     */
    @GetMapping("/open-trades")
    public Map<String, Object> getOpenTrades() {
        List<TradeOrder> openTrades = tradeOrderMapper.selectList(null).stream()
                .filter(order -> "AggressiveML".equals(order.getStrategyName()))
                .filter(order -> "OPEN".equals(order.getStatus()))
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .collect(Collectors.toList());
        
        Map<String, Object> result = new HashMap<>();
        result.put("count", openTrades.size());
        result.put("trades", openTrades);
        
        return result;
    }
    
    /**
     * 获取已平仓订单
     */
    @GetMapping("/closed-trades")
    public Map<String, Object> getClosedTrades() {
        List<TradeOrder> closedTrades = tradeOrderMapper.selectList(null).stream()
                .filter(order -> "AggressiveML".equals(order.getStrategyName()))
                .filter(order -> order.getStatus() != null && order.getStatus().startsWith("CLOSED"))
                .sorted((a, b) -> b.getUpdateTime().compareTo(a.getUpdateTime()))
                .collect(Collectors.toList());
        
        Map<String, Object> result = new HashMap<>();
        result.put("count", closedTrades.size());
        result.put("trades", closedTrades);
        
        return result;
    }
    
    /**
     * 获取交易统计
     */
    @GetMapping("/statistics")
    public Map<String, Object> getStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        // 从服务获取统计
        result.put("summary", paperTradingService.getStatistics());
        result.put("totalTrades", paperTradingService.getTotalTrades());
        result.put("winTrades", paperTradingService.getWinTrades());
        result.put("lossTrades", paperTradingService.getLossTrades());
        result.put("totalProfit", paperTradingService.getTotalProfit());
        
        double winRate = paperTradingService.getTotalTrades() > 0 
                ? (double) paperTradingService.getWinTrades() / paperTradingService.getTotalTrades() * 100 
                : 0;
        result.put("winRate", winRate);
        
        // 从数据库获取详细统计
        List<TradeOrder> closedTrades = tradeOrderMapper.selectList(null).stream()
                .filter(order -> "AggressiveML".equals(order.getStrategyName()))
                .filter(order -> order.getStatus() != null && order.getStatus().startsWith("CLOSED"))
                .collect(Collectors.toList());
        
        long takeProfitCount = closedTrades.stream()
                .filter(order -> "CLOSED_TAKE_PROFIT".equals(order.getStatus()))
                .count();
        
        long stopLossCount = closedTrades.stream()
                .filter(order -> "CLOSED_STOP_LOSS".equals(order.getStatus()))
                .count();
        
        long signalReversalCount = closedTrades.stream()
                .filter(order -> "CLOSED_SIGNAL_REVERSAL".equals(order.getStatus()))
                .count();
        
        double totalPnlFromDb = closedTrades.stream()
                .map(TradeOrder::getProfitLoss)
                .filter(pnl -> pnl != null)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();
        
        result.put("takeProfitCount", takeProfitCount);
        result.put("stopLossCount", stopLossCount);
        result.put("signalReversalCount", signalReversalCount);
        result.put("totalPnlFromDb", totalPnlFromDb);
        
        return result;
    }
    
    /**
     * 获取交易明细（格式化显示）
     */
    @GetMapping("/details")
    public Map<String, Object> getTradeDetails() {
        List<TradeOrder> trades = tradeOrderMapper.selectList(null).stream()
                .filter(order -> "AggressiveML".equals(order.getStrategyName()))
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .limit(50)
                .collect(Collectors.toList());
        
        List<Map<String, Object>> formattedTrades = trades.stream()
                .map(order -> {
                    Map<String, Object> trade = new HashMap<>();
                    trade.put("订单号", order.getOrderNo());
                    trade.put("方向", "BUY".equals(order.getSide()) ? "做多" : "做空");
                    trade.put("入场价", order.getPrice());
                    trade.put("止损价", order.getStopLossPrice());
                    trade.put("止盈价", order.getTakeProfitPrice());
                    trade.put("盈亏", order.getProfitLoss());
                    trade.put("状态", formatStatus(order.getStatus()));
                    trade.put("备注", order.getRemark());
                    trade.put("开仓时间", order.getCreateTime());
                    trade.put("平仓时间", order.getUpdateTime());
                    return trade;
                })
                .collect(Collectors.toList());
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", formattedTrades.size());
        result.put("trades", formattedTrades);
        result.put("statistics", paperTradingService.getStatistics());
        
        return result;
    }
    
    /**
     * 格式化状态
     */
    private String formatStatus(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "OPEN": return "持仓中";
            case "CLOSED_TAKE_PROFIT": return "止盈平仓";
            case "CLOSED_STOP_LOSS": return "止损平仓";
            case "CLOSED_SIGNAL_REVERSAL": return "信号反转平仓";
            default: return status;
        }
    }
}
