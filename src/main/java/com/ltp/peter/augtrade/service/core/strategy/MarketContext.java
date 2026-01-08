package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 市场上下文
 * 包含策略生成信号所需的所有数据
 * 
 * @author Peter Wang
 */
@Data
@Builder
public class MarketContext {
    
    /**
     * 交易品种
     */
    private String symbol;
    
    /**
     * K线数据列表
     */
    private List<Kline> klines;
    
    /**
     * 当前价格
     */
    private BigDecimal currentPrice;
    
    /**
     * 技术指标值
     * key: 指标名称（如：RSI, MACD, ADX）
     * value: 指标值
     */
    @Builder.Default
    private Map<String, Object> indicators = new HashMap<>();
    
    /**
     * ML预测值
     */
    private Double mlPrediction;
    
    /**
     * K线形态识别结果
     */
    private String candlePattern;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    /**
     * 添加指标值
     */
    public void addIndicator(String name, Object value) {
        indicators.put(name, value);
    }
    
    /**
     * 获取指标值
     */
    public <T> T getIndicator(String name, Class<T> type) {
        Object value = indicators.get(name);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }
    
    /**
     * 获取Double类型指标
     */
    public Double getIndicatorAsDouble(String name) {
        return getIndicator(name, Double.class);
    }
    
    /**
     * 获取最新K线
     */
    public Kline getLatestKline() {
        if (klines == null || klines.isEmpty()) {
            return null;
        }
        return klines.get(klines.size() - 1);
    }
}
