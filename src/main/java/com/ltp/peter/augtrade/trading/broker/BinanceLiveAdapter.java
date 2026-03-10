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
 * 币安实盘交易适配器
 * 
 * ⚠️ 注意事项:
 * 1. 确保已在币安开通现货交易权限
 * 2. API Key需要开通现货交易权限（不需要合约权限）
 * 3. 建议先在测试网测试: https://testnet.binance.vision/
 * 4. 实盘前请确保理解交易风险
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@Service("binanceLiveAdapter")
public class BinanceLiveAdapter implements BrokerAdapter {
    
    @Autowired
    private BinanceTradingService binanceService;
    
    @Value("${binance.gold.symbol:PAXGUSDT}")
    private String goldSymbol;
    
    @Value("${binance.api.enabled:false}")
    private boolean enabled;
    
    @Value("${binance.live.mode:false}")
    private boolean liveMode;
    
    @Value("${binance.live.max-order-amount:0.1}")
    private BigDecimal maxOrderAmount;
    
    /**
     * 获取当前市场价格
     */
    @Override
    public BigDecimal getCurrentPrice(String symbol) {
        try {
            if (!enabled) {
                throw new RuntimeException("币安服务未启用，请在application.yml中设置 binance.api.enabled=true");
            }
            
            // 将内部交易对映射到币安交易对
            String binanceSymbol = mapToBinanceSymbol(symbol);
            
            BigDecimal price = binanceService.getCurrentPrice(binanceSymbol);
            log.debug("获取币安价格 - Symbol: {}, Price: ${}", binanceSymbol, price);
            
            return price;
        } catch (Exception e) {
            log.error("获取币安价格失败 - Symbol: {}", symbol, e);
            throw new RuntimeException("获取价格失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 下市价单（实盘）
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
                log.warn("⚠️ 实盘模式未启用，拒绝下单。请设置 binance.live.mode=true");
                throw new RuntimeException("实盘模式未启用，无法下单");
            }
            
            // 订单金额限制检查
            if (request.getQuantity().compareTo(maxOrderAmount) > 0) {
                log.error("❌ 订单数量超过限制 - 请求: {}, 限制: {}", request.getQuantity(), maxOrderAmount);
                throw new RuntimeException(String.format("订单数量超过限制，最大允许: %s", maxOrderAmount));
            }
            
            // 将内部交易对映射到币安交易对
            String binanceSymbol = mapToBinanceSymbol(request.getSymbol());
            
            // 格式化数量（币安要求特定精度）
            String quantity = formatQuantity(request.getQuantity());
            
            log.warn("🔴 [实盘交易] 即将下单 - Symbol: {}, Side: {}, Qty: {}", 
                    binanceSymbol, request.getSide(), quantity);
            
            // 执行下单
            String orderId = binanceService.placeMarketOrder(
                binanceSymbol,
                request.getSide(),
                quantity
            );
            
            log.info("✅ [实盘交易] 下单成功 - OrderId: {}", orderId);
            
            return orderId;
            
        } catch (Exception e) {
            log.error("❌ [实盘交易] 下单失败", e);
            throw new RuntimeException("下单失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取当前持仓
     * 
     * 注意：币安现货不支持持仓概念，这里返回空列表
     * 持仓管理由系统内部的Position表维护
     */
    @Override
    public List<Position> getOpenPositions(String symbol) {
        log.debug("币安现货模式，持仓由系统内部维护");
        return new ArrayList<>();
    }
    
    /**
     * 平仓
     * 
     * 注意：币安现货不支持直接平仓，需要手动下反向订单
     * 这里返回false，由调用方处理
     */
    @Override
    public boolean closePosition(String positionId) {
        log.warn("币安现货不支持直接平仓，请使用反向下单");
        return false;
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
            
            var accountInfo = binanceService.getAccountBalance();
            
            // 提取USDT余额
            if (accountInfo.has("balances")) {
                var balances = accountInfo.getAsJsonArray("balances");
                for (var i = 0; i < balances.size(); i++) {
                    var balance = balances.get(i).getAsJsonObject();
                    if ("USDT".equals(balance.get("asset").getAsString())) {
                        String free = balance.get("free").getAsString();
                        BigDecimal usdtBalance = new BigDecimal(free);
                        log.info("币安账户USDT余额: ${}", usdtBalance);
                        return usdtBalance;
                    }
                }
            }
            
            log.warn("未找到USDT余额");
            return BigDecimal.ZERO;
            
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
        return liveMode ? "Binance-Live" : "Binance-ReadOnly";
    }
    
    /**
     * 检查连接状态
     */
    @Override
    public boolean isConnected() {
        if (!enabled) {
            return false;
        }
        return binanceService.testConnection();
    }
    
    /**
     * 将内部交易对映射到币安交易对
     * 
     * @param symbol 内部交易对（如：XAUUSD, BTCUSDT等）
     * @return 币安交易对
     */
    private String mapToBinanceSymbol(String symbol) {
        // 如果是黄金交易对，使用配置的交易对
        if (symbol.contains("XAU") || symbol.contains("GOLD")) {
            return goldSymbol;
        }
        
        // 其他交易对直接返回
        return symbol;
    }
    
    /**
     * 格式化数量（币安精度要求）
     * 
     * PAXGUSDT: 最小交易量0.001，精度3位小数
     */
    private String formatQuantity(BigDecimal quantity) {
        // 四舍五入到3位小数
        BigDecimal formatted = quantity.setScale(3, RoundingMode.DOWN);
        return formatted.toPlainString();
    }
}
