package com.ltp.peter.augtrade.service.infrastructure.broker;

import com.ltp.peter.augtrade.entity.Position;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易平台适配器接口
 * 统一不同交易平台的API调用
 * 
 * @author Peter Wang
 */
public interface BrokerAdapter {
    
    /**
     * 获取当前市场价格
     * 
     * @param symbol 交易品种（如：XAUTUSDT）
     * @return 当前价格
     */
    BigDecimal getCurrentPrice(String symbol);
    
    /**
     * 下市价单
     * 
     * @param request 订单请求
     * @return 订单ID
     */
    String placeMarketOrder(OrderRequest request);
    
    /**
     * 获取当前持仓
     * 
     * @param symbol 交易品种
     * @return 持仓列表
     */
    List<Position> getOpenPositions(String symbol);
    
    /**
     * 平仓
     * 
     * @param positionId 持仓ID
     * @return 是否成功
     */
    boolean closePosition(String positionId);
    
    /**
     * 获取账户余额
     * 
     * @return 账户余额
     */
    BigDecimal getAccountBalance();
    
    /**
     * 获取适配器名称
     * 
     * @return 适配器名称（如：Bybit、Paper）
     */
    String getAdapterName();
    
    /**
     * 检查连接状态
     * 
     * @return 是否连接正常
     */
    boolean isConnected();
}
