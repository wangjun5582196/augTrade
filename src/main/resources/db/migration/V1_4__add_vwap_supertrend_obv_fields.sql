-- 🔥 新增-20260213：为交易订单表添加VWAP/Supertrend/OBV指标字段
-- 用于日内短线交易数据分析和策略优化

ALTER TABLE t_trade_order ADD COLUMN vwap DECIMAL(20,2) DEFAULT NULL COMMENT 'VWAP成交量加权均价';
ALTER TABLE t_trade_order ADD COLUMN vwap_deviation DECIMAL(10,4) DEFAULT NULL COMMENT '价格偏离VWAP百分比';
ALTER TABLE t_trade_order ADD COLUMN supertrend_value DECIMAL(20,2) DEFAULT NULL COMMENT 'Supertrend线的值';
ALTER TABLE t_trade_order ADD COLUMN supertrend_direction VARCHAR(10) DEFAULT NULL COMMENT 'Supertrend趋势方向:UP/DOWN';
ALTER TABLE t_trade_order ADD COLUMN obv_trend DECIMAL(20,2) DEFAULT NULL COMMENT 'OBV趋势方向(正=看涨,负=看跌)';
ALTER TABLE t_trade_order ADD COLUMN obv_volume_confirmed TINYINT DEFAULT NULL COMMENT 'OBV量价确认:1=确认,0=未确认';

-- 添加索引（用于后续数据分析）
ALTER TABLE t_trade_order ADD INDEX idx_supertrend_direction (supertrend_direction);
ALTER TABLE t_trade_order ADD INDEX idx_vwap (vwap);
