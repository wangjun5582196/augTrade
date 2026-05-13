-- MySQL dump 10.13  Distrib 8.0.45, for macos15 (arm64)
--
-- Host: localhost    Database: aug
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `ml_prediction_record`
--

DROP TABLE IF EXISTS `ml_prediction_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ml_prediction_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易品种',
  `ml_prediction` decimal(5,4) NOT NULL COMMENT 'ML预测值（0-1概率）',
  `predicted_signal` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '预测方向（BUY/SELL/HOLD）',
  `confidence` decimal(5,4) NOT NULL COMMENT '置信度（0-1）',
  `williams_r` decimal(10,4) DEFAULT NULL COMMENT 'Williams %R值',
  `price_at_prediction` decimal(20,8) NOT NULL COMMENT '预测时的价格',
  `trade_taken` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否开仓',
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联的订单号',
  `actual_result` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT '实际结果（PROFIT/LOSS/PENDING/NOT_TRADED）',
  `profit_loss` decimal(20,8) DEFAULT NULL COMMENT '实际盈亏',
  `is_correct` tinyint(1) DEFAULT NULL COMMENT '预测是否正确',
  `features_json` json DEFAULT NULL COMMENT '特征值JSON（可选，用于深度分析）',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `prediction_time` datetime NOT NULL COMMENT '预测时间',
  `result_time` datetime DEFAULT NULL COMMENT '结果确认时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_predicted_signal` (`predicted_signal`),
  KEY `idx_actual_result` (`actual_result`),
  KEY `idx_prediction_time` (`prediction_time`),
  KEY `idx_result_time` (`result_time`),
  KEY `idx_trade_taken` (`trade_taken`),
  KEY `idx_is_correct` (`is_correct`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_symbol_prediction_time` (`symbol`,`prediction_time`),
  KEY `idx_symbol_actual_result` (`symbol`,`actual_result`),
  KEY `idx_prediction_accuracy` (`predicted_signal`,`is_correct`,`confidence`)
) ENGINE=InnoDB AUTO_INCREMENT=66 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ML预测记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `paper_position`
--

DROP TABLE IF EXISTS `paper_position`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `paper_position` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `position_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '仓位ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易品种',
  `side` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易方向：LONG-多头, SHORT-空头',
  `entry_price` decimal(20,8) NOT NULL COMMENT '开仓价格',
  `quantity` decimal(20,8) NOT NULL COMMENT '持仓数量',
  `stop_loss_price` decimal(20,8) DEFAULT NULL COMMENT '止损价格',
  `take_profit_price` decimal(20,8) DEFAULT NULL COMMENT '止盈价格',
  `current_price` decimal(20,8) DEFAULT NULL COMMENT '当前价格（实时更新）',
  `unrealized_pnl` decimal(20,8) DEFAULT NULL COMMENT '未实现盈亏',
  `strategy_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `open_time` datetime NOT NULL COMMENT '开仓时间',
  `close_time` datetime DEFAULT NULL COMMENT '平仓时间',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT '仓位状态：OPEN-持仓中, CLOSED-已平仓',
  `trailing_stop_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用移动止损',
  `close_price` decimal(20,8) DEFAULT NULL COMMENT '平仓价格',
  `realized_pnl` decimal(20,8) DEFAULT NULL COMMENT '已实现盈亏',
  `close_reason` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '平仓原因：STOP_LOSS-止损, TAKE_PROFIT-止盈, SIGNAL-信号平仓, MANUAL-手动平仓',
  `max_favorable_price` decimal(20,8) DEFAULT NULL COMMENT '期间最有利价格（用于移动止损）',
  `max_unfavorable_price` decimal(20,8) DEFAULT NULL COMMENT '期间最不利价格',
  `last_updated_price_time` datetime DEFAULT NULL COMMENT '最后更新价格时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_position_id` (`position_id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_side` (`side`),
  KEY `idx_strategy_name` (`strategy_name`),
  KEY `idx_status` (`status`),
  KEY `idx_open_time` (`open_time`),
  KEY `idx_close_time` (`close_time`),
  KEY `idx_trailing_stop_enabled` (`trailing_stop_enabled`),
  KEY `idx_symbol_status` (`symbol`,`status`),
  KEY `idx_strategy_status` (`strategy_name`,`status`),
  KEY `idx_open_time_status` (`open_time`,`status`),
  KEY `idx_active_positions` (`status`,`symbol`,`open_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模拟持仓表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_backtest_result`
--

DROP TABLE IF EXISTS `t_backtest_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_backtest_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `backtest_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '回测任务ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对符号',
  `strategy_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `interval` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'K线周期',
  `start_time` datetime NOT NULL COMMENT '回测开始时间',
  `end_time` datetime NOT NULL COMMENT '回测结束时间',
  `initial_capital` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '初始资金',
  `final_capital` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '最终资金',
  `total_profit` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '总收益',
  `return_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '收益率(%)',
  `total_trades` int NOT NULL DEFAULT '0' COMMENT '总交易次数',
  `profitable_trades` int NOT NULL DEFAULT '0' COMMENT '盈利交易次数',
  `losing_trades` int NOT NULL DEFAULT '0' COMMENT '亏损交易次数',
  `win_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '胜率(%)',
  `max_profit` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '最大收益',
  `max_loss` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '最大亏损',
  `max_drawdown` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '最大回撤',
  `max_drawdown_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '最大回撤率(%)',
  `avg_profit` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '平均盈利',
  `avg_loss` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '平均亏损',
  `profit_loss_ratio` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '盈亏比',
  `sharpe_ratio` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '夏普比率',
  `total_fee` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '总手续费',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT '回测状态：RUNNING-运行中, COMPLETED-已完成, FAILED-失败',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '回测备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_backtest_id` (`backtest_id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_strategy_name` (`strategy_name`),
  KEY `idx_interval` (`interval`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=127 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回测结果表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_backtest_trade`
--

DROP TABLE IF EXISTS `t_backtest_trade`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_backtest_trade` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `backtest_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '回测任务ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对符号',
  `side` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易方向：BUY-买入, SELL-卖出',
  `entry_price` decimal(20,8) NOT NULL COMMENT '开仓价格',
  `entry_time` datetime NOT NULL COMMENT '开仓时间',
  `exit_price` decimal(20,8) NOT NULL COMMENT '平仓价格',
  `exit_time` datetime NOT NULL COMMENT '平仓时间',
  `quantity` decimal(20,8) NOT NULL COMMENT '交易数量',
  `profit_loss` decimal(20,8) NOT NULL COMMENT '盈亏金额',
  `profit_loss_rate` decimal(10,4) NOT NULL COMMENT '盈亏率(%)',
  `fee` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '手续费',
  `holding_minutes` int NOT NULL COMMENT '持仓时长（分钟）',
  `take_profit_price` decimal(20,8) DEFAULT NULL COMMENT '止盈价格',
  `stop_loss_price` decimal(20,8) DEFAULT NULL COMMENT '止损价格',
  `exit_reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '平仓原因：TAKE_PROFIT-止盈, STOP_LOSS-止损, SIGNAL-信号平仓',
  `signal_description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '交易信号描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_backtest_id` (`backtest_id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_side` (`side`),
  KEY `idx_entry_time` (`entry_time`),
  KEY `idx_exit_time` (`exit_time`),
  KEY `idx_exit_reason` (`exit_reason`),
  KEY `idx_backtest_entry` (`backtest_id`,`entry_time`),
  KEY `idx_backtest_exit` (`backtest_id`,`exit_time`)
) ENGINE=InnoDB AUTO_INCREMENT=22381 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回测交易记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_kline`
--

DROP TABLE IF EXISTS `t_kline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_kline` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对符号',
  `interval` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'K线周期',
  `timestamp` datetime NOT NULL COMMENT 'K线时间戳',
  `open_price` decimal(20,8) NOT NULL COMMENT '开盘价',
  `high_price` decimal(20,8) NOT NULL COMMENT '最高价',
  `low_price` decimal(20,8) NOT NULL COMMENT '最低价',
  `close_price` decimal(20,8) NOT NULL COMMENT '收盘价',
  `volume` decimal(30,8) NOT NULL COMMENT '成交量',
  `amount` decimal(30,8) NOT NULL DEFAULT '0.00000000' COMMENT '成交额',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symbol_interval_timestamp` (`symbol`,`interval`,`timestamp`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_interval` (`interval`),
  KEY `idx_timestamp` (`timestamp`),
  KEY `idx_symbol_timestamp` (`symbol`,`timestamp`),
  KEY `idx_symbol_interval_timestamp_desc` (`symbol`,`interval`,`timestamp` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=88125 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='K线数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_position`
--

DROP TABLE IF EXISTS `t_position`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_position` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对符号',
  `direction` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '持仓方向：LONG-多头, SHORT-空头',
  `quantity` decimal(20,8) NOT NULL COMMENT '持仓数量',
  `avg_price` decimal(20,8) NOT NULL COMMENT '开仓均价',
  `current_price` decimal(20,8) DEFAULT NULL COMMENT '当前价格',
  `unrealized_pnl` decimal(20,8) DEFAULT NULL COMMENT '未实现盈亏',
  `margin` decimal(20,8) DEFAULT NULL COMMENT '保证金',
  `leverage` int DEFAULT NULL COMMENT '杠杆倍数',
  `take_profit_price` decimal(20,8) DEFAULT NULL COMMENT '止盈价格',
  `stop_loss_price` decimal(20,8) DEFAULT NULL COMMENT '止损价格',
  `trailing_stop_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用移动止损',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT '持仓状态：OPEN-开仓中, CLOSED-已平仓',
  `open_time` datetime NOT NULL COMMENT '开仓时间',
  `close_time` datetime DEFAULT NULL COMMENT '平仓时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_direction` (`direction`),
  KEY `idx_status` (`status`),
  KEY `idx_open_time` (`open_time`),
  KEY `idx_close_time` (`close_time`),
  KEY `idx_symbol_status` (`symbol`,`status`),
  KEY `idx_status_open_time` (`status`,`open_time`),
  KEY `idx_trailing_stop_enabled` (`trailing_stop_enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='持仓信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_trade_order`
--

DROP TABLE IF EXISTS `t_trade_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_trade_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
  `symbol` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易对符号',
  `order_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单类型：MARKET-市价单, LIMIT-限价单',
  `side` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易方向：BUY-买入, SELL-卖出',
  `price` decimal(20,8) DEFAULT NULL COMMENT '下单价格',
  `quantity` decimal(20,8) NOT NULL COMMENT '下单数量',
  `executed_price` decimal(20,8) DEFAULT NULL COMMENT '成交价格',
  `executed_quantity` decimal(20,8) DEFAULT NULL COMMENT '成交数量',
  `status` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING-待成交, FILLED-已成交, CANCELLED-已取消, FAILED-失败',
  `strategy_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '策略名称',
  `take_profit_price` decimal(20,8) DEFAULT NULL COMMENT '止盈价格',
  `stop_loss_price` decimal(20,8) DEFAULT NULL COMMENT '止损价格',
  `profit_loss` decimal(20,8) DEFAULT NULL COMMENT '盈亏金额',
  `fee` decimal(20,8) DEFAULT '0.00000000' COMMENT '手续费',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单备注',
  `williams_r` decimal(7,4) DEFAULT NULL COMMENT 'Williams %R指标值',
  `adx` decimal(7,4) DEFAULT NULL COMMENT 'ADX趋势强度指标值',
  `ema20` decimal(20,8) DEFAULT NULL COMMENT 'EMA20均线值',
  `ema50` decimal(20,8) DEFAULT NULL COMMENT 'EMA50均线值',
  `atr` decimal(20,8) DEFAULT NULL COMMENT 'ATR波动率指标值',
  `candle_pattern` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'K线形态类型',
  `candle_pattern_strength` tinyint DEFAULT NULL COMMENT 'K线形态强度(0-10)',
  `bollinger_upper` decimal(20,8) DEFAULT NULL COMMENT '布林带上轨',
  `bollinger_middle` decimal(20,8) DEFAULT NULL COMMENT '布林带中轨',
  `bollinger_lower` decimal(20,8) DEFAULT NULL COMMENT '布林带下轨',
  `signal_strength` tinyint DEFAULT NULL COMMENT '信号强度(0-100)',
  `signal_score` int DEFAULT NULL COMMENT '信号得分',
  `market_regime` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '市场状态: STRONG_TREND/WEAK_TREND/RANGING/CHOPPY',
  `ml_prediction` decimal(5,4) DEFAULT NULL COMMENT 'ML预测值(0-1)',
  `ml_confidence` decimal(5,4) DEFAULT NULL COMMENT 'ML置信度(0-1)',
  `vwap` decimal(20,8) DEFAULT NULL COMMENT 'VWAP值',
  `vwap_deviation` decimal(10,4) DEFAULT NULL COMMENT '价格偏离VWAP百分比',
  `supertrend_value` decimal(20,8) DEFAULT NULL COMMENT 'Supertrend线的值',
  `supertrend_direction` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Supertrend趋势方向：UP/DOWN',
  `obv_trend` decimal(20,8) DEFAULT NULL COMMENT 'OBV趋势方向（正=看涨，负=看跌）',
  `obv_volume_confirmed` tinyint(1) DEFAULT NULL COMMENT 'OBV是否量价确认：1=确认，0=未确认',
  `buy_score` int DEFAULT NULL COMMENT '做多信号总分',
  `sell_score` int DEFAULT NULL COMMENT '做空信号总分',
  `signal_reasons` text COLLATE utf8mb4_unicode_ci COMMENT '信号理由列表(JSON格式)',
  `momentum2` decimal(20,8) DEFAULT NULL COMMENT '2根K线动量',
  `momentum5` decimal(20,8) DEFAULT NULL COMMENT '5根K线动量',
  `volume_ratio` decimal(10,4) DEFAULT NULL COMMENT '成交量比率(当前/20周期平均)',
  `current_volume` decimal(30,8) DEFAULT NULL COMMENT '当前成交量',
  `avg_volume` decimal(30,8) DEFAULT NULL COMMENT '20周期平均成交量',
  `swing_high` decimal(20,8) DEFAULT NULL COMMENT '最近摆动高点',
  `swing_low` decimal(20,8) DEFAULT NULL COMMENT '最近摆动低点',
  `swing_high_distance` decimal(20,8) DEFAULT NULL COMMENT '价格距离摆动高点',
  `swing_low_distance` decimal(20,8) DEFAULT NULL COMMENT '价格距离摆动低点',
  `hma20` decimal(20,8) DEFAULT NULL COMMENT 'Hull Moving Average 20',
  `hma_slope` decimal(20,8) DEFAULT NULL COMMENT 'HMA斜率',
  `hma_trend` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'HMA趋势方向(UP/DOWN/SIDEWAYS)',
  `price_position` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '价格位置(ABOVE_SWING_HIGH/BELOW_SWING_LOW/BETWEEN)',
  `trend_confirmed` tinyint(1) DEFAULT NULL COMMENT '趋势是否确认(1=是,0=否)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `executed_time` datetime DEFAULT NULL COMMENT '成交时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_side` (`side`),
  KEY `idx_status` (`status`),
  KEY `idx_strategy_name` (`strategy_name`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_executed_time` (`executed_time`),
  KEY `idx_symbol_status` (`symbol`,`status`),
  KEY `idx_strategy_status` (`strategy_name`,`status`),
  KEY `idx_status_create_time` (`status`,`create_time`),
  KEY `idx_market_regime` (`market_regime`),
  KEY `idx_supertrend_direction` (`supertrend_direction`),
  KEY `idx_hma_trend` (`hma_trend`),
  KEY `idx_price_position` (`price_position`)
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `trading_state`
--

DROP TABLE IF EXISTS `trading_state`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trading_state` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `state_key` varchar(50) NOT NULL COMMENT '状态键（唯一）',
  `state_value` varchar(255) DEFAULT NULL COMMENT '状态值',
  `last_update` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `state_key` (`state_key`),
  KEY `idx_state_key` (`state_key`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易状态持久化表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'aug'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-05-13 13:01:43
