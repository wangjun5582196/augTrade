#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析为什么交易系统一直做多单
结合K线数据和技术指标分析
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import sys
import pymysql

# MySQL数据库配置
DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': '12345678',
    'database': 'test',
    'charset': 'utf8mb4'
}

def calculate_ema(prices, period):
    """计算EMA"""
    return prices.ewm(span=period, adjust=False).mean()

def calculate_atr(df, period=14):
    """计算ATR"""
    high = df['high_price']
    low = df['low_price']
    close = df['close_price']
    
    tr1 = high - low
    tr2 = abs(high - close.shift())
    tr3 = abs(low - close.shift())
    
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    atr = tr.rolling(window=period).mean()
    
    return atr

def calculate_adx(df, period=14):
    """计算ADX"""
    high = df['high_price']
    low = df['low_price']
    close = df['close_price']
    
    # 计算+DM和-DM
    plus_dm = high.diff()
    minus_dm = -low.diff()
    
    plus_dm[plus_dm < 0] = 0
    minus_dm[minus_dm < 0] = 0
    
    # 计算TR
    tr1 = high - low
    tr2 = abs(high - close.shift())
    tr3 = abs(low - close.shift())
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    
    # 平滑+DM、-DM和TR
    atr = tr.rolling(window=period).mean()
    plus_di = 100 * (plus_dm.rolling(window=period).mean() / atr)
    minus_di = 100 * (minus_dm.rolling(window=period).mean() / atr)
    
    # 计算DX和ADX
    dx = 100 * abs(plus_di - minus_di) / (plus_di + minus_di)
    adx = dx.rolling(window=period).mean()
    
    return adx, plus_di, minus_di

def main():
    print("=" * 80)
    print("🔍 分析为什么交易系统一直做多单")
    print("=" * 80)
    
    try:
        conn = pymysql.connect(**DB_CONFIG)
        
        # 1. 查询最近的交易记录
        print("\n📊 最近的交易记录：")
        print("-" * 80)
        
        trade_query = """
        SELECT 
            id,
            side,
            executed_price as entry_price,
            executed_price as exit_price,
            profit_loss,
            executed_time as entry_time,
            executed_time as exit_time,
            strategy_name,
            signal_strength,
            market_regime
        FROM t_trade_order
        WHERE executed_time IS NOT NULL
        ORDER BY executed_time DESC
        LIMIT 20
        """
        
        trades_df = pd.read_sql_query(trade_query, conn)
        
        if len(trades_df) == 0:
            print("⚠️ 没有找到交易记录")
            return
        
        # 统计多空比例
        buy_count = len(trades_df[trades_df['side'] == 'BUY'])
        sell_count = len(trades_df[trades_df['side'] == 'SELL'])
        
        print(f"\n📈 交易统计（最近20笔）：")
        print(f"   做多(BUY): {buy_count} 笔 ({buy_count/len(trades_df)*100:.1f}%)")
        print(f"   做空(SELL): {sell_count} 笔 ({sell_count/len(trades_df)*100:.1f}%)")
        
        # 显示最近10笔交易
        print(f"\n最近10笔交易详情：")
        for idx, row in trades_df.head(10).iterrows():
            entry_time = pd.to_datetime(row['entry_time']).strftime('%m-%d %H:%M')
            exit_time = pd.to_datetime(row['exit_time']).strftime('%m-%d %H:%M') if pd.notna(row['exit_time']) else 'N/A'
            side_emoji = "📈" if row['side'] == 'BUY' else "📉"
            profit_emoji = "✅" if pd.notna(row['profit_loss']) and row['profit_loss'] > 0 else "❌"
            
            profit_loss_str = f"{row['profit_loss']:+.2f}" if pd.notna(row['profit_loss']) else 'N/A'
            market_regime_str = row['market_regime'] if pd.notna(row['market_regime']) else 'N/A'
            signal_strength_str = str(row['signal_strength']) if pd.notna(row['signal_strength']) else 'N/A'
            
            print(f"{side_emoji} {entry_time} | {row['side']:4s} | "
                  f"价格:{row['entry_price']:.2f} | "
                  f"{profit_emoji} P/L:{profit_loss_str} | "
                  f"市场:{market_regime_str} | 强度:{signal_strength_str}")
        
        # 2. 查询最近的K线数据和技术指标
        print("\n\n📊 最近K线数据和技术指标分析：")
        print("-" * 80)
        
        kline_query = """
        SELECT 
            timestamp as open_time,
            open_price,
            high_price,
            low_price,
            close_price,
            volume
        FROM t_kline
        WHERE symbol = 'XAUTUSDT'
        AND `interval` = '5m'
        ORDER BY timestamp DESC
        LIMIT 100
        """
        
        klines_df = pd.read_sql_query(kline_query, conn)
        
        if len(klines_df) == 0:
            print("⚠️ 没有找到K线数据")
            return
        
        # 反转数据顺序（从旧到新）
        klines_df = klines_df.iloc[::-1].reset_index(drop=True)
        
        # 计算技术指标
        klines_df['EMA20'] = calculate_ema(klines_df['close_price'], 20)
        klines_df['EMA50'] = calculate_ema(klines_df['close_price'], 50)
        klines_df['ATR'] = calculate_atr(klines_df, 14)
        klines_df['ADX'], klines_df['Plus_DI'], klines_df['Minus_DI'] = calculate_adx(klines_df, 14)
        
        # 判断趋势
        klines_df['Trend'] = 'NEUTRAL'
        klines_df.loc[klines_df['EMA20'] > klines_df['EMA50'], 'Trend'] = 'UPTREND'
        klines_df.loc[klines_df['EMA20'] < klines_df['EMA50'], 'Trend'] = 'DOWNTREND'
        
        # 计算价格与EMA20的偏离度
        klines_df['Price_to_EMA20'] = ((klines_df['close_price'] - klines_df['EMA20']) / klines_df['EMA20'] * 100)
        
        # 获取最近的数据
        recent_klines = klines_df.tail(20)
        
        print("\n最近20根K线的趋势分析：")
        for idx, row in recent_klines.iterrows():
            # open_time可能是字符串或datetime，需要判断
            if isinstance(row['open_time'], str):
                open_time = pd.to_datetime(row['open_time']).strftime('%m-%d %H:%M')
            else:
                open_time = pd.to_datetime(row['open_time'], unit='ms').strftime('%m-%d %H:%M')
            trend_emoji = "📈" if row['Trend'] == 'UPTREND' else ("📉" if row['Trend'] == 'DOWNTREND' else "📊")
            
            print(f"{trend_emoji} {open_time} | 价格:{row['close_price']:.2f} | "
                  f"EMA20:{row['EMA20']:.2f} | EMA50:{row['EMA50']:.2f} | "
                  f"偏离:{row['Price_to_EMA20']:+.2f}% | "
                  f"ADX:{row['ADX']:.1f} | ATR:{row['ATR']:.2f} | {row['Trend']}")
        
        # 3. 分析为什么只做多单
        print("\n\n🔍 核心问题分析：为什么一直做多单？")
        print("=" * 80)
        
        latest = klines_df.iloc[-1]
        
        print(f"\n当前市场状态（最新K线）：")
        print(f"   📊 价格: {latest['close_price']:.2f}")
        print(f"   📊 EMA20: {latest['EMA20']:.2f}")
        print(f"   📊 EMA50: {latest['EMA50']:.2f}")
        print(f"   📊 趋势: {latest['Trend']}")
        print(f"   📊 ADX: {latest['ADX']:.2f}")
        print(f"   📊 ATR: {latest['ATR']:.2f}")
        print(f"   📊 价格偏离EMA20: {latest['Price_to_EMA20']:+.2f}%")
        
        # 统计最近的趋势分布
        recent_50 = klines_df.tail(50)
        uptrend_count = len(recent_50[recent_50['Trend'] == 'UPTREND'])
        downtrend_count = len(recent_50[recent_50['Trend'] == 'DOWNTREND'])
        neutral_count = len(recent_50[recent_50['Trend'] == 'NEUTRAL'])
        
        print(f"\n最近50根K线的趋势统计：")
        print(f"   📈 上涨趋势: {uptrend_count} 根 ({uptrend_count/50*100:.1f}%)")
        print(f"   📉 下跌趋势: {downtrend_count} 根 ({downtrend_count/50*100:.1f}%)")
        print(f"   📊 震荡: {neutral_count} 根 ({neutral_count/50*100:.1f}%)")
        
        # 4. 策略条件分析
        print("\n\n📋 SimplifiedTrendStrategy 策略条件检查：")
        print("-" * 80)
        
        # 买入条件检查
        print("\n✅ 买入(BUY)信号条件：")
        
        is_uptrend = latest['EMA20'] > latest['EMA50']
        adx_ok = latest['ADX'] >= 20
        atr_ok = latest['ATR'] <= 6.0
        price_near_ema20 = -0.5 <= latest['Price_to_EMA20'] <= 0.8
        
        print(f"   1. 上涨趋势 (EMA20 > EMA50): {'✅ 满足' if is_uptrend else '❌ 不满足'}")
        print(f"      EMA20={latest['EMA20']:.2f}, EMA50={latest['EMA50']:.2f}")
        
        print(f"   2. ADX >= 20 (有趋势): {'✅ 满足' if adx_ok else '❌ 不满足'}")
        print(f"      当前ADX={latest['ADX']:.2f}")
        
        print(f"   3. ATR <= 6.0 (波动不太大): {'✅ 满足' if atr_ok else '❌ 不满足'}")
        print(f"      当前ATR={latest['ATR']:.2f}")
        
        print(f"   4. 价格靠近EMA20 (-0.5% ~ +0.8%): {'✅ 满足' if price_near_ema20 else '❌ 不满足'}")
        print(f"      当前偏离度={latest['Price_to_EMA20']:+.2f}%")
        
        buy_signal_ready = is_uptrend and adx_ok and atr_ok and price_near_ema20
        print(f"\n   🎯 买入信号是否就绪: {'✅ 是' if buy_signal_ready else '❌ 否'}")
        
        # 卖出条件检查
        print("\n❌ 卖出(SELL)信号条件：")
        
        is_downtrend = latest['EMA20'] < latest['EMA50']
        price_near_ema20_sell = -0.8 <= latest['Price_to_EMA20'] <= 0.5
        
        print(f"   1. 下跌趋势 (EMA20 < EMA50): {'✅ 满足' if is_downtrend else '❌ 不满足'}")
        print(f"      EMA20={latest['EMA20']:.2f}, EMA50={latest['EMA50']:.2f}")
        
        print(f"   2. ADX >= 20 (有趋势): {'✅ 满足' if adx_ok else '❌ 不满足'}")
        print(f"      当前ADX={latest['ADX']:.2f}")
        
        print(f"   3. ATR <= 6.0 (波动不太大): {'✅ 满足' if atr_ok else '❌ 不满足'}")
        print(f"      当前ATR={latest['ATR']:.2f}")
        
        print(f"   4. 价格靠近EMA20 (-0.8% ~ +0.5%): {'✅ 满足' if price_near_ema20_sell else '❌ 不满足'}")
        print(f"      当前偏离度={latest['Price_to_EMA20']:+.2f}%")
        
        sell_signal_ready = is_downtrend and adx_ok and atr_ok and price_near_ema20_sell
        print(f"\n   🎯 卖出信号是否就绪: {'✅ 是' if sell_signal_ready else '❌ 否'}")
        
        # 5. 结论
        print("\n\n" + "=" * 80)
        print("💡 分析结论：")
        print("=" * 80)
        
        if uptrend_count > downtrend_count * 2:
            print(f"\n1️⃣ **市场处于强势上涨趋势**")
            print(f"   - 最近50根K线中，{uptrend_count}根({uptrend_count/50*100:.1f}%)处于上涨趋势")
            print(f"   - 下跌趋势只有{downtrend_count}根({downtrend_count/50*100:.1f}%)")
            print(f"   - EMA20={latest['EMA20']:.2f} 持续高于 EMA50={latest['EMA50']:.2f}")
        
        if is_uptrend:
            print(f"\n2️⃣ **当前处于上涨趋势，策略只会产生买入信号**")
            print(f"   - SimplifiedTrendStrategy是顺势交易策略")
            print(f"   - 上涨趋势中，只寻找买入机会，不做空")
            print(f"   - 下跌趋势中，只寻找卖出机会，不做多")
        
        print(f"\n3️⃣ **这是策略设计的正常行为**")
        print(f"   - 策略遵循\"顺势而为\"的原则")
        print(f"   - 避免逆势交易，降低风险")
        print(f"   - 只有当EMA20跌破EMA50，才会转为下跌趋势，产生卖出信号")
        
        if latest['ADX'] > 25:
            print(f"\n4️⃣ **当前是强趋势市场（ADX={latest['ADX']:.2f} > 25）**")
            print(f"   - 强趋势中，策略会加强趋势方向的信号")
            print(f"   - 同时会完全禁用逆向信号（上涨趋势禁用卖出，下跌趋势禁用买入）")
        
        # 建议
        print(f"\n\n📝 建议：")
        print("-" * 80)
        
        if is_uptrend and latest['ADX'] > 25:
            print("✅ 当前市场状态适合做多")
            print("   - 强上涨趋势 + 高ADX，顺势做多是正确的策略")
            print("   - 等待价格回调到EMA20附近入场")
        elif is_uptrend and latest['ADX'] < 25:
            print("⚠️ 当前处于弱上涨趋势")
            print("   - ADX较低，趋势不够强")
            print("   - 可以考虑观望，等待趋势加强")
        elif is_downtrend:
            print("📉 如果需要做空信号，需要等待：")
            print("   - EMA20跌破EMA50（下跌趋势确认）")
            print("   - ADX > 20（趋势强度足够）")
            print("   - 价格反弹到EMA20附近（入场时机）")
        else:
            print("📊 当前处于震荡市场")
            print("   - 建议观望，等待明确趋势形成")
        
        # 如果想要更多做空机会的建议
        print("\n\n🔧 如果想增加做空机会：")
        print("-" * 80)
        print("1. 等待市场进入下跌趋势（EMA20 < EMA50）")
        print("2. 或者使用双向策略（如AggressiveScalpingStrategy）")
        print("3. 或者降低趋势判断的敏感度（使用更短期的EMA）")
        print("4. 但要注意：逆势交易风险更高，胜率会降低")
        
        conn.close()
        
    except Exception as e:
        print(f"\n❌ 错误: {e}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    main()
