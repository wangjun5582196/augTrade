package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.trading.broker.LiveTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 实盘交易控制器
 * 
 * ⚠️ 重要提示：
 * 1. 此控制器用于实盘交易，请务必谨慎操作
 * 2. 建议添加身份认证和权限控制
 * 3. 所有操作都有详细日志记录
 * 4. 支持飞书通知推送
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@RestController
@RequestMapping("/live-trading")
public class LiveTradingController {
    
    @Autowired
    private LiveTradingService liveTradingService;
    
    /**
     * 获取实盘状态
     * 
     * GET /api/live-trading/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean liveModeEnabled = liveTradingService.isLiveModeEnabled();
            boolean connected = liveTradingService.isConnected();
            
            result.put("success", true);
            result.put("liveModeEnabled", liveModeEnabled);
            result.put("connected", connected);
            result.put("status", liveModeEnabled ? "实盘模式已启用" : "实盘模式未启用");
            
            if (liveModeEnabled && connected) {
                try {
                    BigDecimal balance = liveTradingService.getAccountBalance();
                    result.put("balance", balance);
                } catch (Exception e) {
                    result.put("balanceError", e.getMessage());
                }
            }
            
            log.info("实盘状态查询 - 启用: {}, 连接: {}", liveModeEnabled, connected);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取状态失败: " + e.getMessage());
            log.error("获取实盘状态失败", e);
        }
        
        return result;
    }
    
    /**
     * 获取账户余额
     * 
     * GET /api/live-trading/balance
     */
    @GetMapping("/balance")
    public Map<String, Object> getBalance() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            BigDecimal balance = liveTradingService.getAccountBalance();
            
            result.put("success", true);
            result.put("balance", balance);
            result.put("currency", "USDT");
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("查询账户余额: ${}", balance);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取余额失败: " + e.getMessage());
            log.error("获取账户余额失败", e);
        }
        
        return result;
    }
    
    /**
     * 实盘买入
     * 
     * POST /api/live-trading/buy
     * 
     * 参数：
     * - symbol: 交易对（如：PAXGUSDT）
     * - quantity: 数量（如：0.01）
     * - strategy: 策略名称（可选，默认：手动交易）
     * 
     * ⚠️ 此接口会真实下单，请谨慎使用！
     */
    @PostMapping("/buy")
    public Map<String, Object> executeBuy(
            @RequestParam(required = false, defaultValue = "PAXGUSDT") String symbol,
            @RequestParam BigDecimal quantity,
            @RequestParam(required = false, defaultValue = "手动交易") String strategy) {
        
        Map<String, Object> result = new HashMap<>();
        
        log.warn("🔴 [实盘交易请求] 买入 - Symbol: {}, Qty: {}, Strategy: {}", symbol, quantity, strategy);
        
        try {
            // 参数验证
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                result.put("success", false);
                result.put("message", "数量必须大于0");
                return result;
            }
            
            // 执行实盘买入
            TradeOrder order = liveTradingService.executeLiveBuy(symbol, quantity, strategy);
            
            result.put("success", true);
            result.put("message", "实盘买入成功");
            result.put("order", order);
            
            log.info("✅ [实盘交易] 买入成功 - OrderNo: {}", order.getOrderNo());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "实盘买入失败: " + e.getMessage());
            log.error("❌ [实盘交易] 买入失败", e);
        }
        
        return result;
    }
    
    /**
     * 实盘卖出
     * 
     * POST /api/live-trading/sell
     * 
     * 参数：
     * - symbol: 交易对（如：PAXGUSDT）
     * - quantity: 数量（如：0.01）
     * - strategy: 策略名称（可选，默认：手动平仓）
     * 
     * ⚠️ 此接口会真实下单，请谨慎使用！
     */
    @PostMapping("/sell")
    public Map<String, Object> executeSell(
            @RequestParam(required = false, defaultValue = "PAXGUSDT") String symbol,
            @RequestParam BigDecimal quantity,
            @RequestParam(required = false, defaultValue = "手动平仓") String strategy) {
        
        Map<String, Object> result = new HashMap<>();
        
        log.warn("🔴 [实盘交易请求] 卖出 - Symbol: {}, Qty: {}, Strategy: {}", symbol, quantity, strategy);
        
        try {
            // 参数验证
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                result.put("success", false);
                result.put("message", "数量必须大于0");
                return result;
            }
            
            // 执行实盘卖出
            TradeOrder order = liveTradingService.executeLiveSell(symbol, quantity, strategy);
            
            result.put("success", true);
            result.put("message", "实盘卖出成功");
            result.put("order", order);
            
            log.info("✅ [实盘交易] 卖出成功 - OrderNo: {}, PnL: ${}", 
                    order.getOrderNo(), order.getProfitLoss());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "实盘卖出失败: " + e.getMessage());
            log.error("❌ [实盘交易] 卖出失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试连接
     * 
     * GET /api/live-trading/test-connection
     */
    @GetMapping("/test-connection")
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean connected = liveTradingService.isConnected();
            
            result.put("success", true);
            result.put("connected", connected);
            result.put("message", connected ? "连接正常" : "连接失败");
            
            log.info("测试币安连接 - 结果: {}", connected);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("connected", false);
            result.put("message", "测试连接失败: " + e.getMessage());
            log.error("测试币安连接失败", e);
        }
        
        return result;
    }
    
    /**
     * 健康检查
     * 
     * GET /api/live-trading/health
     */
    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "LiveTrading");
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }
}
