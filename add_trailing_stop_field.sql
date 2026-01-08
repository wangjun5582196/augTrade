-- 添加移动止损字段到持仓表
-- 用于支持移动止损（Trailing Stop）功能

ALTER TABLE t_position 
ADD COLUMN trailing_stop_enabled TINYINT(1) DEFAULT 0 COMMENT '是否启用移动止损：0-未启用，1-已启用' 
AFTER stop_loss_price;

-- 查看表结构
DESC t_position;
