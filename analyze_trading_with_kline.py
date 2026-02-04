#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
交易逻辑与K线数据综合分析
结合CSV交易记录和MySQL K线数据，深度分析交易逻辑
"""

import pandas as pd
import mysql.connector
from datetime import datetime, timedelta
import numpy as np

# 数据库连接
conn = mysql.connector.connect(
    host='localhost',
    user='root',
    password='12345678',
    database='test'
)

# 读取交易记录
trades_df = pd.read_csv('01_01_2007-04_02_2026.csv')
trades_df['opening_time_utc'] = pd.to_datetime(trades_df['opening_time_utc'], format='mixed')
trades_df['closing_time_utc'] = pd.to_datetime(trades_df['closing_time_utc'], format='mixed')
trades_df['duration_seconds'] = (trades_df['closing_time_utc'] - trades_df['opening_time_utc']).dt.total_seconds()

# 只分析黄金交易
gold_trades = trades_df[trades_df['symbol'] == 'XAUUSDm'].copy()
print(f"黄金交易总数: {len(gold_trades)}")

# 重点分析2026-02-02的交易（有爆仓）
focus_date = '2026-02-02'
focus_trades = gold_trades[gold_trades['opening_time_utc'].dt.date == pd.to_datetime(focus_date).date()]

print(f"\n{'='*80}")
print(f"2026-02-02 交易分析（爆仓日）")
print(f"{'='*80}")
print(f"当日交易数: {len(focus_trades)}")
print(f"当日盈亏: ${focus_trades['profit_usd'].sum():.2f}")
print(f"爆仓次数: {len(focus_trades[focus_trades['close_reason'] == 'so'])}")

# 获取当天K线数据
query = """
SELECT 
    timestamp,
    open_price,
    high_price,
    low_price,
    close_price,
    volume,
    (high_price - low_price) as price_range,
    (close_price - open_price) as body_size
FROM t_kline 
WHERE symbol='XAUTUSDT'
    AND DATE(timestamp) = %s
ORDER BY timestamp
"""

klines_df = pd.read_sql(query, conn, params=(focus_date,))
klines_df['timestamp'] = pd.to_datetime(klines_df['timestamp'])

print(f"\n当日K线数: {len(klines_df)}")
print(f"价格波动范围: {klines_df['low_price'].min():.2f} - {klines_df['high_price'].max():.2f}")
print(f"波动幅度: {(klines_df['high_price'].max() - klines_df['low_price'].min()):.2f} 点")

# 分析爆仓交易的K线环境
print(f"\n{'='*80}")
print(f"爆仓交易分析")
print(f"{'='*80}")

so_trades = focus_trades[focus_trades['close_reason'] == 'so']
for idx, trade in so_trades.iterrows():
    print(f"\n订单号: {trade['ticket']}")
    print(f"类型: {trade['type']}")
    print(f"手数: {trade['lots']}")
    print(f"开仓: {trade['opening_time_utc']} @ {trade['opening_price']:.2f}")
    print(f"平仓: {trade['closing_time_utc']} @ {trade['closing_price']:.2f}")
    print(f"亏损: ${trade['profit_usd']:.2f}")
    print(f"持仓时长: {trade['duration_seconds']:.0f}秒")
    
    # 找到对应的K线
    open_time = trade['opening_time_utc']
    close_time = trade['closing_time_utc']
    
    # 获取该时段的K线
    period_klines = klines_df[
        (klines_df['timestamp'] >= open_time - timedelta(minutes=10)) &
        (klines_df['timestamp'] <= close_time + timedelta(minutes=5))
    ]
    
    if len(period_klines) > 0:
        print(f"\n持仓期间K线特征:")
        print(f"  K线数量: {len(period_klines)}")
        print(f"  最高价: {period_klines['high_price'].max():.2f}")
        print(f"  最低价: {period_klines['low_price'].min():.2f}")
        print(f"  价格波动: {(period_klines['high_price'].max() - period_klines['low_price'].min()):.2f} 点")
        print(f"  平均K线幅度: {period_klines['price_range'].mean():.2f}")
        print(f"  最大K线幅度: {period_klines['price_range'].max():.2f}")
        
        # 找出最极端的K线
        max_range_kline = period_klines.loc[period_klines['price_range'].idxmax()]
        print(f"\n  最大波动K线:")
        print(f"    时间: {max_range_kline['timestamp']}")
        print(f"    开盘: {max_range_kline['open_price']:.2f}")
        print(f"    最高: {max_range_kline['high_price']:.2f}")
        print(f"    最低: {max_range_kline['low_price']:.2f}")
        print(f"    收盘: {max_range_kline['close_price']:.2f}")
        print(f"    波动: {max_range_kline['price_range']:.2f} 点")
        print(f"    成交量: {max_range_kline['volume']:.4f}")

# 分析交易入场时的市场状态
print(f"\n{'='*80}")
print(f"入场时机分析（抽样分析最近20笔交易）")
print(f"{'='*80}")

recent_trades = focus_trades.sort_values('opening_time_utc', ascending=False).head(20)

for idx, trade in recent_trades.iterrows():
    open_time = trade['opening_time_utc']
    
    # 找到开仓前后的K线
    nearby_klines = klines_df[
        (klines_df['timestamp'] >= open_time - timedelta(minutes=15)) &
        (klines_df['timestamp'] <= open_time + timedelta(minutes=5))
    ]
    
    if len(nearby_klines) >= 3:
        prev_kline = nearby_klines.iloc[-2]  # 前一根K线
        curr_kline = nearby_klines.iloc[-1]  # 当前K线
        
        # 判断趋势
        trend = "上涨" if curr_kline['close_price'] > prev_kline['close_price'] else "下跌"
        
        print(f"\n订单 {trade['ticket']} ({trade['type']})")
        print(f"  开仓时间: {open_time.strftime('%H:%M:%S')}")
        print(f"  开仓价格: {trade['opening_price']:.2f}")
        print(f"  当前K线: {curr_kline['timestamp'].strftime('%H:%M')} - 趋势:{trend}")
        print(f"    价格: O:{curr_kline['open_price']:.2f} H:{curr_kline['high_price']:.2f} L:{curr_kline['low_price']:.2f} C:{curr_kline['close_price']:.2f}")
        print(f"  交易方向: {trade['type'].upper()}")
        print(f"  是否顺势: {'✓' if (trade['type']=='sell' and trend=='下跌') or (trade['type']=='buy' and trend=='上涨') else '✗ 逆势'}")
        print(f"  结果: ${trade['profit_usd']:.2f} ({trade['close_reason']})")

# 统计分析
print(f"\n{'='*80}")
print(f"交易策略统计分析")
print(f"{'='*80}")

# 按小时分析交易活跃度
gold_trades['hour'] = gold_trades['opening_time_utc'].dt.hour
hourly_stats = gold_trades.groupby('hour').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)

print("\n按小时交易统计（UTC时间）:")
print(hourly_stats.to_string())

# 持仓时长分析
print(f"\n持仓时长分析:")
print(f"平均持仓: {gold_trades['duration_seconds'].mean():.0f} 秒 ({gold_trades['duration_seconds'].mean()/60:.1f} 分钟)")
print(f"中位数: {gold_trades['duration_seconds'].median():.0f} 秒")
print(f"最短: {gold_trades['duration_seconds'].min():.0f} 秒")
print(f"最长: {gold_trades['duration_seconds'].max():.0f} 秒")

# 超短线（<5分钟）统计
ultra_short = gold_trades[gold_trades['duration_seconds'] < 300]
print(f"\n超短线交易（<5分钟）:")
print(f"数量: {len(ultra_short)} ({len(ultra_short)/len(gold_trades)*100:.1f}%)")
print(f"胜率: {(ultra_short['profit_usd']>0).sum()/len(ultra_short)*100:.1f}%")
print(f"平均盈亏: ${ultra_short['profit_usd'].mean():.2f}")

# 手数分析
print(f"\n手数使用分析:")
lots_stats = gold_trades.groupby('lots').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
print(lots_stats.to_string())

# 止损触发分析
sl_trades = gold_trades[gold_trades['close_reason'] == 'sl']
print(f"\n止损交易分析:")
print(f"止损次数: {len(sl_trades)} ({len(sl_trades)/len(gold_trades)*100:.1f}%)")
print(f"止损平均亏损: ${sl_trades['profit_usd'].mean():.2f}")
print(f"止损总亏损: ${sl_trades['profit_usd'].sum():.2f}")

conn.close()

print(f"\n{'='*80}")
print("分析完成！")
print(f"{'='*80}")
