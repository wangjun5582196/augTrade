package com.ltp.peter.augtrade.trading.broker;

import com.ltp.peter.augtrade.entity.Position;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 币安合约交易适配器（XAUUSDT永续合约）
 * 
 * ⚠️ 注意事项:
 * 1. 使用币安U本位合约API（USDT保证金）
 * 2. XAUUSDT是TradeFi永续合约，支持双向持仓
 * 3. API Key需要开通合约交易权限
 * 4. 支持杠杆交易，请谨慎使用
 * 
 * API文档: https://binance-docs.github.io/apidocs/futures/cn/
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@Service("binanceFuturesAdapter")
public class BinanceFuturesAdapter implements BrokerAdapter {
    
    @Autowired
    private BinanceFuturesTradingService futuresService;
    
    @Value("${binance.futures.symbol:XAUUSDT}")
    private String symbol;
    
    @Value("${binance.api.enabled:false}")
    private boolean enabled;
    
    @Value("${binance.futures.live-mode:false}")
    private boolean liveMode;
    
    @Value("${binance.futures.max-order-amount:10}")
    private BigDecimal maxOrderAmount;
    
    @Value("${binance.futures.leverage:2}")
    private int leverage;
    
    /**
     * 获取当前市场价格
     */
    @Override
    public BigDecimal getCurrentPrice(String tradingSymbol) {
        try {
            if (!enabled) {
                throw new RuntimeException("币安服务未启用");
            }
            
            // 使用配置的XAUUSDT交易对
            BigDecimal price = futuresService.getCurrentPrice(symbol);
            log.debug("获取币安合约价格 - Symbol: {}, Price: ${}", symbol, price);
            
            return price;
        } catch (Exception e) {
            log.error("获取币安合约价格失败 - Symbol: {}", symbol, e);
            throw new RuntimeException("获取价格失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 下市价单（合约交易）
     * 
     * ⚠️ 实盘交易！请谨慎使用！
     */
    @Override
    public String placeMarketOrder(OrderRequest request) {
        try {
            // 安全检查
            if (!enabled) {
                throw new RuntimeException("币安服务未启用");
            }
            
            if (!liveMode) {
                log.warn("⚠️ 合约实盘模式未启用，拒绝下单。请设置 binance.futures.live-mode=true");
                throw new RuntimeException("合约实盘模式未启用，无法下单");
            }
            
            // 订单金额限制检查
            if (request.getQuantity().compareTo(maxOrderAmount) > 0) {
                log.error("❌ 订单数量超过限制 - 请求: {}, 限制: {}", request.getQuantity(), maxOrderAmount);
                throw new RuntimeException(String.format("订单数量超过限制，最大允许: %s", maxOrderAmount));
            }
            
            // 格式化数量（XAUUSDT精度：整数）
            String quantity = formatQuantity(request.getQuantity());
            
            // 判断开仓/平仓
            String side = request.getSide(); // BUY/SELL
            String positionSide = determinePositionSide(side, request);
            
            log.warn("🔴 [合约实盘] 即将下单 - Symbol: {}, Side: {}, PositionSide: {}, Qty: {}", 
                    symbol, side, positionSide, quantity);
            
            // 执行下单
            String orderId = futuresService.placeMarketOrder(
                symbol,
                side,
                positionSide,
                quantity,
                leverage
            );
            
            log.info("✅ [合约实盘] 下单成功 - OrderId: {}", orderId);
            
            return orderId;
            
        } catch (Exception e) {
            log.error("❌ [合约实盘] 下单失败", e);
            throw new RuntimeException("下单失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取当前持仓
     */
    @Override
    public List<Position> getOpenPositions(String tradingSymbol) {
        try {
            if (!enabled) {
                throw new RuntimeException("币安服务未启用");
            }
            
            return futuresService.getOpenPositions(symbol);
            
        } catch (Exception e) {
            log.error("获取持仓失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 平仓
     */
    @Override
    public boolean closePosition(String positionId) {
        try {
            if (!enabled) {
                throw new RuntimeException("币安服务未启用");
            }
            
            if (!liveMode) {
                throw new RuntimeException("合约实盘模式未启用");
            }
            
            return futuresService.closeAllPositions(symbol);
            
        } catch (Exception e) {
            log.error("平仓失败", e);
            return false;
        }
    }
    
    /**
     * 获取账户余额
     */
    @Override
    public BigDecimal getAccountBalance() {
        try {
            if (!enabled) {
                throw new RuntimeException("币安服务未启用");
            }
            
            return futuresService.getAccountBalance();
            
        } catch (Exception e) {
            log.error("获取账户余额失败", e);
            throw new RuntimeException("获取余额失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取适配器名称
     */
    @Override
    public String getAdapterName() {
        return liveMode ? "Binance-Futures-Live" : "Binance-Futures-ReadOnly";
    }
    
    /**
     * 检查连接状态
     */
    @Override
    public boolean isConnected() {
        if (!enabled) {
            return false;
        }
        return futuresService.testConnection();
    }
    
    /**
     * 判断持仓方向
     * 
     * 币安合约支持双向持仓：
     * - LONG: 做多持仓
     * - SHORT: 做空持仓
     */
    private String determinePositionSide(String side, OrderRequest request) {
        // 如果request中指定了持仓方向，使用指定的
        if (request.getPositionSide() != null) {
            return request.getPositionSide();
        }
        
        // 默认逻辑：BUY=开多/平空，SELL=开空/平多
        // 这里简化处理，实际应该根据当前持仓判断
        return "BUY".equals(side) ? "LONG" : "SHORT";
    }
    
    /**
     * 格式化数量（币安合约精度要求）
     * 
     * XAUUSDT: 最小交易量1，精度0位小数（整数）
     */
    private String formatQuantity(BigDecimal quantity) {
        // 四舍五入到整数
        BigDecimal formatted = quantity.setScale(0, RoundingMode.DOWN);
        return formatted.toPlainString();
    }
}
