#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用户交易记录完整分析脚本
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta

def analyze_trading_records(file_path):
    # 读取CSV文件
    df = pd.read_csv(file_path)
    
    # 转换时间列 (使用混合格式处理不一致的时间格式)
    df['opening_time_utc'] = pd.to_datetime(df['opening_time_utc'], format='mixed')
    df['closing_time_utc'] = pd.to_datetime(df['closing_time_utc'], format='mixed')
    df['duration_minutes'] = (df['closing_time_utc'] - df['opening_time_utc']).dt.total_seconds() / 60
    df['duration_hours'] = df['duration_minutes'] / 60
    
    print('='*100)
    print('交易记录完整分析报告')
    print('='*100)
    print()
    
    # 1. 基本统计
    print('【一、基本统计信息】')
    print('-'*100)
    print(f'总交易笔数: {len(df)} 笔')
    print(f'交易时间范围: {df["opening_time_utc"].min()} 至 {df["closing_time_utc"].max()}')
    time_span = (df['closing_time_utc'].max() - df['opening_time_utc'].min()).days
    print(f'交易时间跨度: {time_span} 天')
    print(f'交易品种: {", ".join(df["symbol"].unique())}')
    print()
    
    # 2. 盈亏统计
    print('【二、盈亏统计】')
    print('-'*100)
    total_profit = df['profit_usd'].sum()
    win_trades = df[df['profit_usd'] > 0]
    loss_trades = df[df['profit_usd'] < 0]
    break_even = df[df['profit_usd'] == 0]
    
    print(f'总盈亏: ${total_profit:.2f} USD')
    print(f'盈利交易: {len(win_trades)} 笔 ({len(win_trades)/len(df)*100:.2f}%), 总盈利: ${win_trades["profit_usd"].sum():.2f}')
    print(f'亏损交易: {len(loss_trades)} 笔 ({len(loss_trades)/len(df)*100:.2f}%), 总亏损: ${loss_trades["profit_usd"].sum():.2f}')
    print(f'持平交易: {len(break_even)} 笔 ({len(break_even)/len(df)*100:.2f}%)')
    print(f'胜率: {len(win_trades)/len(df)*100:.2f}%')
    
    if len(win_trades) > 0:
        print(f'平均盈利: ${win_trades["profit_usd"].mean():.2f}')
    if len(loss_trades) > 0:
        print(f'平均亏损: ${loss_trades["profit_usd"].mean():.2f}')
        if len(win_trades) > 0:
            profit_factor = abs(win_trades["profit_usd"].sum() / loss_trades["profit_usd"].sum())
            print(f'盈亏比(总盈利/总亏损): {profit_factor:.2f}')
            avg_profit_factor = abs(win_trades["profit_usd"].mean() / loss_trades["profit_usd"].mean())
            print(f'平均盈亏比: {avg_profit_factor:.2f}')
    print()
    
    # 3. 极值统计
    print('【三、极值统计】')
    print('-'*100)
    max_profit_idx = df['profit_usd'].idxmax()
    max_loss_idx = df['profit_usd'].idxmin()
    print(f'最大单笔盈利: ${df.loc[max_profit_idx, "profit_usd"]:.2f}')
    print(f'  - 品种: {df.loc[max_profit_idx, "symbol"]}, 方向: {df.loc[max_profit_idx, "type"]}, 手数: {df.loc[max_profit_idx, "lots"]}')
    print(f'最大单笔亏损: ${df.loc[max_loss_idx, "profit_usd"]:.2f}')
    print(f'  - 品种: {df.loc[max_loss_idx, "symbol"]}, 方向: {df.loc[max_loss_idx, "type"]}, 手数: {df.loc[max_loss_idx, "lots"]}')
    
    # 连续盈亏统计
    df['is_win'] = df['profit_usd'] > 0
    df['streak'] = (df['is_win'] != df['is_win'].shift()).cumsum()
    win_streaks = df[df['is_win']].groupby('streak').size()
    loss_streaks = df[~df['is_win']].groupby('streak').size()
    
    if len(win_streaks) > 0:
        print(f'最长连续盈利: {win_streaks.max()} 笔')
    if len(loss_streaks) > 0:
        print(f'最长连续亏损: {loss_streaks.max()} 笔')
    print()
    
    # 4. 按交易方向统计
    print('【四、按交易方向统计】')
    print('-'*100)
    for trade_type in ['buy', 'sell']:
        type_trades = df[df['type'] == trade_type]
        if len(type_trades) > 0:
            type_profit = type_trades['profit_usd'].sum()
            type_win_rate = len(type_trades[type_trades['profit_usd'] > 0]) / len(type_trades) * 100
            type_name = '多单(买入)' if trade_type == 'buy' else '空单(卖出)'
            print(f'{type_name}:')
            print(f'  交易笔数: {len(type_trades)} 笔')
            print(f'  总盈亏: ${type_profit:.2f}')
            print(f'  胜率: {type_win_rate:.2f}%')
            print(f'  平均盈亏: ${type_trades["profit_usd"].mean():.2f}')
    print()
    
    # 5. 按品种统计
    print('【五、按交易品种统计】')
    print('-'*100)
    for symbol in df['symbol'].unique():
        symbol_trades = df[df['symbol'] == symbol]
        symbol_profit = symbol_trades['profit_usd'].sum()
        symbol_win_rate = len(symbol_trades[symbol_trades['profit_usd'] > 0]) / len(symbol_trades) * 100
        print(f'{symbol}:')
        print(f'  交易笔数: {len(symbol_trades)} 笔 ({len(symbol_trades)/len(df)*100:.2f}%)')
        print(f'  总盈亏: ${symbol_profit:.2f}')
        print(f'  胜率: {symbol_win_rate:.2f}%')
        print(f'  平均盈亏: ${symbol_trades["profit_usd"].mean():.2f}')
    print()
    
    # 6. 持仓时长分析
    print('【六、持仓时长分析】')
    print('-'*100)
    print(f'平均持仓时长: {df["duration_minutes"].mean():.2f} 分钟 ({df["duration_hours"].mean():.2f} 小时)')
    print(f'中位数持仓时长: {df["duration_minutes"].median():.2f} 分钟')
    print(f'最短持仓: {df["duration_minutes"].min():.2f} 分钟')
    print(f'最长持仓: {df["duration_minutes"].max():.2f} 分钟 ({df["duration_hours"].max():.2f} 小时)')
    
    # 持仓时长分布
    short_term = df[df['duration_minutes'] <= 5]
    medium_term = df[(df['duration_minutes'] > 5) & (df['duration_minutes'] <= 30)]
    long_term = df[df['duration_minutes'] > 30]
    print(f'\n持仓时长分布:')
    print(f'  超短线(≤5分钟): {len(short_term)} 笔 ({len(short_term)/len(df)*100:.2f}%), 盈亏: ${short_term["profit_usd"].sum():.2f}')
    print(f'  短线(5-30分钟): {len(medium_term)} 笔 ({len(medium_term)/len(df)*100:.2f}%), 盈亏: ${medium_term["profit_usd"].sum():.2f}')
    print(f'  中长线(>30分钟): {len(long_term)} 笔 ({len(long_term)/len(df)*100:.2f}%), 盈亏: ${long_term["profit_usd"].sum():.2f}')
    print()
    
    # 7. 仓位管理分析
    print('【七、仓位管理分析】')
    print('-'*100)
    print('仓位大小分布:')
    lots_distribution = df['lots'].value_counts().sort_index()
    for lots, count in lots_distribution.items():
        lots_trades = df[df['lots'] == lots]
        lots_profit = lots_trades['profit_usd'].sum()
        print(f'  {lots} 手: {count} 笔 ({count/len(df)*100:.2f}%), 盈亏: ${lots_profit:.2f}')
    print()
    
    # 8. 平仓原因分析
    print('【八、平仓原因分析】')
    print('-'*100)
    close_reasons = df['close_reason'].value_counts()
    for reason, count in close_reasons.items():
        reason_trades = df[df['close_reason'] == reason]
        reason_profit = reason_trades['profit_usd'].sum()
        reason_name_map = {
            'user': '手动平仓',
            'sl': '止损触发',
            'tp': '止盈触发',
            'so': '强制平仓(爆仓)'
        }
        reason_name = reason_name_map.get(reason, reason)
        print(f'{reason_name}: {count} 笔 ({count/len(df)*100:.2f}%), 盈亏: ${reason_profit:.2f}')
    print()
    
    # 9. 风控使用情况
    print('【九、风控设置使用情况】')
    print('-'*100)
    has_sl = df['stop_loss'].notna().sum()
    has_tp = df['take_profit'].notna().sum()
    has_both = df[(df['stop_loss'].notna()) & (df['take_profit'].notna())].shape[0]
    has_none = df[(df['stop_loss'].isna()) & (df['take_profit'].isna())].shape[0]
    
    print(f'设置止损的交易: {has_sl} 笔 ({has_sl/len(df)*100:.2f}%)')
    print(f'设置止盈的交易: {has_tp} 笔 ({has_tp/len(df)*100:.2f}%)')
    print(f'同时设置止损止盈: {has_both} 笔 ({has_both/len(df)*100:.2f}%)')
    print(f'未设置任何风控: {has_none} 笔 ({has_none/len(df)*100:.2f}%)')
    
    sl_triggered = df[df['close_reason'] == 'sl']
    tp_triggered = df[df['close_reason'] == 'tp']
    so_triggered = df[df['close_reason'] == 'so']
    print(f'\n止损被触发: {len(sl_triggered)} 笔, 总亏损: ${sl_triggered["profit_usd"].sum():.2f}')
    print(f'止盈被触发: {len(tp_triggered)} 笔, 总盈利: ${tp_triggered["profit_usd"].sum():.2f}')
    if len(so_triggered) > 0:
        print(f'⚠️ 强制平仓(爆仓): {len(so_triggered)} 笔, 总亏损: ${so_triggered["profit_usd"].sum():.2f}')
    print()
    
    # 10. 交易频率分析
    print('【十、交易频率分析】')
    print('-'*100)
    df['date'] = df['opening_time_utc'].dt.date
    trades_per_day = df.groupby('date').size()
    print(f'平均每天交易: {trades_per_day.mean():.2f} 笔')
    print(f'最多单日交易: {trades_per_day.max()} 笔')
    print(f'最少单日交易: {trades_per_day.min()} 笔')
    
    # 活跃交易日
    print(f'\n交易最频繁的5天:')
    top_days = trades_per_day.nlargest(5)
    for date, count in top_days.items():
        day_profit = df[df['date'] == date]['profit_usd'].sum()
        print(f'  {date}: {count} 笔, 盈亏: ${day_profit:.2f}')
    print()
    
    # 11. 交易手法特征分析
    print('【十一、交易手法特征分析】')
    print('-'*100)
    
    # 频繁交易特征
    print('1. 交易风格:')
    scalping = df[df['duration_minutes'] <= 10]
    print(f'   超高频交易(≤10分钟): {len(scalping)} 笔 ({len(scalping)/len(df)*100:.2f}%)')
    print(f'   说明: {"典型的超短线/剥头皮交易风格" if len(scalping)/len(df) > 0.5 else "较为稳健的交易风格"}')
    
    # 重仓交易
    print(f'\n2. 仓位管理特征:')
    heavy_lots = df[df['lots'] >= 0.15]
    very_heavy = df[df['lots'] >= 0.5]
    print(f'   大仓位交易(≥0.15手): {len(heavy_lots)} 笔 ({len(heavy_lots)/len(df)*100:.2f}%)')
    print(f'   极重仓位(≥0.5手): {len(very_heavy)} 笔 ({len(very_heavy)/len(df)*100:.2f}%)')
    if len(very_heavy) > 0:
        print(f'   极重仓总盈亏: ${very_heavy["profit_usd"].sum():.2f}')
    
    # 是否使用马丁格尔
    print(f'\n3. 加仓行为分析:')
    # 检查短时间内连续开仓
    df_sorted = df.sort_values('opening_time_utc')
    df_sorted['time_diff'] = df_sorted['opening_time_utc'].diff().dt.total_seconds()
    rapid_entries = df_sorted[df_sorted['time_diff'] <= 60]  # 1分钟内开仓
    print(f'   1分钟内快速开仓: {len(rapid_entries)} 笔')
    
    # 检查同方向连续加仓
    consecutive_same_dir = 0
    for i in range(1, len(df_sorted)):
        if (df_sorted.iloc[i]['type'] == df_sorted.iloc[i-1]['type'] and 
            df_sorted.iloc[i]['time_diff'] <= 300):  # 5分钟内
            consecutive_same_dir += 1
    print(f'   5分钟内同方向连续开仓: {consecutive_same_dir} 次')
    print(f'   说明: {"存在明显的加仓/摊平成本行为" if consecutive_same_dir > 20 else "较少使用加仓策略"}')
    print()
    
    # 12. 风险指标分析
    print('【十二、风险指标分析】')
    print('-'*100)
    
    # 最大回撤
    df_sorted = df.sort_values('closing_time_utc')
    df_sorted['cumulative_profit'] = df_sorted['profit_usd'].cumsum()
    df_sorted['cumulative_max'] = df_sorted['cumulative_profit'].cummax()
    df_sorted['drawdown'] = df_sorted['cumulative_profit'] - df_sorted['cumulative_max']
    max_drawdown = df_sorted['drawdown'].min()
    
    print(f'最大回撤: ${max_drawdown:.2f}')
    
    # 风险收益比
    if max_drawdown < 0:
        risk_reward_ratio = total_profit / abs(max_drawdown)
        print(f'风险收益比(总盈亏/最大回撤): {risk_reward_ratio:.2f}')
    
    # 爆仓风险
    margin_calls = df[df['close_reason'] == 'so']
    if len(margin_calls) > 0:
        print(f'\n⚠️ 警告: 发现 {len(margin_calls)} 次强制平仓(爆仓)事件!')
        print(f'   爆仓总损失: ${margin_calls["profit_usd"].sum():.2f}')
    print()
    
    # 13. 交易时间分析
    print('【十三、交易时间偏好分析】')
    print('-'*100)
    df['hour'] = df['opening_time_utc'].dt.hour
    trades_by_hour = df.groupby('hour').agg({
        'profit_usd': ['count', 'sum', 'mean']
    }).round(2)
    
    print('按小时统计(UTC时间):')
    top_hours = df.groupby('hour')['profit_usd'].sum().nlargest(5)
    for hour, profit in top_hours.items():
        hour_count = len(df[df['hour'] == hour])
        print(f'  {hour:02d}:00 - {count} 笔, 盈亏: ${profit:.2f}')
    print()
    
    # 14. 盈亏分布分析
    print('【十四、盈亏分布分析】')
    print('-'*100)
    
    # 盈利分布
    print('盈利交易分布:')
    if len(win_trades) > 0:
        small_win = win_trades[win_trades['profit_usd'] <= 20]
        medium_win = win_trades[(win_trades['profit_usd'] > 20) & (win_trades['profit_usd'] <= 50)]
        large_win = win_trades[(win_trades['profit_usd'] > 50) & (win_trades['profit_usd'] <= 100)]
        huge_win = win_trades[win_trades['profit_usd'] > 100]
        
        print(f'  小赢(≤$20): {len(small_win)} 笔, ${small_win["profit_usd"].sum():.2f}')
        print(f'  中赢($20-50): {len(medium_win)} 笔, ${medium_win["profit_usd"].sum():.2f}')
        print(f'  大赢($50-100): {len(large_win)} 笔, ${large_win["profit_usd"].sum():.2f}')
        print(f'  巨赢(>$100): {len(huge_win)} 笔, ${huge_win["profit_usd"].sum():.2f}')
    
    # 亏损分布
    print('\n亏损交易分布:')
    if len(loss_trades) > 0:
        small_loss = loss_trades[loss_trades['profit_usd'] >= -20]
        medium_loss = loss_trades[(loss_trades['profit_usd'] < -20) & (loss_trades['profit_usd'] >= -50)]
        large_loss = loss_trades[(loss_trades['profit_usd'] < -50) & (loss_trades['profit_usd'] >= -100)]
        huge_loss = loss_trades[loss_trades['profit_usd'] < -100]
        
        print(f'  小亏(≥-$20): {len(small_loss)} 笔, ${small_loss["profit_usd"].sum():.2f}')
        print(f'  中亏(-$20至-50): {len(medium_loss)} 笔, ${medium_loss["profit_usd"].sum():.2f}')
        print(f'  大亏(-$50至-100): {len(large_loss)} 笔, ${large_loss["profit_usd"].sum():.2f}')
        print(f'  巨亏(<-$100): {len(huge_loss)} 笔, ${huge_loss["profit_usd"].sum():.2f}')
    print()
    
    # 15. 交易模式识别
    print('【十五、交易模式识别】')
    print('-'*100)
    
    # 识别频繁反向操作
    df_sorted = df.sort_values('opening_time_utc').reset_index(drop=True)
    direction_changes = 0
    for i in range(1, len(df_sorted)):
        if df_sorted.iloc[i]['type'] != df_sorted.iloc[i-1]['type']:
            time_diff = (df_sorted.iloc[i]['opening_time_utc'] - df_sorted.iloc[i-1]['opening_time_utc']).total_seconds()
            if time_diff <= 300:  # 5分钟内
                direction_changes += 1
    
    print(f'5分钟内频繁换向操作: {direction_changes} 次')
    print(f'说明: {"存在频繁追涨杀跌现象" if direction_changes > 50 else "方向判断相对稳定"}')
    
    # 识别报复性交易
    print(f'\n报复性交易特征:')
    loss_followed_by_heavy = 0
    for i in range(1, len(df_sorted)):
        if (df_sorted.iloc[i-1]['profit_usd'] < -50 and 
            df_sorted.iloc[i]['lots'] > df_sorted.iloc[i-1]['lots'] and
            (df_sorted.iloc[i]['opening_time_utc'] - df_sorted.iloc[i-1]['closing_time_utc']).total_seconds() <= 60):
            loss_followed_by_heavy += 1
    
    print(f'  大亏后立即加仓次数: {loss_followed_by_heavy} 次')
    print(f'  说明: {"⚠️ 存在情绪化交易倾向" if loss_followed_by_heavy > 5 else "情绪控制良好"}')
    print()
    
    return df

if __name__ == '__main__':
    df = analyze_trading_records('01_01_2007-06_02_2026.csv')
    
    print('='*100)
    print('分析完成!')
    print('='*100)
