#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
全面交易记录分析 - 识别问题和最近变化
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta

# 读取数据
df = pd.read_csv('01_01_2007-09_02_2026.csv')
df['opening_time_utc'] = pd.to_datetime(df['opening_time_utc'], format='mixed')
df['closing_time_utc'] = pd.to_datetime(df['closing_time_utc'], format='mixed')
df['holding_minutes'] = (df['closing_time_utc'] - df['opening_time_utc']).dt.total_seconds() / 60
df['trade_date'] = df['closing_time_utc'].dt.date

print("=" * 100)
print("全面交易记录分析报告")
print("=" * 100)

# 1. 整体统计
print("\n【一、整体交易统计】")
print(f"总交易次数: {len(df)}")
print(f"交易时间跨度: {df['closing_time_utc'].min()} 至 {df['closing_time_utc'].max()}")
print(f"总盈亏: ${df['profit_usd'].sum():.2f}")

win_trades = df[df['profit_usd'] > 0]
loss_trades = df[df['profit_usd'] < 0]
print(f"\n盈利交易: {len(win_trades)} 次")
print(f"亏损交易: {len(loss_trades)} 次")
print(f"整体胜率: {len(win_trades)/len(df)*100:.2f}%")
print(f"平均盈亏比: {abs(win_trades['profit_usd'].mean() / loss_trades['profit_usd'].mean()):.2f}")

# 2. 时间段划分分析
print("\n【二、时间段对比分析】")

# 定义时间段
recent_7days = df[df['closing_time_utc'] >= datetime(2026, 2, 2)].copy()
recent_30days = df[df['closing_time_utc'] >= datetime(2026, 1, 10)].copy()
older_trades = df[df['closing_time_utc'] < datetime(2026, 1, 10)].copy()

def analyze_period(period_df, period_name):
    if len(period_df) == 0:
        return
    
    print(f"\n{period_name}:")
    print(f"  交易次数: {len(period_df)}")
    print(f"  总盈亏: ${period_df['profit_usd'].sum():.2f}")
    
    win = period_df[period_df['profit_usd'] > 0]
    loss = period_df[period_df['profit_usd'] < 0]
    
    print(f"  胜率: {len(win)/len(period_df)*100:.2f}%")
    
    if len(loss) > 0 and len(win) > 0:
        print(f"  盈亏比: {abs(win['profit_usd'].mean() / loss['profit_usd'].mean()):.2f}")
    
    print(f"  平均持仓时间: {period_df['holding_minutes'].mean():.1f}分钟")
    print(f"  平均手数: {period_df['lots'].mean():.3f}")
    
    # 日均交易次数
    days = (period_df['closing_time_utc'].max() - period_df['closing_time_utc'].min()).days + 1
    print(f"  日均交易次数: {len(period_df)/days:.1f}")
    
    # 止损使用率
    sl_usage = period_df['stop_loss'].notna().sum() / len(period_df) * 100
    print(f"  止损设置率: {sl_usage:.1f}%")
    
    # 平仓原因分布
    print(f"  平仓原因分布:")
    for reason, count in period_df['close_reason'].value_counts().head(5).items():
        print(f"    {reason}: {count} ({count/len(period_df)*100:.1f}%)")

analyze_period(older_trades, "早期交易（2026-01-10之前）")
analyze_period(recent_30days, "最近30天（2026-01-10至今）")
analyze_period(recent_7days, "最近7天（2026-02-02至今）")

# 3. 交易品种分析
print("\n【三、交易品种分析】")
symbol_analysis = df.groupby('symbol').agg({
    'profit_usd': ['count', 'sum', 'mean'],
    'lots': 'mean'
}).round(2)
symbol_analysis.columns = ['交易次数', '总盈亏', '平均盈亏', '平均手数']
symbol_analysis['胜率%'] = df.groupby('symbol').apply(
    lambda x: (x['profit_usd'] > 0).sum() / len(x) * 100
).round(2)
print(symbol_analysis.sort_values('交易次数', ascending=False))

# 4. 关键问题识别
print("\n" + "=" * 100)
print("【四、核心问题识别】")
print("=" * 100)

problems = []

# 问题1: 胜率
overall_winrate = len(win_trades)/len(df)*100
if overall_winrate < 50:
    problems.append(f"1. ❌ 整体胜率过低 ({overall_winrate:.1f}%)")
    
    # 对比最近变化
    if len(recent_7days) > 0:
        recent_winrate = len(recent_7days[recent_7days['profit_usd'] > 0])/len(recent_7days)*100
        if recent_winrate > overall_winrate + 5:
            problems.append(f"   ✓ 最近7天胜率有所改善 ({recent_winrate:.1f}%)")
        elif recent_winrate < overall_winrate - 5:
            problems.append(f"   ✗ 最近7天胜率进一步恶化 ({recent_winrate:.1f}%)")

# 问题2: 盈亏比
avg_win = win_trades['profit_usd'].mean()
avg_loss = abs(loss_trades['profit_usd'].mean())
profit_loss_ratio = avg_win / avg_loss
if profit_loss_ratio < 1.5:
    problems.append(f"\n2. ❌ 盈亏比不理想 ({profit_loss_ratio:.2f})")
    problems.append(f"   平均盈利: ${avg_win:.2f}")
    problems.append(f"   平均亏损: ${avg_loss:.2f}")

# 问题3: 过度交易
daily_trades = df.groupby('trade_date').size()
avg_daily_trades = daily_trades.mean()
if avg_daily_trades > 30:
    problems.append(f"\n3. ❌ 交易过于频繁 (日均{avg_daily_trades:.1f}次)")
    
    if len(recent_7days) > 0:
        recent_daily = len(recent_7days) / 7
        if recent_daily < avg_daily_trades * 0.7:
            problems.append(f"   ✓ 最近7天频率有所降低 (日均{recent_daily:.1f}次)")

# 问题4: 手数使用
if df['lots'].max() > 0.2:
    problems.append(f"\n4. ❌ 手数使用过大 (最大{df['lots'].max():.2f})")
    
    if len(recent_7days) > 0 and recent_7days['lots'].max() < 0.15:
        problems.append(f"   ✓ 最近7天手数控制改善 (最大{recent_7days['lots'].max():.2f})")

# 问题5: 止损使用
sl_usage_rate = df['stop_loss'].notna().sum() / len(df) * 100
if sl_usage_rate < 50:
    problems.append(f"\n5. ❌ 止损设置率不足 ({sl_usage_rate:.1f}%)")
    
    if len(recent_7days) > 0:
        recent_sl_rate = recent_7days['stop_loss'].notna().sum() / len(recent_7days) * 100
        if recent_sl_rate > sl_usage_rate + 10:
            problems.append(f"   ✓ 最近7天止损使用改善 ({recent_sl_rate:.1f}%)")

# 问题6: 持仓时间
avg_holding = df['holding_minutes'].mean()
if avg_holding < 15:
    problems.append(f"\n6. ❌ 平均持仓时间过短 ({avg_holding:.1f}分钟)")
    
    if len(recent_7days) > 0:
        recent_holding = recent_7days['holding_minutes'].mean()
        if recent_holding > avg_holding * 1.5:
            problems.append(f"   ✓ 最近7天持仓时间延长 ({recent_holding:.1f}分钟)")

# 问题7: 爆仓
so_count = len(df[df['close_reason'] == 'so'])
if so_count > 0:
    problems.append(f"\n7. 🔴 发生爆仓 {so_count} 次！严重风险管理问题")
    
    if len(recent_7days) > 0:
        recent_so = len(recent_7days[recent_7days['close_reason'] == 'so'])
        if recent_so == 0:
            problems.append(f"   ✓ 最近7天无爆仓记录")
        else:
            problems.append(f"   ✗ 最近7天仍有{recent_so}次爆仓")

# 问题8: 连续亏损
df_sorted = df.sort_values('closing_time_utc')
df_sorted['is_loss'] = df_sorted['profit_usd'] < 0
df_sorted['loss_streak'] = (df_sorted['is_loss'] != df_sorted['is_loss'].shift()).cumsum()
max_streak = df_sorted[df_sorted['is_loss']].groupby('loss_streak').size().max()
if max_streak > 5:
    problems.append(f"\n8. ❌ 最大连续亏损{max_streak}次，缺乏止损机制")

# 输出所有问题
for problem in problems:
    print(problem)

# 5. 最近变化总结
print("\n" + "=" * 100)
print("【五、最近交易手法变化总结】")
print("=" * 100)

if len(recent_7days) > 0 and len(older_trades) > 0:
    changes = []
    
    # 对比胜率
    old_wr = len(older_trades[older_trades['profit_usd'] > 0])/len(older_trades)*100
    new_wr = len(recent_7days[recent_7days['profit_usd'] > 0])/len(recent_7days)*100
    if abs(new_wr - old_wr) > 5:
        trend = "提升" if new_wr > old_wr else "下降"
        changes.append(f"✦ 胜率{trend}: {old_wr:.1f}% → {new_wr:.1f}%")
    
    # 对比交易频率
    old_freq = len(older_trades) / ((older_trades['closing_time_utc'].max() - older_trades['closing_time_utc'].min()).days + 1)
    new_freq = len(recent_7days) / 7
    if abs(new_freq - old_freq) > 10:
        trend = "增加" if new_freq > old_freq else "减少"
        changes.append(f"✦ 交易频率{trend}: 日均{old_freq:.1f}次 → {new_freq:.1f}次")
    
    # 对比手数
    old_lots = older_trades['lots'].mean()
    new_lots = recent_7days['lots'].mean()
    if abs(new_lots - old_lots) > 0.05:
        trend = "增加" if new_lots > old_lots else "减少"
        changes.append(f"✦ 平均手数{trend}: {old_lots:.3f} → {new_lots:.3f}")
    
    # 对比持仓时间
    old_holding = older_trades['holding_minutes'].mean()
    new_holding = recent_7days['holding_minutes'].mean()
    if abs(new_holding - old_holding) > 10:
        trend = "延长" if new_holding > old_holding else "缩短"
        changes.append(f"✦ 持仓时间{trend}: {old_holding:.1f}分钟 → {new_holding:.1f}分钟")
    
    # 对比止损使用
    old_sl = older_trades['stop_loss'].notna().sum() / len(older_trades) * 100
    new_sl = recent_7days['stop_loss'].notna().sum() / len(recent_7days) * 100
    if abs(new_sl - old_sl) > 10:
        trend = "提高" if new_sl > old_sl else "降低"
        changes.append(f"✦ 止损使用率{trend}: {old_sl:.1f}% → {new_sl:.1f}%")
    
    # 对比盈亏
    old_profit = older_trades['profit_usd'].sum()
    new_profit = recent_7days['profit_usd'].sum()
    changes.append(f"✦ 总盈亏变化: ${old_profit:.2f} (早期) vs ${new_profit:.2f} (最近7天)")
    
    if changes:
        print("\n最近7天相比早期的主要变化:")
        for change in changes:
            print(change)
    else:
        print("\n最近7天的交易手法基本保持不变")
else:
    print("\n数据不足，无法进行对比分析")

# 6. 改进建议
print("\n" + "=" * 100)
print("【六、核心改进建议】")
print("=" * 100)

recommendations = [
    "\n🎯 第一优先级 - 风险控制",
    "   1. 每笔交易必须设置止损，目标100%",
    "   2. 严格控制单笔手数不超过0.1",
    "   3. 单笔风险控制在账户的1-2%",
    "   4. 设置每日最大亏损限额",
    
    "\n🎯 第二优先级 - 提升胜率",
    "   1. 减少交易频率，只做高质量信号",
    "   2. 多时间周期确认入场点",
    "   3. 避免在震荡市频繁交易",
    "   4. 等待明确的趋势信号",
    
    "\n🎯 第三优先级 - 优化盈亏比",
    "   1. 目标盈亏比至少2:1",
    "   2. 使用移动止损保护利润",
    "   3. 让利润充分奔跑",
    "   4. 快速止损，慢速止盈",
    
    "\n🎯 第四优先级 - 心理控制",
    "   1. 连续3次亏损后停止交易",
    "   2. 达到日亏损限额后立即停止",
    "   3. 避免情绪化交易和报复性交易",
    "   4. 保持交易日志，定期复盘",
]

for rec in recommendations:
    print(rec)

# 7. 品种建议
print("\n" + "=" * 100)
print("【七、品种选择建议】")
print("=" * 100)

symbol_perf = df.groupby('symbol').agg({
    'profit_usd': ['count', 'sum', 'mean']
})
symbol_perf.columns = ['交易次数', '总盈亏', '平均盈亏']
symbol_perf['胜率%'] = df.groupby('symbol').apply(
    lambda x: (x['profit_usd'] > 0).sum() / len(x) * 100
)

# 只分析交易次数>10的品种
significant_symbols = symbol_perf[symbol_perf['交易次数'] > 10].copy()
significant_symbols['推荐度'] = significant_symbols.apply(
    lambda x: '🟢 推荐' if x['胜率%'] > 50 and x['总盈亏'] > 0 
    else ('🟡 谨慎' if x['胜率%'] > 45 or x['总盈亏'] > 0
    else '🔴 避免'), axis=1
)

print("\n品种表现排名（交易次数>10）:")
print(significant_symbols.sort_values('总盈亏', ascending=False))

print("\n建议:")
print("🟢 继续交易表现好的品种")
print("🟡 谨慎交易中等表现的品种，优化策略")
print("🔴 暂停交易亏损品种，找出问题所在")

# 保存报告
output_file = f"全面交易分析报告_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
print(f"\n\n报告已生成，建议保存输出内容到文件中进行详细查看")
print("=" * 100)
