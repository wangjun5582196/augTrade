package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.service.FeishuNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书通知测试控制器
 * 
 * 用于测试飞书通知功能是否正常工作
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/feishu/test")
public class FeishuTestController {
    
    @Autowired
    private FeishuNotificationService feishuNotificationService;
    
    /**
     * 测试基础通知
     * 
     * 访问: http://localhost:3131/api/feishu/test/basic
     */
    @GetMapping("/basic")
    public Map<String, Object> testBasicNotification() {
        log.info("=== 开始测试飞书基础通知 ===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.testNotification();
            
            result.put("success", true);
            result.put("message", "测试通知已发送");
            result.put("tip", "请检查飞书群是否收到消息");
            
            log.info("✅ 测试通知发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            
            log.error("❌ 测试通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试开仓通知
     * 
     * 访问: http://localhost:3131/api/feishu/test/open-long
     */
    @GetMapping("/open-long")
    public Map<String, Object> testOpenLongNotification() {
        log.info("=== 开始测试开仓通知（做多）===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.notifyOpenPosition(
                "XAUUSDT",
                "LONG",
                new BigDecimal("2678.50"),
                new BigDecimal("0.01"),
                new BigDecimal("2653.50"),
                new BigDecimal("2758.50"),
                "测试策略"
            );
            
            result.put("success", true);
            result.put("message", "开仓通知（做多）已发送");
            result.put("details", Map.of(
                "symbol", "XAUUSDT",
                "side", "LONG",
                "entryPrice", "$2678.50",
                "quantity", "0.01",
                "stopLoss", "$2653.50",
                "takeProfit", "$2758.50"
            ));
            
            log.info("✅ 开仓通知（做多）发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            
            log.error("❌ 开仓通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试开仓通知（做空）
     * 
     * 访问: http://localhost:3131/api/feishu/test/open-short
     */
    @GetMapping("/open-short")
    public Map<String, Object> testOpenShortNotification() {
        log.info("=== 开始测试开仓通知（做空）===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.notifyOpenPosition(
                "XAUUSDT",
                "SHORT",
                new BigDecimal("2678.50"),
                new BigDecimal("0.01"),
                new BigDecimal("2703.50"),
                new BigDecimal("2598.50"),
                "测试策略"
            );
            
            result.put("success", true);
            result.put("message", "开仓通知（做空）已发送");
            result.put("details", Map.of(
                "symbol", "XAUUSDT",
                "side", "SHORT",
                "entryPrice", "$2678.50",
                "quantity", "0.01",
                "stopLoss", "$2703.50",
                "takeProfit", "$2598.50"
            ));
            
            log.info("✅ 开仓通知（做空）发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            
            log.error("❌ 开仓通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试止盈通知
     * 
     * 访问: http://localhost:3131/api/feishu/test/take-profit
     */
    @GetMapping("/take-profit")
    public Map<String, Object> testTakeProfitNotification() {
        log.info("=== 开始测试止盈通知 ===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.notifyStopLossOrTakeProfit(
                "XAUUSDT",
                "LONG",
                new BigDecimal("2678.50"),
                new BigDecimal("2758.50"),
                new BigDecimal("0.01"),
                new BigDecimal("80.00"),
                "止盈"
            );
            
            result.put("success", true);
            result.put("message", "止盈通知已发送");
            result.put("details", Map.of(
                "entryPrice", "$2678.50",
                "exitPrice", "$2758.50",
                "profit", "$80.00"
            ));
            
            log.info("✅ 止盈通知发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            
            log.error("❌ 止盈通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试止损通知
     * 
     * 访问: http://localhost:3131/api/feishu/test/stop-loss
     */
    @GetMapping("/stop-loss")
    public Map<String, Object> testStopLossNotification() {
        log.info("=== 开始测试止损通知 ===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.notifyStopLossOrTakeProfit(
                "XAUUSDT",
                "LONG",
                new BigDecimal("2678.50"),
                new BigDecimal("2653.50"),
                new BigDecimal("0.01"),
                new BigDecimal("-25.00"),
                "止损"
            );
            
            result.put("success", true);
            result.put("message", "止损通知已发送");
            result.put("details", Map.of(
                "entryPrice", "$2678.50",
                "exitPrice", "$2653.50",
                "loss", "$-25.00"
            ));
            
            log.info("✅ 止损通知发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            
            log.error("❌ 止损通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试信号反转通知
     * 
     * 访问: http://localhost:3131/api/feishu/test/signal-reversal
     */
    @GetMapping("/signal-reversal")
    public Map<String, Object> testSignalReversalNotification() {
        log.info("=== 开始测试信号反转通知 ===");
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            feishuNotificationService.notifySignalReversalClose(
                "XAUUSDT",
                "LONG",
                new BigDecimal("2678.50"),
                new BigDecimal("2690.20"),
                new BigDecimal("11.70")
            );
            
            result.put("success", true);
            result.put("message", "信号反转通知已发送");
            result.put("details", Map.of(
                "entryPrice", "$2678.50",
                "exitPrice", "$2690.20",
                "profit", "$11.70"
            ));
            
            log.info("✅ 信号反转通知发送完成");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            
            log.error("❌ 信号反转通知发送失败", e);
        }
        
        return result;
    }
    
    /**
     * 测试所有通知类型
     * 
     * 访问: http://localhost:3131/api/feishu/test/all
     */
    @GetMapping("/all")
    public Map<String, Object> testAllNotifications() {
        log.info("=== 开始测试所有通知类型 ===");
        
        Map<String, Object> result = new HashMap<>();
        Map<String, String> testResults = new HashMap<>();
        
        // 1. 测试基础通知
        try {
            feishuNotificationService.testNotification();
            testResults.put("1_basic", "✅ 成功");
            Thread.sleep(2000);
        } catch (Exception e) {
            testResults.put("1_basic", "❌ 失败: " + e.getMessage());
        }
        
        // 2. 测试开仓（做多）
        try {
            feishuNotificationService.notifyOpenPosition(
                "XAUUSDT", "LONG", new BigDecimal("2678.50"), new BigDecimal("0.01"),
                new BigDecimal("2653.50"), new BigDecimal("2758.50"), "测试策略"
            );
            testResults.put("2_open_long", "✅ 成功");
            Thread.sleep(2000);
        } catch (Exception e) {
            testResults.put("2_open_long", "❌ 失败: " + e.getMessage());
        }
        
        // 3. 测试开仓（做空）
        try {
            feishuNotificationService.notifyOpenPosition(
                "XAUUSDT", "SHORT", new BigDecimal("2678.50"), new BigDecimal("0.01"),
                new BigDecimal("2703.50"), new BigDecimal("2598.50"), "测试策略"
            );
            testResults.put("3_open_short", "✅ 成功");
            Thread.sleep(2000);
        } catch (Exception e) {
            testResults.put("3_open_short", "❌ 失败: " + e.getMessage());
        }
        
        // 4. 测试止盈
        try {
            feishuNotificationService.notifyStopLossOrTakeProfit(
                "XAUUSDT", "LONG", new BigDecimal("2678.50"), new BigDecimal("2758.50"),
                new BigDecimal("0.01"), new BigDecimal("80.00"), "止盈"
            );
            testResults.put("4_take_profit", "✅ 成功");
            Thread.sleep(2000);
        } catch (Exception e) {
            testResults.put("4_take_profit", "❌ 失败: " + e.getMessage());
        }
        
        // 5. 测试止损
        try {
            feishuNotificationService.notifyStopLossOrTakeProfit(
                "XAUUSDT", "LONG", new BigDecimal("2678.50"), new BigDecimal("2653.50"),
                new BigDecimal("0.01"), new BigDecimal("-25.00"), "止损"
            );
            testResults.put("5_stop_loss", "✅ 成功");
            Thread.sleep(2000);
        } catch (Exception e) {
            testResults.put("5_stop_loss", "❌ 失败: " + e.getMessage());
        }
        
        // 6. 测试信号反转
        try {
            feishuNotificationService.notifySignalReversalClose(
                "XAUUSDT", "LONG", new BigDecimal("2678.50"), new BigDecimal("2690.20"),
                new BigDecimal("11.70")
            );
            testResults.put("6_signal_reversal", "✅ 成功");
        } catch (Exception e) {
            testResults.put("6_signal_reversal", "❌ 失败: " + e.getMessage());
        }
        
        result.put("success", true);
        result.put("message", "所有测试已完成");
        result.put("results", testResults);
        result.put("tip", "请检查飞书群，应该收到6条测试消息");
        
        log.info("✅ 所有测试完成");
        log.info("测试结果: {}", testResults);
        
        return result;
    }
    
    /**
     * 获取配置状态
     * 
     * 访问: http://localhost:3131/api/feishu/test/status
     */
    @GetMapping("/status")
    public Map<String, Object> getConfigStatus() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 通过反射获取配置状态
            java.lang.reflect.Field enabledField = feishuNotificationService.getClass().getDeclaredField("enabled");
            enabledField.setAccessible(true);
            boolean enabled = (boolean) enabledField.get(feishuNotificationService);
            
            java.lang.reflect.Field webhookField = feishuNotificationService.getClass().getDeclaredField("webhookUrl");
            webhookField.setAccessible(true);
            String webhookUrl = (String) webhookField.get(feishuNotificationService);
            
            result.put("enabled", enabled);
            result.put("webhookConfigured", webhookUrl != null && !webhookUrl.isEmpty());
            result.put("webhookUrlMask", webhookUrl != null && !webhookUrl.isEmpty() ? 
                    webhookUrl.substring(0, Math.min(50, webhookUrl.length())) + "..." : "未配置");
            
            if (!enabled) {
                result.put("warning", "飞书通知未启用，请在application.yml中设置 feishu.notification.enabled=true");
            }
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                result.put("warning", "Webhook地址未配置，请在application.yml中设置 feishu.webhook.url");
            }
            
            result.put("ready", enabled && webhookUrl != null && !webhookUrl.isEmpty());
            
        } catch (Exception e) {
            result.put("error", "获取配置状态失败: " + e.getMessage());
        }
        
        return result;
    }
}
