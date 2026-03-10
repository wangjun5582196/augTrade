package com.ltp.peter.augtrade.trading.broker;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.notification.FeishuNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 实盘交易服务
 * 
 * 功能：
 * 1. 统一管理实盘交易
 * 2. 交易前安全检查
 * 3. 订单记录与持仓管理
 * 4. 风险控制
 * 5. 飞书通知
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@Service
public class LiveTradingService {
    
    @Autowired
    @Qualifier("binanceLiveAdapter")
    private BrokerAdapter binanceAdapter;
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private PositionMapper positionMapper;
    
    @Autowired(required = false)
    private FeishuNotificationService feishuService;
    
    @Value("${binance.live.mode:false}")
    private boolean liveMode;
    
    @Value("${binance.live.max-order-amount:0.1}")
    private BigDecimal maxOrderAmount;
    
    @Value("${binance.live.max-daily-trades:20}")
    private int maxDailyTrades;
    
    @Value("${binance.live.max-daily-loss:500.0}")
    private BigDecimal maxDailyLoss;
    
    @Value("${binance.gold.symbol:PAXGUSDT}")
    private String goldSymbol;
    
    /**
     * 执行实盘买入
     * 
     * @param symbol 交易对
     * @param quantity 数量
     * @param strategyName 策略名称
     * @return 订单对象
     */
    @Transactional
    public TradeOrder executeLiveBuy(String symbol, BigDecimal quantity, String strategyName) {
        log.info("🔴 [实盘] 准备执行买入 - Symbol: {}, Qty: {}, Strategy: {}", symbol, quantity, strategyName);
        
        try {
            // 1. 安全检查
            validateLiveTrading();
            validateOrderAmount(quantity);
            checkDailyLimits();
            
            // 2. 获取当前价格
            BigDecimal currentPrice = binanceAdapter.getCurrentPrice(symbol);
            log.info("当前市场价格: ${}", currentPrice);
            
            // 3. 二次确认
            if (!confirmTrade("BUY", symbol, quantity, currentPrice)) {
                throw new RuntimeException("交易确认失败");
            }
            
            // 4. 执行下单
            OrderRequest request = OrderRequest.builder()
                    .symbol(symbol)
                    .side("BUY")
                    .quantity(quantity)
                    .build();
            
            String orderId = binanceAdapter.placeMarketOrder(request);
            log.info("✅ [实盘] 币安下单成功 - OrderId: {}", orderId);
            
            // 5. 记录订单到数据库
            TradeOrder order = createTradeOrder(
                symbol, "BUY", currentPrice, quantity, orderId, strategyName, "FILLED"
            );
            tradeOrderMapper.insert(order);
            
            // 6. 创建持仓记录
            Position position = createPosition(
                symbol, "LONG", currentPrice, quantity
            );
            positionMapper.insert(position);
            
            // 7. 发送飞书通知
            sendTradeNotification("买入", symbol, quantity, currentPrice, orderId);
            
            log.info("✅ [实盘] 买入交易完成 - OrderNo: {}", order.getOrderNo());
            return order;
            
        } catch (Exception e) {
            log.error("❌ [实盘] 买入交易失败", e);
            sendErrorNotification("买入失败", e.getMessage());
            throw new RuntimeException("实盘买入失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 执行实盘卖出
     * 
     * @param symbol 交易对
     * @param quantity 数量
     * @param strategyName 策略名称
     * @return 订单对象
     */
    @Transactional
    public TradeOrder executeLiveSell(String symbol, BigDecimal quantity, String strategyName) {
        log.info("🔴 [实盘] 准备执行卖出 - Symbol: {}, Qty: {}, Strategy: {}", symbol, quantity, strategyName);
        
        try {
            // 1. 安全检查
            validateLiveTrading();
            validateOrderAmount(quantity);
            
            // 2. 检查持仓
            Position position = getOpenPosition(symbol, "LONG");
            if (position == null) {
                throw new RuntimeException("没有可平仓的持仓");
            }
            
            // 3. 获取当前价格
            BigDecimal currentPrice = binanceAdapter.getCurrentPrice(symbol);
            log.info("当前市场价格: ${}", currentPrice);
            
            // 4. 计算盈亏
            BigDecimal profitLoss = currentPrice.subtract(position.getAvgPrice())
                    .multiply(quantity);
            log.info("预计盈亏: ${}", profitLoss);
            
            // 5. 二次确认
            if (!confirmTrade("SELL", symbol, quantity, currentPrice)) {
                throw new RuntimeException("交易确认失败");
            }
            
            // 6. 执行下单
            OrderRequest request = OrderRequest.builder()
                    .symbol(symbol)
                    .side("SELL")
                    .quantity(quantity)
                    .build();
            
            String orderId = binanceAdapter.placeMarketOrder(request);
            log.info("✅ [实盘] 币安下单成功 - OrderId: {}", orderId);
            
            // 7. 记录订单到数据库
            TradeOrder order = createTradeOrder(
                symbol, "SELL", currentPrice, quantity, orderId, strategyName, "FILLED"
            );
            order.setProfitLoss(profitLoss);
            tradeOrderMapper.insert(order);
            
            // 8. 更新持仓状态
            position.setStatus("CLOSED");
            position.setCloseTime(LocalDateTime.now());
            position.setUpdateTime(LocalDateTime.now());
            positionMapper.updateById(position);
            
            // 9. 发送飞书通知
            sendTradeNotification("卖出", symbol, quantity, currentPrice, orderId, profitLoss);
            
            log.info("✅ [实盘] 卖出交易完成 - OrderNo: {}, PnL: ${}", order.getOrderNo(), profitLoss);
            return order;
            
        } catch (Exception e) {
            log.error("❌ [实盘] 卖出交易失败", e);
            sendErrorNotification("卖出失败", e.getMessage());
            throw new RuntimeException("实盘卖出失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 验证实盘交易是否启用
     */
    private void validateLiveTrading() {
        if (!liveMode) {
            throw new RuntimeException("实盘模式未启用，请设置 binance.live.mode=true");
        }
    }
    
    /**
     * 验证订单金额
     */
    private void validateOrderAmount(BigDecimal quantity) {
        if (quantity.compareTo(maxOrderAmount) > 0) {
            throw new RuntimeException(String.format(
                "订单数量超过限制，最大允许: %s", maxOrderAmount
            ));
        }
        
        if (quantity.compareTo(new BigDecimal("0.001")) < 0) {
            throw new RuntimeException("订单数量过小，最小: 0.001");
        }
    }
    
    /**
     * 检查每日交易限制
     */
    private void checkDailyLimits() {
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        
        // 检查今日交易次数
        long todayTradeCount = tradeOrderMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                .ge(TradeOrder::getCreateTime, today)
                .eq(TradeOrder::getStatus, "FILLED")
        );
        
        if (todayTradeCount >= maxDailyTrades) {
            throw new RuntimeException(String.format(
                "今日交易次数已达上限: %d/%d", todayTradeCount, maxDailyTrades
            ));
        }
        
        // 检查今日亏损
        BigDecimal todayLoss = calculateTodayLoss();
        if (todayLoss.abs().compareTo(maxDailyLoss) > 0) {
            throw new RuntimeException(String.format(
                "今日亏损已达上限: $%.2f (限制: $%.2f)", todayLoss, maxDailyLoss
            ));
        }
    }
    
    /**
     * 计算今日亏损
     */
    private BigDecimal calculateTodayLoss() {
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        
        var orders = tradeOrderMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                .ge(TradeOrder::getCreateTime, today)
                .eq(TradeOrder::getStatus, "FILLED")
                .isNotNull(TradeOrder::getProfitLoss)
        );
        
        return orders.stream()
            .map(TradeOrder::getProfitLoss)
            .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) < 0) // 只统计亏损
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * 二次确认交易
     */
    private boolean confirmTrade(String side, String symbol, BigDecimal quantity, BigDecimal price) {
        log.warn("⚠️ [实盘确认] {} - Symbol: {}, Qty: {}, Price: ${}", 
                side, symbol, quantity, price);
        
        BigDecimal totalAmount = price.multiply(quantity);
        log.warn("⚠️ [实盘确认] 预计成交金额: ${}", totalAmount);
        
        // 这里可以添加更多确认逻辑，如需要人工确认，可以集成审批流程
        return true;
    }
    
    /**
     * 创建交易订单记录
     */
    private TradeOrder createTradeOrder(String symbol, String side, BigDecimal price, 
                                       BigDecimal quantity, String externalOrderId,
                                       String strategyName, String status) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setSymbol(symbol);
        order.setOrderType("MARKET");
        order.setSide(side);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setExecutedPrice(price);
        order.setExecutedQuantity(quantity);
        order.setStatus(status);
        order.setStrategyName(strategyName);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setExecutedTime(LocalDateTime.now());
        
        // 保存币安订单ID到备注
        order.setRemark("Binance OrderId: " + externalOrderId);
        
        // 计算手续费 (0.1%)
        BigDecimal fee = price.multiply(quantity).multiply(new BigDecimal("0.001"));
        order.setFee(fee);
        
        return order;
    }
    
    /**
     * 创建持仓记录
     */
    private Position createPosition(String symbol, String direction, 
                                   BigDecimal price, BigDecimal quantity) {
        Position position = new Position();
        position.setSymbol(symbol);
        position.setDirection(direction);
        position.setQuantity(quantity);
        position.setAvgPrice(price);
        position.setCurrentPrice(price);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setMargin(price.multiply(quantity));
        position.setLeverage(1);
        position.setStatus("OPEN");
        position.setOpenTime(LocalDateTime.now());
        position.setCreateTime(LocalDateTime.now());
        position.setUpdateTime(LocalDateTime.now());
        
        return position;
    }
    
    /**
     * 获取开仓持仓
     */
    private Position getOpenPosition(String symbol, String direction) {
        return positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, direction)
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
    }
    
    /**
     * 生成订单号
     */
    private String generateOrderNo() {
        return "LIVE-" + System.currentTimeMillis() + "-" + 
               UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
    
    /**
     * 发送交易通知
     */
    private void sendTradeNotification(String action, String symbol, BigDecimal quantity, 
                                     BigDecimal price, String orderId) {
        sendTradeNotification(action, symbol, quantity, price, orderId, null);
    }
    
    /**
     * 发送交易通知（带盈亏）
     */
    private void sendTradeNotification(String action, String symbol, BigDecimal quantity, 
                                     BigDecimal price, String orderId, BigDecimal profitLoss) {
        if (feishuService == null) {
            return;
        }
        
        try {
            StringBuilder msg = new StringBuilder();
            msg.append("🔴 [实盘交易] ").append(action).append("\n\n");
            msg.append("交易对: ").append(symbol).append("\n");
            msg.append("数量: ").append(quantity).append("\n");
            msg.append("价格: $").append(price).append("\n");
            msg.append("订单号: ").append(orderId).append("\n");
            
            if (profitLoss != null) {
                String pnlEmoji = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "💰" : "📉";
                msg.append(pnlEmoji).append(" 盈亏: $").append(profitLoss).append("\n");
            }
            
            msg.append("\n⏰ ").append(LocalDateTime.now());
            
            // 简化通知：暂时使用日志记录
            log.info("飞书通知: {}", msg.toString());
        } catch (Exception e) {
            log.error("发送飞书通知失败", e);
        }
    }
    
    /**
     * 发送错误通知
     */
    private void sendErrorNotification(String title, String message) {
        if (feishuService == null) {
            return;
        }
        
        try {
            String msg = String.format("❌ [实盘错误] %s\n\n详情: %s\n\n⏰ %s", 
                    title, message, LocalDateTime.now());
            // 简化通知：暂时使用日志记录
            log.error("飞书通知: {}", msg);
        } catch (Exception e) {
            log.error("发送飞书通知失败", e);
        }
    }
    
    /**
     * 获取实盘状态
     */
    public boolean isLiveModeEnabled() {
        return liveMode;
    }
    
    /**
     * 获取适配器状态
     */
    public boolean isConnected() {
        return binanceAdapter.isConnected();
    }
    
    /**
     * 获取账户余额
     */
    public BigDecimal getAccountBalance() {
        return binanceAdapter.getAccountBalance();
    }
}
