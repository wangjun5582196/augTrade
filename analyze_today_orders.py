#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
今日订单分析 - 结合信号指标分析
"""

import pymysql
import pandas as pd
from datetime import datetime, date
import warnings
warnings.filterwarnings('ignore')

# 数据库连接配置
DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': '12345678',
    'database': 'test',
    'charset': 'utf8mb4'
}

def connect_db():
    """连接数据库"""
    try:
        conn = pymysql.connect(**DB_CONFIG)
        print(f"✅ 成功连接到数据库: {DB_CONFIG['database']}")
        return conn
    except Exception as e:
        print(f"❌ 数据库连接失败: {e}")
        return None

def get_today_orders(conn):
    """获取今天的所有订单"""
    today = date.today()
    
    query = """
    SELECT 
        id,
        order_no,
        symbol,
        order_type,
        side,
        price,
        executed_price,
        quantity,
        executed_quantity,
        status,
        strategy_name,
        take_profit_price,
        stop_loss_price,
        profit_loss,
        fee,
        williams_r,
        adx,
        ema20,
        ema50,
        atr,
        candle_pattern,
        candle_pattern_strength,
        bollinger_upper,
        bollinger_middle,
        bollinger_lower,
        signal_strength,
        signal_score,
        market_regime,
        ml_prediction,
        ml_confidence,
        remark,
        create_time,
        executed_time
    FROM t_trade_order
    WHERE DATE(create_time) = %s
    ORDER BY create_time DESC
    """
    
    try:
        df = pd.read_sql(query, conn, params=(today,))
        return df
    except Exception as e:
        print(f"❌ 查询订单失败: {e}")
        return pd.DataFrame()

def analyze_signal_indicators(df):
    """分析信号指标"""
    if df.empty:
        print("\n❌ 今日暂无订单数据")
        return
    
    print("\n" + "=" * 100)
    print(f"📊 今日订单分析报告 - {datetime.now().strftime('%Y年%m月%d日 %H:%M:%S')}")
    print("=" * 100)
    
    # 1. 基本统计
    print("\n【一、订单基本信息】")
    print(f"总订单数: {len(df)}")
    print(f"买单(BUY): {len(df[df['side'] == 'BUY'])}")
    print(f"卖单(SELL): {len(df[df['side'] == 'SELL'])}")
    print(f"已成交: {len(df[df['status'] == 'FILLED'])}")
    print(f"待成交: {len(df[df['status'] == 'PENDING'])}")
    print(f"已取消: {len(df[df['status'] == 'CANCELLED'])}")
    print(f"失败: {len(df[df['status'] == 'FAILED'])}")
    
    # 策略分布
    if 'strategy_name' in df.columns and df['strategy_name'].notna().any():
        print(f"\n策略分布:")
        for strategy, count in df['strategy_name'].value_counts().items():
            print(f"  {strategy}: {count} 单 ({count/len(df)*100:.1f}%)")
    
    # 2. 盈亏分析（仅针对已成交订单）
    filled_orders = df[df['status'] == 'FILLED'].copy()
    if not filled_orders.empty:
        print("\n【二、盈亏分析（已成交订单）】")
        print(f"已成交订单: {len(filled_orders)}")
        
        # 盈亏统计
        filled_orders['profit_loss'] = pd.to_numeric(filled_orders['profit_loss'], errors='coerce')
        profit_orders = filled_orders[filled_orders['profit_loss'] > 0]
        loss_orders = filled_orders[filled_orders['profit_loss'] < 0]
        
        total_profit_loss = filled_orders['profit_loss'].sum()
        print(f"总盈亏: ${total_profit_loss:.2f}")
        print(f"盈利订单: {len(profit_orders)} ({len(profit_orders)/len(filled_orders)*100:.1f}%)")
        print(f"亏损订单: {len(loss_orders)} ({len(loss_orders)/len(filled_orders)*100:.1f}%)")
        
        if len(profit_orders) > 0:
            print(f"平均盈利: ${profit_orders['profit_loss'].mean():.2f}")
            print(f"最大盈利: ${profit_orders['profit_loss'].max():.2f}")
        
        if len(loss_orders) > 0:
            print(f"平均亏损: ${loss_orders['profit_loss'].mean():.2f}")
            print(f"最大亏损: ${loss_orders['profit_loss'].min():.2f}")
        
        if len(profit_orders) > 0 and len(loss_orders) > 0:
            profit_loss_ratio = abs(profit_orders['profit_loss'].mean() / loss_orders['profit_loss'].mean())
            print(f"盈亏比: {profit_loss_ratio:.2f}:1")
    
    # 3. 技术指标分析
    print("\n【三、技术指标分析】")
    
    # 过滤有指标数据的订单
    indicator_orders = df.copy()
    
    # Williams %R 分析
    if 'williams_r' in indicator_orders.columns:
        wr_data = pd.to_numeric(indicator_orders['williams_r'], errors='coerce').dropna()
        if len(wr_data) > 0:
            print(f"\n📈 Williams %R 指标:")
            print(f"  平均值: {wr_data.mean():.2f}")
            print(f"  最小值: {wr_data.min():.2f}")
            print(f"  最大值: {wr_data.max():.2f}")
            
            # 信号分类
            oversold = len(wr_data[wr_data < -80])
            overbought = len(wr_data[wr_data > -20])
            neutral = len(wr_data[(wr_data >= -80) & (wr_data <= -20)])
            
            print(f"  超卖区(<-80): {oversold} 单")
            print(f"  超买区(>-20): {overbought} 单")
            print(f"  中性区: {neutral} 单")
    
    # ADX 趋势强度分析
    if 'adx' in indicator_orders.columns:
        adx_data = pd.to_numeric(indicator_orders['adx'], errors='coerce').dropna()
        if len(adx_data) > 0:
            print(f"\n📊 ADX 趋势强度指标:")
            print(f"  平均值: {adx_data.mean():.2f}")
            print(f"  最小值: {adx_data.min():.2f}")
            print(f"  最大值: {adx_data.max():.2f}")
            
            # 趋势分类
            weak = len(adx_data[adx_data < 20])
            moderate = len(adx_data[(adx_data >= 20) & (adx_data < 40)])
            strong = len(adx_data[adx_data >= 40])
            
            print(f"  弱趋势(<20): {weak} 单")
            print(f"  中等趋势(20-40): {moderate} 单")
            print(f"  强趋势(>=40): {strong} 单")
    
    # ATR 波动率分析
    if 'atr' in indicator_orders.columns:
        atr_data = pd.to_numeric(indicator_orders['atr'], errors='coerce').dropna()
        if len(atr_data) > 0:
            print(f"\n📉 ATR 波动率指标:")
            print(f"  平均值: {atr_data.mean():.2f}")
            print(f"  最小值: {atr_data.min():.2f}")
            print(f"  最大值: {atr_data.max():.2f}")
    
    # 4. 市场状态分析
    if 'market_regime' in indicator_orders.columns:
        regime_data = indicator_orders['market_regime'].dropna()
        if len(regime_data) > 0:
            print(f"\n🌍 市场状态分布:")
            for regime, count in regime_data.value_counts().items():
                print(f"  {regime}: {count} 单 ({count/len(regime_data)*100:.1f}%)")
    
    # 5. K线形态分析
    if 'candle_pattern' in indicator_orders.columns:
        pattern_data = indicator_orders['candle_pattern'].dropna()
        if len(pattern_data) > 0:
            print(f"\n🕯️ K线形态分布:")
            for pattern, count in pattern_data.value_counts().head(10).items():
                print(f"  {pattern}: {count} 单")
            
            # 形态强度分析
            if 'candle_pattern_strength' in indicator_orders.columns:
                strength_data = pd.to_numeric(indicator_orders['candle_pattern_strength'], errors='coerce').dropna()
                if len(strength_data) > 0:
                    print(f"\n  平均形态强度: {strength_data.mean():.2f}/10")
    
    # 6. 信号质量分析
    if 'signal_strength' in indicator_orders.columns or 'signal_score' in indicator_orders.columns:
        print(f"\n⚡ 信号质量分析:")
        
        if 'signal_strength' in indicator_orders.columns:
            signal_strength = pd.to_numeric(indicator_orders['signal_strength'], errors='coerce').dropna()
            if len(signal_strength) > 0:
                print(f"  平均信号强度: {signal_strength.mean():.2f}/100")
                print(f"  最高信号强度: {signal_strength.max():.2f}")
                print(f"  最低信号强度: {signal_strength.min():.2f}")
        
        if 'signal_score' in indicator_orders.columns:
            signal_score = pd.to_numeric(indicator_orders['signal_score'], errors='coerce').dropna()
            if len(signal_score) > 0:
                print(f"  平均信号得分: {signal_score.mean():.2f}")
    
    # 7. ML预测分析
    if 'ml_prediction' in indicator_orders.columns:
        ml_pred = pd.to_numeric(indicator_orders['ml_prediction'], errors='coerce').dropna()
        if len(ml_pred) > 0:
            print(f"\n🤖 ML预测分析:")
            print(f"  平均预测值: {ml_pred.mean():.4f}")
            print(f"  最高预测值: {ml_pred.max():.4f}")
            print(f"  最低预测值: {ml_pred.min():.4f}")
            
            if 'ml_confidence' in indicator_orders.columns:
                ml_conf = pd.to_numeric(indicator_orders['ml_confidence'], errors='coerce').dropna()
                if len(ml_conf) > 0:
                    print(f"  平均置信度: {ml_conf.mean():.4f}")
    
    # 8. 布林带分析
    if all(col in indicator_orders.columns for col in ['bollinger_upper', 'bollinger_middle', 'bollinger_lower']):
        bb_orders = indicator_orders.dropna(subset=['bollinger_upper', 'bollinger_middle', 'bollinger_lower'])
        if not bb_orders.empty:
            print(f"\n📊 布林带分析:")
            print(f"  有布林带数据的订单: {len(bb_orders)}")
            
            # 分析价格与布林带的关系
            bb_orders['price_num'] = pd.to_numeric(bb_orders['price'], errors='coerce')
            bb_orders['bb_upper'] = pd.to_numeric(bb_orders['bollinger_upper'], errors='coerce')
            bb_orders['bb_middle'] = pd.to_numeric(bb_orders['bollinger_middle'], errors='coerce')
            bb_orders['bb_lower'] = pd.to_numeric(bb_orders['bollinger_lower'], errors='coerce')
            
            valid_bb = bb_orders.dropna(subset=['price_num', 'bb_upper', 'bb_middle', 'bb_lower'])
            if not valid_bb.empty:
                above_upper = len(valid_bb[valid_bb['price_num'] > valid_bb['bb_upper']])
                between = len(valid_bb[(valid_bb['price_num'] >= valid_bb['bb_lower']) & 
                                      (valid_bb['price_num'] <= valid_bb['bb_upper'])])
                below_lower = len(valid_bb[valid_bb['price_num'] < valid_bb['bb_lower']])
                
                print(f"  价格在上轨之上: {above_upper} 单")
                print(f"  价格在轨道之间: {between} 单")
                print(f"  价格在下轨之下: {below_lower} 单")
    
    # 9. 详细订单列表
    print("\n【四、订单详细列表】")
    print("-" * 100)
    
    for idx, row in df.iterrows():
        print(f"\n订单 #{row['id']} - {row['order_no']}")
        print(f"时间: {row['create_time']}")
        print(f"交易对: {row['symbol']} | 方向: {row['side']} | 状态: {row['status']}")
        print(f"价格: ${row['price']:.2f} | 数量: {row['quantity']}")
        
        if pd.notna(row['executed_price']):
            print(f"成交价: ${row['executed_price']:.2f}")
        
        if 'strategy_name' in row and pd.notna(row['strategy_name']):
            print(f"策略: {row['strategy_name']}")
        
        # 信号指标
        indicators = []
        if pd.notna(row.get('williams_r')):
            indicators.append(f"Williams R: {row['williams_r']:.2f}")
        if pd.notna(row.get('adx')):
            indicators.append(f"ADX: {row['adx']:.2f}")
        if pd.notna(row.get('atr')):
            indicators.append(f"ATR: {row['atr']:.2f}")
        if pd.notna(row.get('signal_strength')):
            indicators.append(f"信号强度: {row['signal_strength']}")
        if pd.notna(row.get('market_regime')):
            indicators.append(f"市场状态: {row['market_regime']}")
        
        if indicators:
            print(f"指标: {' | '.join(indicators)}")
        
        if pd.notna(row.get('profit_loss')):
            profit_loss = float(row['profit_loss'])
            status_icon = "✅" if profit_loss > 0 else "❌" if profit_loss < 0 else "➖"
            print(f"盈亏: {status_icon} ${profit_loss:.2f}")
        
        if pd.notna(row.get('remark')):
            print(f"备注: {row['remark']}")
        
        print("-" * 100)
    
    # 10. 综合分析与建议
    print("\n【五、综合分析与建议】")
    
    if not filled_orders.empty:
        # 胜率分析
        win_rate = len(profit_orders) / len(filled_orders) * 100 if len(filled_orders) > 0 else 0
        
        if win_rate < 40:
            print("\n⚠️ 今日胜率较低，建议:")
            print("  1. 检查信号质量，提高入场标准")
            print("  2. 等待更强的趋势确认")
            print("  3. 避免在震荡市场频繁交易")
        elif win_rate > 60:
            print("\n✅ 今日胜率良好，继续保持!")
        
        # 指标综合评估
        if 'adx' in indicator_orders.columns:
            adx_avg = pd.to_numeric(indicator_orders['adx'], errors='coerce').mean()
            if pd.notna(adx_avg) and adx_avg < 20:
                print("\n⚠️ 平均ADX较低，市场趋势不明显:")
                print("  建议减少交易频率，等待更强的趋势信号")
        
        # 波动率评估
        if 'atr' in indicator_orders.columns:
            atr_avg = pd.to_numeric(indicator_orders['atr'], errors='coerce').mean()
            if pd.notna(atr_avg):
                if atr_avg > 10:
                    print(f"\n⚠️ 市场波动较大(ATR={atr_avg:.2f}):")
                    print("  建议适当增加止损距离，避免被扫损")
                elif atr_avg < 2:
                    print(f"\n📊 市场波动较小(ATR={atr_avg:.2f}):")
                    print("  可能不适合短线交易，建议等待波动增大")
    
    print("\n" + "=" * 100)
    print("分析完成!")
    print("=" * 100)

def main():
    print("开始分析今日订单...")
    print("=" * 100)
    
    # 连接数据库
    conn = connect_db()
    if conn is None:
        return
    
    try:
        # 获取今日订单
        df = get_today_orders(conn)
        
        # 分析信号指标
        analyze_signal_indicators(df)
        
    finally:
        conn.close()
        print("\n✅ 数据库连接已关闭")

if __name__ == "__main__":
    main()
