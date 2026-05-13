-- ===================================================================
-- 添加信号追踪字段
-- 版本: V1.5
-- 创建时间: 2026-03-09
-- 目的: 记录每次下单时的完整信号数据，用于后续分析优化
-- ===================================================================

-- 1. 添加信号评分字段
ALTER TABLE t_trade_order ADD COLUMN buy_score INT DEFAULT 0 COMMENT '做多信号总分';
ALTER TABLE t_trade_order ADD COLUMN sell_score INT DEFAULT 0 COMMENT '做空信号总分';
ALTER TABLE t_trade_order ADD COLUMN signal_reasons TEXT COMMENT '信号理由列表(JSON格式)';

-- 2. 添加动量指标字段
ALTER TABLE t_trade_order ADD COLUMN momentum2 DECIMAL(20,8) COMMENT '2根K线动量';
ALTER TABLE t_trade_order ADD COLUMN momentum5 DECIMAL(20,8) COMMENT '5根K线动量';

-- 3. 添加成交量指标字段
ALTER TABLE t_trade_order ADD COLUMN volume_ratio DECIMAL(10,4) COMMENT '成交量比率(当前/20周期平均)';
ALTER TABLE t_trade_order ADD COLUMN current_volume DECIMAL(20,8) COMMENT '当前成交量';
ALTER TABLE t_trade_order ADD COLUMN avg_volume DECIMAL(20,8) COMMENT '20周期平均成交量';

-- 4. 添加摆动点字段
ALTER TABLE t_trade_order ADD COLUMN swing_high DECIMAL(20,2) COMMENT '最近摆动高点';
ALTER TABLE t_trade_order ADD COLUMN swing_low DECIMAL(20,2) COMMENT '最近摆动低点';
ALTER TABLE t_trade_order ADD COLUMN swing_high_distance DECIMAL(20,2) COMMENT '价格距离摆动高点';
ALTER TABLE t_trade_order ADD COLUMN swing_low_distance DECIMAL(20,2) COMMENT '价格距离摆动低点';

-- 5. 添加HMA指标字段
ALTER TABLE t_trade_order ADD COLUMN hma20 DECIMAL(20,2) COMMENT 'Hull Moving Average 20';
ALTER TABLE t_trade_order ADD COLUMN hma_slope DECIMAL(10,6) COMMENT 'HMA斜率(判断趋势强度)';
ALTER TABLE t_trade_order ADD COLUMN hma_trend VARCHAR(10) COMMENT 'HMA趋势方向(UP/DOWN/SIDEWAYS)';

-- 6. 添加当前市场状态快照
ALTER TABLE t_trade_order ADD COLUMN price_position VARCHAR(20) COMMENT '价格位置(ABOVE_SWING_HIGH/BELOW_SWING_LOW/BETWEEN)';
ALTER TABLE t_trade_order ADD COLUMN trend_confirmed TINYINT(1) DEFAULT 0 COMMENT '趋势是否确认(1=是,0=否)';

-- 7. 添加信号生成时间
ALTER TABLE t_trade_order ADD COLUMN signal_generate_time DATETIME COMMENT '信号生成时间';
ALTER TABLE t_trade_order ADD COLUMN signal_to_order_delay INT COMMENT '信号生成到下单的延迟(毫秒)';

-- 8. 为新字段添加索引（优化查询性能）
CREATE INDEX idx_buy_score ON t_trade_order(buy_score);
CREATE INDEX idx_sell_score ON t_trade_order(sell_score);
CREATE INDEX idx_volume_ratio ON t_trade_order(volume_ratio);
CREATE INDEX idx_signal_generate_time ON t_trade_order(signal_generate_time);
CREATE INDEX idx_price_position ON t_trade_order(price_position);

-- 9. 为历史数据填充默认值（可选）
UPDATE t_trade_order 
SET 
    buy_score = 0,
    sell_score = 0,
    signal_reasons = '{"note":"历史数据，无信号记录"}',
    trend_confirmed = 0
WHERE buy_score IS NULL;

-- 验证新字段
SELECT 
    COLUMN_NAME,
    COLUMN_TYPE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 't_trade_order'
    AND COLUMN_NAME IN (
        'buy_score', 'sell_score', 'signal_reasons',
        'momentum2', 'momentum5',
        'volume_ratio', 'current_volume', 'avg_volume',
        'swing_high', 'swing_low',
        'hma20', 'hma_slope', 'hma_trend',
        'price_position', 'trend_confirmed',
        'signal_generate_time', 'signal_to_order_delay'
    )
ORDER BY ORDINAL_POSITION;
