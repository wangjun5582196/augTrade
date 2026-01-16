-- 创建交易状态持久化表
-- 用于存储重启后需要恢复的关键状态变量

CREATE TABLE IF NOT EXISTS trading_state (
    id INT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    state_key VARCHAR(50) UNIQUE NOT NULL COMMENT '状态键（唯一）',
    state_value VARCHAR(255) COMMENT '状态值',
    last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_state_key (state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易状态持久化表';

-- 插入初始状态（如果不存在）
INSERT INTO trading_state (state_key, state_value) 
VALUES ('daily_trade_count', '0')
ON DUPLICATE KEY UPDATE state_value = state_value;

INSERT INTO trading_state (state_key, state_value)
VALUES ('daily_trade_reset_time', DATE_FORMAT(NOW(), '%Y-%m-%dT00:00:00'))
ON DUPLICATE KEY UPDATE state_value = state_value;

-- 查询当前状态
SELECT * FROM trading_state;
