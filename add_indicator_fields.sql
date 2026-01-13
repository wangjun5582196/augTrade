-- ==========================================
-- 添加技术指标字段到t_trade_order表
-- 用于AI学习和策略优化
-- 执行时间: 2026-01-13
-- ==========================================

USE test;

-- 添加技术指标字段
ALTER TABLE t_trade_order
    -- Williams %R指标
    ADD COLUMN williams_r DECIMAL(10, 2) COMMENT 'Williams %R指标值(-100到0)',
    
    -- ADX趋势强度
    ADD COLUMN adx DECIMAL(10, 2) COMMENT 'ADX趋势强度指标值(0-100)',
    
    -- EMA均线
    ADD COLUMN ema20 DECIMAL(20, 2) COMMENT 'EMA20均线值',
    ADD COLUMN ema50 DECIMAL(20, 2) COMMENT 'EMA50均线值',
    
    -- ATR波动率
    ADD COLUMN atr DECIMAL(20, 2) COMMENT 'ATR波动率指标值',
    
    -- K线形态
    ADD COLUMN candle_pattern VARCHAR(50) COMMENT 'K线形态类型(BULLISH_ENGULFING/BEARISH_ENGULFING等)',
    ADD COLUMN candle_pattern_strength INT COMMENT 'K线形态强度(0-10)',
    
    -- 布林带
    ADD COLUMN bollinger_upper DECIMAL(20, 2) COMMENT '布林带上轨',
    ADD COLUMN bollinger_middle DECIMAL(20, 2) COMMENT '布林带中轨',
    ADD COLUMN bollinger_lower DECIMAL(20, 2) COMMENT '布林带下轨',
    
    -- 信号相关
    ADD COLUMN signal_strength INT COMMENT '信号强度(0-100)',
    ADD COLUMN signal_score INT COMMENT '信号得分',
    
    -- 市场状态
    ADD COLUMN market_regime VARCHAR(50) COMMENT '市场状态(STRONG_TREND/WEAK_TREND/RANGING/CHOPPY)',
    
    -- ML预测
    ADD COLUMN ml_prediction DECIMAL(5, 4) COMMENT 'ML预测值(0-1)',
    ADD COLUMN ml_confidence DECIMAL(5, 4) COMMENT 'ML置信度(0-1)';

-- 添加索引以提高查询效率
CREATE INDEX idx_market_regime ON t_trade_order(market_regime);
CREATE INDEX idx_signal_strength ON t_trade_order(signal_strength);
CREATE INDEX idx_candle_pattern ON t_trade_order(candle_pattern);
CREATE INDEX idx_adx ON t_trade_order(adx);

-- 查看表结构
DESC t_trade_order;

-- 统计验证
SELECT 
    '字段添加完成' as status,
    COUNT(*) as total_records,
    SUM(CASE WHEN williams_r IS NOT NULL THEN 1 ELSE 0 END) as with_williams,
    SUM(CASE WHEN adx IS NOT NULL THEN 1 ELSE 0 END) as with_adx,
    SUM(CASE WHEN market_regime IS NOT NULL THEN 1 ELSE 0 END) as with_regime
FROM t_trade_order;

-- ==========================================
-- 🎯 AI学习查询示例
-- ==========================================

-- 1. 查询不同市场状态下的胜率
SELECT 
    market_regime as '市场状态',
    COUNT(*) as '交易次数',
    SUM(CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END) as '盈利次数',
    ROUND(SUM(CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as '胜率%',
    ROUND(AVG(profit_loss), 2) as '平均盈亏',
    ROUND(SUM(profit_loss), 2) as '总盈亏'
FROM t_trade_order
WHERE status LIKE 'CLOSED%' AND market_regime IS NOT NULL
GROUP BY market_regime
ORDER BY '胜率%' DESC;

-- 2. 查询不同信号强度的表现
SELECT 
    CASE 
        WHEN signal_strength >= 90 THEN '超强(>=90)'
        WHEN signal_strength >= 80 THEN '很强(80-89)'
        WHEN signal_strength >= 70 THEN '强(70-79)'
        WHEN signal_strength >= 60 THEN '中等(60-69)'
        ELSE '弱(<60)'
    END as '信号强度',
    COUNT(*) as '交易次数',
    ROUND(SUM(CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as '胜率%',
    ROUND(AVG(profit_loss), 2) as '平均盈亏'
FROM t_trade_order
WHERE status LIKE 'CLOSED%' AND signal_strength IS NOT NULL
GROUP BY 
    CASE 
        WHEN signal_strength >= 90 THEN '超强(>=90)'
        WHEN signal_strength >= 80 THEN '很强(80-89)'
        WHEN signal_strength >= 70 THEN '强(70-79)'
        WHEN signal_strength >= 60 THEN '中等(60-69)'
        ELSE '弱(<60)'
    END
ORDER BY '胜率%' DESC;

-- 3. 查询K线形态的有效性
SELECT 
    candle_pattern as 'K线形态',
    COUNT(*) as '出现次数',
    ROUND(AVG(candle_pattern_strength), 1) as '平均强度',
    ROUND(SUM(CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as '胜率%',
    ROUND(AVG(profit_loss), 2) as '平均盈亏'
FROM t_trade_order
WHERE status LIKE 'CLOSED%' AND candle_pattern IS NOT NULL
GROUP BY candle_pattern
HAVING COUNT(*) >= 3
ORDER BY '胜率%' DESC;

-- 4. 查询ADX vs 胜率的关系
SELECT 
    CASE 
        WHEN adx >= 40 THEN '超强趋势(>=40)'
        WHEN adx >= 30 THEN '强趋势(30-39)'
        WHEN adx >= 20 THEN '弱趋势(20-29)'
        ELSE '震荡市(<20)'
    END as 'ADX区间',
    COUNT(*) as '交易次数',
    ROUND(SUM(CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as '胜率%',
    ROUND(AVG(profit_loss), 2) as '平均盈亏'
FROM t_trade_order
WHERE status LIKE 'CLOSED%' AND adx IS NOT NULL
GROUP BY 
    CASE 
        WHEN adx >= 40 THEN '超强趋势(>=40)'
        WHEN adx >= 30 THEN '强趋势(30-39)'
        WHEN adx >= 20 THEN '弱趋势(20-29)'
        ELSE '震荡市(<20)'
    END
ORDER BY '胜率%' DESC;

-- 5. 导出AI训练数据(CSV格式)
SELECT 
    symbol as '品种',
    side as '方向',
    price as '价格',
    williams_r as 'WilliamsR',
    adx as 'ADX',
    ema20 as 'EMA20',
    ema50 as 'EMA50',
    atr as 'ATR',
    candle_pattern as 'K线形态',
    candle_pattern_strength as '形态强度',
    signal_strength as '信号强度',
    signal_score as '信号得分',
    market_regime as '市场状态',
    ml_prediction as 'ML预测',
    ml_confidence as 'ML置信度',
    profit_loss as '盈亏',
    CASE WHEN profit_loss > 0 THEN 1 ELSE 0 END as '是否盈利'
FROM t_trade_order
WHERE status LIKE 'CLOSED%'
    AND williams_r IS NOT NULL
ORDER BY create_time DESC
LIMIT 1000;
