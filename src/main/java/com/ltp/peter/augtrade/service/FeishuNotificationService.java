package com.ltp.peter.augtrade.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 飞书机器人通知服务
 * 
 * 用于在交易开仓、平仓时发送通知到飞书群
 * 
 * 配置方式：
 * 1. 在飞书群中添加自定义机器人
 * 2. 获取Webhook地址
 * 3. 在application.yml中配置webhook URL
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class FeishuNotificationService {
    
    @Value("${feishu.webhook.url:}")
    private String webhookUrl;
    
    @Value("${feishu.webhook.secret:}")
    private String webhookSecret;
    
    @Value("${feishu.notification.enabled:false}")
    private boolean enabled;
    
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 发送开仓通知
     * 
     * @param symbol 交易品种
     * @param side 方向（LONG/SHORT）
     * @param entryPrice 开仓价格
     * @param quantity 数量
     * @param stopLoss 止损价
     * @param takeProfit 止盈价
     * @param strategy 策略名称
     */
    public void notifyOpenPosition(String symbol, String side, BigDecimal entryPrice, 
                                    BigDecimal quantity, BigDecimal stopLoss, 
                                    BigDecimal takeProfit, String strategy) {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty()) {
            log.debug("飞书通知未启用或未配置Webhook");
            return;
        }
        
        try {
            String direction = "LONG".equals(side) ? "🔥 做多" : "📉 做空";
            String color = "LONG".equals(side) ? "green" : "red";
            
            String message = buildCardMessage(
                "💰 开仓通知",
                color,
                String.format(
                    "**交易品种**: %s\n" +
                    "**方向**: %s\n" +
                    "**开仓价**: $%s\n" +
                    "**数量**: %s\n" +
                    "**止损价**: $%s\n" +
                    "**止盈价**: $%s\n" +
                    "**策略**: %s\n" +
                    "**时间**: %s",
                    symbol,
                    direction,
                    entryPrice.toPlainString(),
                    quantity.toPlainString(),
                    stopLoss.toPlainString(),
                    takeProfit.toPlainString(),
                    strategy,
                    LocalDateTime.now().format(formatter)
                )
            );
            
            sendMessage(message);
            log.info("✅ 飞书开仓通知发送成功");
            
        } catch (Exception e) {
            log.error("❌ 飞书开仓通知发送失败", e);
        }
    }
    
    /**
     * 发送平仓通知
     * 
     * @param symbol 交易品种
     * @param side 方向
     * @param entryPrice 开仓价
     * @param exitPrice 平仓价
     * @param quantity 数量
     * @param profit 盈亏
     * @param holdingTime 持仓时间（秒）
     * @param reason 平仓原因
     */
    public void notifyClosePosition(String symbol, String side, BigDecimal entryPrice,
                                     BigDecimal exitPrice, BigDecimal quantity,
                                     BigDecimal profit, long holdingTime, String reason) {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty()) {
            log.debug("飞书通知未启用或未配置Webhook");
            return;
        }
        
        try {
            String direction = "LONG".equals(side) ? "做多" : "做空";
            boolean isProfit = profit.compareTo(BigDecimal.ZERO) > 0;
            String profitEmoji = isProfit ? "✅ 盈利" : "❌ 亏损";
            String color = isProfit ? "green" : "red";
            
            // 计算持仓时间
            long hours = holdingTime / 3600;
            long minutes = (holdingTime % 3600) / 60;
            long seconds = holdingTime % 60;
            String holdingTimeStr = String.format("%d小时%d分%d秒", hours, minutes, seconds);
            
            String message = buildCardMessage(
                "🔔 平仓通知",
                color,
                String.format(
                    "**交易品种**: %s\n" +
                    "**方向**: %s\n" +
                    "**开仓价**: $%s\n" +
                    "**平仓价**: $%s\n" +
                    "**数量**: %s\n" +
                    "**盈亏**: %s $%s\n" +
                    "**持仓时间**: %s\n" +
                    "**平仓原因**: %s\n" +
                    "**时间**: %s",
                    symbol,
                    direction,
                    entryPrice.toPlainString(),
                    exitPrice.toPlainString(),
                    quantity.toPlainString(),
                    profitEmoji,
                    profit.abs().toPlainString(),
                    holdingTimeStr,
                    reason,
                    LocalDateTime.now().format(formatter)
                )
            );
            
            sendMessage(message);
            log.info("✅ 飞书平仓通知发送成功");
            
        } catch (Exception e) {
            log.error("❌ 飞书平仓通知发送失败", e);
        }
    }
    
    /**
     * 发送止损/止盈通知
     * 
     * @param symbol 交易品种
     * @param side 方向
     * @param entryPrice 开仓价
     * @param exitPrice 平仓价
     * @param quantity 数量
     * @param profit 盈亏
     * @param type 类型（止损/止盈）
     */
    public void notifyStopLossOrTakeProfit(String symbol, String side, BigDecimal entryPrice,
                                            BigDecimal exitPrice, BigDecimal quantity,
                                            BigDecimal profit, String type) {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty()) {
            log.debug("飞书通知未启用或未配置Webhook");
            return;
        }
        
        try {
            String direction = "LONG".equals(side) ? "做多" : "做空";
            String emoji = "止盈".equals(type) ? "🎉" : "⚠️";
            String color = "止盈".equals(type) ? "green" : "orange";
            
            String message = buildCardMessage(
                emoji + " " + type + "通知",
                color,
                String.format(
                    "**交易品种**: %s\n" +
                    "**方向**: %s\n" +
                    "**开仓价**: $%s\n" +
                    "**平仓价**: $%s\n" +
                    "**数量**: %s\n" +
                    "**盈亏**: $%s\n" +
                    "**触发类型**: %s\n" +
                    "**时间**: %s",
                    symbol,
                    direction,
                    entryPrice.toPlainString(),
                    exitPrice.toPlainString(),
                    quantity.toPlainString(),
                    profit.toPlainString(),
                    type,
                    LocalDateTime.now().format(formatter)
                )
            );
            
            sendMessage(message);
            log.info("✅ 飞书{}通知发送成功", type);
            
        } catch (Exception e) {
            log.error("❌ 飞书{}通知发送失败", type, e);
        }
    }
    
    /**
     * 发送信号反转平仓通知
     * 
     * @param symbol 交易品种
     * @param side 原方向
     * @param entryPrice 开仓价
     * @param exitPrice 平仓价
     * @param profit 盈亏
     */
    public void notifySignalReversalClose(String symbol, String side, BigDecimal entryPrice,
                                           BigDecimal exitPrice, BigDecimal profit) {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty()) {
            log.debug("飞书通知未启用或未配置Webhook");
            return;
        }
        
        try {
            String direction = "LONG".equals(side) ? "做多" : "做空";
            boolean isProfit = profit.compareTo(BigDecimal.ZERO) > 0;
            String color = isProfit ? "green" : "orange";
            
            String message = buildCardMessage(
                "⚠️ 信号反转平仓",
                color,
                String.format(
                    "**交易品种**: %s\n" +
                    "**原方向**: %s\n" +
                    "**开仓价**: $%s\n" +
                    "**平仓价**: $%s\n" +
                    "**盈亏**: $%s\n" +
                    "**原因**: 策略信号反转\n" +
                    "**时间**: %s",
                    symbol,
                    direction,
                    entryPrice.toPlainString(),
                    exitPrice.toPlainString(),
                    profit.toPlainString(),
                    LocalDateTime.now().format(formatter)
                )
            );
            
            sendMessage(message);
            log.info("✅ 飞书信号反转通知发送成功");
            
        } catch (Exception e) {
            log.error("❌ 飞书信号反转通知发送失败", e);
        }
    }
    
    /**
     * 构建卡片消息
     */
    private String buildCardMessage(String title, String color, String content) {
        // 飞书卡片消息格式
        return String.format("""
            {
              "msg_type": "interactive",
              "card": {
                "config": {
                  "wide_screen_mode": true
                },
                "header": {
                  "title": {
                    "tag": "plain_text",
                    "content": "%s"
                  },
                  "template": "%s"
                },
                "elements": [
                  {
                    "tag": "markdown",
                    "content": "%s"
                  }
                ]
              }
            }
            """, title, color, content.replace("\n", "\\n"));
    }
    
    /**
     * 发送消息到飞书（支持签名校验）
     */
    private void sendMessage(String message) throws IOException, InterruptedException {
        String finalMessage = message;
        
        // 如果配置了签名密钥，添加签名
        if (webhookSecret != null && !webhookSecret.isEmpty()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = generateSign(timestamp, webhookSecret);
            
            // 在消息中添加timestamp和sign字段
            finalMessage = message.substring(0, message.lastIndexOf("}")) + 
                    String.format(",\"timestamp\":\"%d\",\"sign\":\"%s\"}", timestamp, sign);
            
            log.debug("添加签名校验: timestamp={}, sign={}", timestamp, sign);
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(finalMessage))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("飞书通知发送失败，状态码: {}, 响应: {}", response.statusCode(), response.body());
        } else {
            log.debug("飞书通知发送成功，响应: {}", response.body());
        }
    }
    
    /**
     * 生成飞书签名
     * 
     * @param timestamp 时间戳（秒）
     * @param secret 签名密钥
     * @return 签名字符串
     */
    private String generateSign(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            return java.util.Base64.getEncoder().encodeToString(signData);
            
        } catch (Exception e) {
            log.error("生成飞书签名失败", e);
            return "";
        }
    }
    
    /**
     * 测试通知
     */
    public void testNotification() {
        if (!enabled || webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("飞书通知未启用或未配置Webhook，无法测试");
            return;
        }
        
        try {
            String message = buildCardMessage(
                "🎯 测试通知",
                "blue",
                String.format(
                    "**系统**: AugTrade交易系统\n" +
                    "**状态**: 运行正常\n" +
                    "**飞书通知**: 配置成功\n" +
                    "**时间**: %s",
                    LocalDateTime.now().format(formatter)
                )
            );
            
            sendMessage(message);
            log.info("✅ 飞书测试通知发送成功");
            
        } catch (Exception e) {
            log.error("❌ 飞书测试通知发送失败", e);
        }
    }
}
