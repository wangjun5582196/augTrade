#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""快速统计脚本"""

import pandas as pd

# 读取CSV
df = pd.read_csv('01_01_2007-04_02_2026.csv')

print("\n" + "="*80)
print("交易数据快速统计")
print("="*80 + "\n")

# 基础统计
total = len(df)
win = len(df[df['profit_usd'] > 0])
loss = len(df[df['profit_usd'] < 0])
even = len(df[df['profit_usd'] == 0])

print(f"📊 基础统计:")
print(f"   总交易次数: {total}")
print(f"   盈利: {win} ({win/total*100:.1f}%)")
print(f"   亏损: {loss} ({loss/total*100:.1f}%)")
print(f"   持平: {even} ({even/total*100:.1f}%)")
print()

# 盈亏统计
total_profit = df['profit_usd'].sum()
avg_win = df[df['profit_usd'] > 0]['profit_usd'].mean()
avg_loss = df[df['profit_usd'] < 0]['profit_usd'].mean()
max_win = df['profit_usd'].max()
max_loss = df['profit_usd'].min()

print(f"💰 盈亏统计:")
print(f"   总盈亏: ${total_profit:.2f}")
print(f"   平均盈利: ${avg_win:.2f}")
print(f"   平均亏损: ${avg_loss:.2f}")
print(f"   盈亏比: {abs(avg_win/avg_loss):.2f}:1")
print(f"   最大盈利: ${max_win:.2f}")
print(f"   最大亏损: ${max_loss:.2f}")
print()

# 品种统计
print(f"📈 交易品种:")
for symbol in df['symbol'].unique():
    count = len(df[df['symbol'] == symbol])
    profit = df[df['symbol'] == symbol]['profit_usd'].sum()
    print(f"   {symbol}: {count}笔 (${profit:.2f})")
print()

# 做多做空
buy_count = len(df[df['type'] == 'buy'])
sell_count = len(df[df['type'] == 'sell'])
buy_profit = df[df['type'] == 'buy']['profit_usd'].sum()
sell_profit = df[df['type'] == 'sell']['profit_usd'].sum()
buy_winrate = len(df[(df['type'] == 'buy') & (df['profit_usd'] > 0)]) / buy_count * 100
sell_winrate = len(df[(df['type'] == 'sell') & (df['profit_usd'] > 0)]) / sell_count * 100

print(f"📊 做多/做空对比:")
print(f"   做多: {buy_count}笔 ({buy_count/total*100:.1f}%), 盈亏${buy_profit:.2f}, 胜率{buy_winrate:.1f}%")
print(f"   做空: {sell_count}笔 ({sell_count/total*100:.1f}%), 盈亏${sell_profit:.2f}, 胜率{sell_winrate:.1f}%")
print()

# 仓位分析
print(f"💼 仓位使用情况:")
lots_count = df.groupby('lots').size().sort_index()
for lot, count in lots_count.items():
    print(f"   {lot}手: {count}笔 ({count/total*100:.1f}%)")
print()

# 重仓交易统计
heavy_pos = df[df['lots'] >= 0.5]
print(f"⚠️  重仓交易 (≥0.5手): {len(heavy_pos)}笔 ({len(heavy_pos)/total*100:.1f}%)")
print(f"   重仓总盈亏: ${heavy_pos['profit_usd'].sum():.2f}")
print()

# 持仓时长
df['opening_time'] = pd.to_datetime(df['opening_time_utc'], format='mixed')
df['closing_time'] = pd.to_datetime(df['closing_time_utc'], format='mixed')
df['duration_min'] = (df['closing_time'] - df['opening_time']).dt.total_seconds() / 60

print(f"⏱️  持仓时长:")
print(f"   平均: {df['duration_min'].mean():.1f}分钟 ({df['duration_min'].mean()/60:.1f}小时)")
print(f"   中位数: {df['duration_min'].median():.1f}分钟")
print(f"   最短: {df['duration_min'].min():.1f}分钟")
print(f"   最长: {df['duration_min'].max():.1f}分钟 ({df['duration_min'].max()/60:.1f}小时)")
print()

# 超短线交易
ultra_short = len(df[df['duration_min'] < 5])
short = len(df[(df['duration_min'] >= 5) & (df['duration_min'] < 60)])
medium = len(df[(df['duration_min'] >= 60) & (df['duration_min'] < 240)])
long_term = len(df[df['duration_min'] >= 240])

print(f"   <5分钟: {ultra_short}笔 ({ultra_short/total*100:.1f}%)")
print(f"   5-60分钟: {short}笔 ({short/total*100:.1f}%)")
print(f"   1-4小时: {medium}笔 ({medium/total*100:.1f}%)")
print(f"   >4小时: {long_term}笔 ({long_term/total*100:.1f}%)")
print()

# 止损止盈使用
sl_set = df['stop_loss'].notna().sum()
tp_set = df['take_profit'].notna().sum()
sl_triggered = len(df[df['close_reason'] == 'sl'])
tp_triggered = len(df[df['close_reason'] == 'tp'])

print(f"🛡️  风险管理:")
print(f"   设置止损: {sl_set}笔 ({sl_set/total*100:.1f}%)")
print(f"   设置止盈: {tp_set}笔 ({tp_set/total*100:.1f}%)")
print(f"   被止损: {sl_triggered}笔")
print(f"   触发止盈: {tp_triggered}笔")
print(f"   手动平仓: {len(df[df['close_reason'] == 'user'])}笔")
if len(df[df['close_reason'] == 'so']) > 0:
    print(f"   ⚠️ 爆仓: {len(df[df['close_reason'] == 'so'])}笔")
print()

# 止损交易分析
if sl_triggered > 0:
    sl_profit = df[df['close_reason'] == 'sl']['profit_usd'].sum()
    sl_avg = df[df['close_reason'] == 'sl']['profit_usd'].mean()
    print(f"   止损交易总盈亏: ${sl_profit:.2f}")
    print(f"   止损平均亏损: ${sl_avg:.2f}")
    print()

# 最差的几笔交易
print(f"💔 最大亏损TOP5:")
worst_trades = df.nsmallest(5, 'profit_usd')[['opening_time_utc', 'type', 'lots', 'symbol', 'profit_usd', 'close_reason']]
for idx, row in worst_trades.iterrows():
    print(f"   {row['opening_time_utc'][:10]} {row['type']} {row['lots']}手 {row['symbol']}: ${row['profit_usd']:.2f} ({row['close_reason']})")
print()

# 最好的几笔交易
print(f"💚 最大盈利TOP5:")
best_trades = df.nlargest(5, 'profit_usd')[['opening_time_utc', 'type', 'lots', 'symbol', 'profit_usd', 'close_reason']]
for idx, row in best_trades.iterrows():
    print(f"   {row['opening_time_utc'][:10]} {row['type']} {row['lots']}手 {row['symbol']}: ${row['profit_usd']:.2f} ({row['close_reason']})")
print()

# 日期统计
df['date'] = pd.to_datetime(df['opening_time_utc']).dt.date
daily_stats = df.groupby('date').agg({
    'ticket': 'count',
    'profit_usd': 'sum'
}).round(2)
daily_stats.columns = ['交易次数', '盈亏']

print(f"📅 交易活跃度:")
print(f"   总交易日: {len(daily_stats)}")
print(f"   日均交易: {daily_stats['交易次数'].mean():.1f}笔")
print(f"   最多单日: {daily_stats['交易次数'].max()}笔 ({daily_stats['交易次数'].idxmax()})")
print(f"   最少单日: {daily_stats['交易次数'].min()}笔 ({daily_stats['交易次数'].idxmin()})")
print()

# 最好和最差的交易日
best_day = daily_stats.nlargest(1, '盈亏')
worst_day = daily_stats.nsmallest(1, '盈亏')
print(f"   最佳交易日: {best_day.index[0]} (${best_day['盈亏'].values[0]:.2f})")
print(f"   最差交易日: {worst_day.index[0]} (${worst_day['盈亏'].values[0]:.2f})")

print("\n" + "="*80)
print("统计完成！详细分析请查看：交易分析报告_20260204.md")
print("="*80 + "\n")
