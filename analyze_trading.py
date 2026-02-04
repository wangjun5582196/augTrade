k#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
交易数据分析脚本
分析用户的开单逻辑、交易手法，并提出建议
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import matplotlib.pyplot as plt
import matplotlib
matplotlib.use('Agg')  # 使用非交互式后端
plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei']  # 支持中文
plt.rcParams['axes.unicode_minus'] = False

# 读取CSV文件
df = pd.read_csv('01_01_2007-04_02_2026.csv')

# 转换时间字段
df['opening_time'] = pd.to_datetime(df['opening_time_utc'])
df['closing_time'] = pd.to_datetime(df['closing_time_utc'])
df['duration'] = (df['closing_time'] - df['opening_time']).dt.total_seconds() / 60  # 持仓时长（分钟）

# 基础统计
print("=" * 80)
print("交易数据综合分析报告")
print("=" * 80)
print()

print("1. 基础统计信息")
print("-" * 80)
total_trades = len(df)
win_trades = len(df[df['profit_usd'] > 0])
loss_trades = len(df[df['profit_usd'] < 0])
breakeven_trades = len(df[df['profit_usd'] == 0])

print(f"总交易次数: {total_trades}")
print(f"盈利交易: {win_trades} ({win_trades/total_trades*100:.2f}%)")
print(f"亏损交易: {loss_trades} ({loss_trades/total_trades*100:.2f}%)")
print(f"持平交易: {breakeven_trades} ({breakeven_trades/total_trades*100:.2f}%)")
print()

# 盈亏统计
total_profit = df['profit_usd'].sum()
avg_profit = df[df['profit_usd'] > 0]['profit_usd'].mean()
avg_loss = df[df['profit_usd'] < 0]['profit_usd'].mean()
max_profit = df['profit_usd'].max()
max_loss = df['profit_usd'].min()

print(f"总盈亏: ${total_profit:.2f}")
print(f"平均盈利: ${avg_profit:.2f}")
print(f"平均亏损: ${avg_loss:.2f}")
print(f"最大单笔盈利: ${max_profit:.2f}")
print(f"最大单笔亏损: ${max_loss:.2f}")
print(f"盈亏比: {abs(avg_profit/avg_loss):.2f}")
print()

print("2. 交易品种分析")
print("-" * 80)
symbol_stats = df.groupby('symbol').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
symbol_stats.columns = ['交易次数', '总盈亏', '平均盈亏']
print(symbol_stats)
print()

print("3. 做多/做空分析")
print("-" * 80)
type_stats = df.groupby('type').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
type_stats.columns = ['交易次数', '总盈亏', '平均盈亏']
print(type_stats)
print()

# 计算做多做空的胜率
buy_trades = df[df['type'] == 'buy']
sell_trades = df[df['type'] == 'sell']
buy_win_rate = len(buy_trades[buy_trades['profit_usd'] > 0]) / len(buy_trades) * 100 if len(buy_trades) > 0 else 0
sell_win_rate = len(sell_trades[sell_trades['profit_usd'] > 0]) / len(sell_trades) * 100 if len(sell_trades) > 0 else 0

print(f"做多胜率: {buy_win_rate:.2f}%")
print(f"做空胜率: {sell_win_rate:.2f}%")
print()

print("4. 仓位管理分析")
print("-" * 80)
lots_stats = df.groupby('lots').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
lots_stats.columns = ['交易次数', '总盈亏', '平均盈亏']
print(lots_stats.head(10))
print()

print("5. 持仓时长分析")
print("-" * 80)
print(f"平均持仓时长: {df['duration'].mean():.2f} 分钟 ({df['duration'].mean()/60:.2f} 小时)")
print(f"最短持仓: {df['duration'].min():.2f} 分钟")
print(f"最长持仓: {df['duration'].max():.2f} 分钟 ({df['duration'].max()/60:.2f} 小时)")
print()

# 按持仓时长分组
df['duration_group'] = pd.cut(df['duration'], 
                               bins=[0, 5, 15, 60, 240, float('inf')],
                               labels=['<5分钟', '5-15分钟', '15-60分钟', '1-4小时', '>4小时'])
duration_analysis = df.groupby('duration_group').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
duration_analysis.columns = ['交易次数', '总盈亏', '平均盈亏']
print("持仓时长分组统计:")
print(duration_analysis)
print()

print("6. 止损/止盈使用情况")
print("-" * 80)
sl_used = df['stop_loss'].notna().sum()
tp_used = df['take_profit'].notna().sum()
print(f"使用止损的交易: {sl_used} ({sl_used/total_trades*100:.2f}%)")
print(f"使用止盈的交易: {tp_used} ({tp_used/total_trades*100:.2f}%)")

# 分析平仓原因
close_reason_stats = df['close_reason'].value_counts()
print("\n平仓原因统计:")
for reason, count in close_reason_stats.items():
    print(f"  {reason}: {count} ({count/total_trades*100:.2f}%)")
print()

# 止损交易的盈亏
sl_trades = df[df['close_reason'] == 'sl']
if len(sl_trades) > 0:
    print(f"止损交易总盈亏: ${sl_trades['profit_usd'].sum():.2f}")
    print(f"止损交易平均盈亏: ${sl_trades['profit_usd'].mean():.2f}")
print()

print("7. 交易时间分析")
print("-" * 80)
df['hour'] = df['opening_time'].dt.hour
df['day_of_week'] = df['opening_time'].dt.dayofweek  # 0=周一, 6=周日

# 按小时统计
hour_stats = df.groupby('hour').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
hour_stats.columns = ['交易次数', '总盈亏', '平均盈亏']
print("按小时统计 (UTC时间):")
print(hour_stats.head(24))
print()

# 按星期统计
week_days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
df['week_day_name'] = df['day_of_week'].map(lambda x: week_days[x])
week_stats = df.groupby('week_day_name').agg({
    'ticket': 'count',
    'profit_usd': ['sum', 'mean']
}).round(2)
week_stats.columns = ['交易次数', '总盈亏', '平均盈亏']
print("按星期统计:")
print(week_stats)
print()

print("8. 连续交易分析")
print("-" * 80)
# 计算连续盈利/亏损
df = df.sort_values('opening_time')
df['win'] = df['profit_usd'] > 0
df['win_streak'] = (df['win'] != df['win'].shift()).cumsum()

max_win_streak = df[df['win']].groupby('win_streak').size().max()
max_loss_streak = df[~df['win']].groupby('win_streak').size().max()

print(f"最大连续盈利: {max_win_streak} 次")
print(f"最大连续亏损: {max_loss_streak} 次")
print()

print("9. 主要交易品种详细分析 (XAUUSDm - 黄金)")
print("-" * 80)
gold_df = df[df['symbol'] == 'XAUUSDm'].copy()
if len(gold_df) > 0:
    print(f"黄金交易占比: {len(gold_df)/total_trades*100:.2f}%")
    print(f"黄金交易总盈亏: ${gold_df['profit_usd'].sum():.2f}")
    print(f"黄金交易胜率: {len(gold_df[gold_df['profit_usd'] > 0])/len(gold_df)*100:.2f}%")
    print(f"黄金平均持仓时长: {gold_df['duration'].mean():.2f} 分钟")
    
    # 黄金做多做空对比
    gold_buy = gold_df[gold_df['type'] == 'buy']
    gold_sell = gold_df[gold_df['type'] == 'sell']
    
    print(f"\n黄金做多交易: {len(gold_buy)} 次, 盈亏: ${gold_buy['profit_usd'].sum():.2f}")
    print(f"黄金做空交易: {len(gold_sell)} 次, 盈亏: ${gold_sell['profit_usd'].sum():.2f}")
print()

print("10. 交易手法特征识别")
print("-" * 80)

# 识别超短线交易（日内交易）
scalping_trades = df[df['duration'] < 60]  # 1小时内
print(f"超短线交易（<1小时）: {len(scalping_trades)} 次 ({len(scalping_trades)/total_trades*100:.2f}%)")

# 识别波段交易
swing_trades = df[(df['duration'] >= 60) & (df['duration'] < 1440)]  # 1小时到1天
print(f"日内波段交易（1小时-1天）: {len(swing_trades)} 次 ({len(swing_trades)/total_trades*100:.2f}%)")

# 识别持仓过夜
overnight_trades = df[df['duration'] >= 1440]  # 超过1天
print(f"持仓过夜（>1天）: {len(overnight_trades)} 次 ({len(overnight_trades)/total_trades*100:.2f}%)")

print()

# 识别频繁交易时段
df['date'] = df['opening_time'].dt.date
daily_trades = df.groupby('date').size()
print(f"日均交易次数: {daily_trades.mean():.2f}")
print(f"最多单日交易: {daily_trades.max()} 次 (日期: {daily_trades.idxmax()})")
print()

print("=" * 80)
print("关键问题分析")
print("=" * 80)
print()

print("❌ 发现的主要问题:")
print()

# 问题1: 胜率分析
win_rate = win_trades / total_trades * 100
if win_rate < 50:
    print(f"1. 【胜率偏低】整体胜率仅 {win_rate:.2f}%，低于50%")
    print(f"   建议: 需要优化入场时机，提高交易质量")
else:
    print(f"1. 【胜率尚可】整体胜率 {win_rate:.2f}%")

# 问题2: 盈亏比分析
profit_loss_ratio = abs(avg_profit / avg_loss)
if profit_loss_ratio < 1:
    print(f"\n2. 【盈亏比不佳】盈亏比仅 {profit_loss_ratio:.2f}:1")
    print(f"   说明: 赚的时候赚得少，亏的时候亏得多")
    print(f"   建议: 需要让盈利充分奔跑，及时止损")
elif profit_loss_ratio < 1.5:
    print(f"\n2. 【盈亏比一般】盈亏比 {profit_loss_ratio:.2f}:1")
    print(f"   建议: 可以进一步优化，追求更高的盈亏比")
else:
    print(f"\n2. 【盈亏比良好】盈亏比 {profit_loss_ratio:.2f}:1")

# 问题3: 止损止盈使用
sl_usage_rate = sl_used / total_trades * 100
tp_usage_rate = tp_used / total_trades * 100
if sl_usage_rate < 50:
    print(f"\n3. 【风险管理不足】仅 {sl_usage_rate:.2f}% 的交易设置了止损")
    print(f"   建议: 每笔交易都应该设置止损，保护资金")
if tp_usage_rate < 30:
    print(f"\n4. 【缺少盈利目标】仅 {tp_usage_rate:.2f}% 的交易设置了止盈")
    print(f"   建议: 设置合理的止盈目标，避免盈利回吐")

# 问题4: 持仓时长分析
avg_duration_hours = df['duration'].mean() / 60
if avg_duration_hours < 0.5:
    print(f"\n5. 【过度交易】平均持仓仅 {avg_duration_hours*60:.1f} 分钟")
    print(f"   说明: 交易频率过高，可能是在追逐短期波动")
    print(f"   建议: 增加耐心，等待更好的入场机会")

# 问题5: 连续亏损风险
if max_loss_streak > 5:
    print(f"\n6. 【连续亏损风险】最大连续亏损达到 {max_loss_streak} 次")
    print(f"   说明: 出现连续亏损时没有及时调整策略")
    print(f"   建议: 连续亏损3次以上应该暂停交易，复盘问题")

print()
print("=" * 80)
print("✅ 改进建议")
print("=" * 80)
print()

print("1. 【交易纪律】")
print("   - 每笔交易必须设置止损，风险控制在账户的1-2%")
print("   - 设置合理的止盈目标，盈亏比至少1.5:1")
print("   - 严格执行交易计划，避免情绪化交易")
print()

print("2. 【入场时机】")
print("   - 减少交易频率，只在高概率机会时入场")
print("   - 等待明确的趋势信号，避免在震荡市场频繁交易")
print("   - 结合多个时间周期确认趋势")
print()

print("3. 【仓位管理】")
print("   - 控制单笔仓位，避免重仓交易")
print("   - 根据市场波动性调整仓位大小")
print("   - 盈利后不要急于加大仓位")
print()

print("4. 【风险管理】")
print("   - 设置每日最大亏损限额")
print("   - 连续亏损3次立即停止交易，分析原因")
print("   - 保持良好的交易心态，不追单、不报复性交易")
print()

print("5. 【交易策略】")
if sell_win_rate > buy_win_rate + 5:
    print("   - 你的做空胜率明显高于做多，可以侧重做空策略")
elif buy_win_rate > sell_win_rate + 5:
    print("   - 你的做多胜率明显高于做空，可以侧重做多策略")
else:
    print("   - 做多做空表现相近，建议顺势交易")

# 分析最佳交易时段
best_hours = hour_stats.nlargest(3, ('总盈亏',))
if len(best_hours) > 0:
    print(f"   - 你的最佳交易时段是 UTC {best_hours.index[0]}:00-{best_hours.index[0]+1}:00")
    print(f"     可以重点关注这个时段的交易机会")

print()
print("6. 【具体执行建议】")
print("   - 制定详细的交易计划，记录每次交易的理由")
print("   - 定期复盘，总结成功和失败的交易")
print("   - 使用交易日志，跟踪交易表现")
print("   - 持续学习技术分析和风险管理知识")
print()

# 生成图表
print("正在生成分析图表...")
fig, axes = plt.subplots(2, 3, figsize=(18, 12))

# 图1: 盈亏分布
axes[0, 0].hist(df['profit_usd'], bins=50, color='steelblue', edgecolor='black', alpha=0.7)
axes[0, 0].axvline(0, color='red', linestyle='--', linewidth=2)
axes[0, 0].set_title('盈亏分布', fontsize=14, fontweight='bold')
axes[0, 0].set_xlabel('盈亏 (USD)')
axes[0, 0].set_ylabel('交易次数')
axes[0, 0].grid(alpha=0.3)

# 图2: 做多做空对比
type_data = df.groupby('type')['profit_usd'].sum()
axes[0, 1].bar(type_data.index, type_data.values, color=['green', 'red'], alpha=0.7)
axes[0, 1].set_title('做多/做空总盈亏对比', fontsize=14, fontweight='bold')
axes[0, 1].set_ylabel('总盈亏 (USD)')
axes[0, 1].grid(alpha=0.3)

# 图3: 累计盈亏曲线
df_sorted = df.sort_values('opening_time')
df_sorted['cumulative_profit'] = df_sorted['profit_usd'].cumsum()
axes[0, 2].plot(df_sorted['opening_time'], df_sorted['cumulative_profit'], linewidth=2)
axes[0, 2].set_title('累计盈亏曲线', fontsize=14, fontweight='bold')
axes[0, 2].set_xlabel('时间')
axes[0, 2].set_ylabel('累计盈亏 (USD)')
axes[0, 2].grid(alpha=0.3)
axes[0, 2].tick_params(axis='x', rotation=45)

# 图4: 持仓时长分布
axes[1, 0].hist(df['duration'], bins=50, color='orange', edgecolor='black', alpha=0.7)
axes[1, 0].set_title('持仓时长分布', fontsize=14, fontweight='bold')
axes[1, 0].set_xlabel('持仓时长 (分钟)')
axes[1, 0].set_ylabel('交易次数')
axes[1, 0].grid(alpha=0.3)

# 图5: 交易时段分析
hour_profit = df.groupby('hour')['profit_usd'].sum()
axes[1, 1].bar(hour_profit.index, hour_profit.values, color='teal', alpha=0.7)
axes[1, 1].set_title('各时段盈亏 (UTC时间)', fontsize=14, fontweight='bold')
axes[1, 1].set_xlabel('小时')
axes[1, 1].set_ylabel('总盈亏 (USD)')
axes[1, 1].grid(alpha=0.3)

# 图6: 胜率vs亏损率
win_loss_data = [win_trades, loss_trades, breakeven_trades]
win_loss_labels = ['盈利', '亏损', '持平']
colors_pie = ['#2ecc71', '#e74c3c', '#95a5a6']
axes[1, 2].pie(win_loss_data, labels=win_loss_labels, autopct='%1.1f%%', colors=colors_pie, startangle=90)
axes[1, 2].set_title('交易结果分布', fontsize=14, fontweight='bold')

plt.tight_layout()
plt.savefig('trading_analysis.png', dpi=300, bbox_inches='tight')
print("图表已保存: trading_analysis.png")
print()

print("=" * 80)
print("分析完成！")
print("=" * 80)
