#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
特征工程
"""
import pandas as pd
import numpy as np
import ta  # pip install ta

class FeatureEngineer:
    """特征工程"""
    
    def __init__(self):
        pass
    
    def create_features(self, df):
        """创建所有特征"""
        print("\n🔧 特征工程开始...")
        df = df.copy()
        
        # 1. 价格特征
        print("  - 计算价格特征...")
        df = self._add_price_features(df)
        
        # 2. 技术指标
        print("  - 计算技术指标...")
        df = self._add_technical_indicators(df)
        
        # 3. K线形态特征
        print("  - 计算K线形态...")
        df = self._add_candle_patterns(df)
        
        # 4. 时间特征
        print("  - 计算时间特征...")
        df = self._add_time_features(df)
        
        # 删除前100行（指标计算需要历史数据）
        print(f"  - 删除前100行（指标计算缓冲）")
        df = df.iloc[100:].reset_index(drop=True)
        
        print(f"✅ 特征工程完成！生成 {len(df.columns)} 个特征")
        return df
    
    def _add_price_features(self, df):
        """价格相关特征"""
        # 收益率
        df['returns'] = df['close_price'].pct_change()
        df['returns_5'] = df['close_price'].pct_change(5)
        df['returns_10'] = df['close_price'].pct_change(10)
        
        # 波动率（滚动标准差）
        df['volatility_10'] = df['returns'].rolling(10).std()
        df['volatility_20'] = df['returns'].rolling(20).std()
        
        # 价格动量
        df['momentum_5'] = df['close_price'] - df['close_price'].shift(5)
        df['momentum_10'] = df['close_price'] - df['close_price'].shift(10)
        
        # 振幅
        df['amplitude'] = (df['high_price'] - df['low_price']) / df['open_price']
        
        # 实体大小
        df['body_size'] = abs(df['close_price'] - df['open_price']) / df['open_price']
        
        # 上影线/下影线
        df['upper_shadow'] = (df['high_price'] - df[['open_price', 'close_price']].max(axis=1)) / df['open_price']
        df['lower_shadow'] = (df[['open_price', 'close_price']].min(axis=1) - df['low_price']) / df['open_price']
        
        return df
    
    def _add_technical_indicators(self, df):
        """技术指标"""
        high = df['high_price']
        low = df['low_price']
        close = df['close_price']
        volume = df['volume']
        
        # RSI
        df['rsi_14'] = ta.momentum.rsi(close, window=14)
        df['rsi_7'] = ta.momentum.rsi(close, window=7)
        
        # MACD
        macd = ta.trend.MACD(close)
        df['macd'] = macd.macd()
        df['macd_signal'] = macd.macd_signal()
        df['macd_diff'] = macd.macd_diff()
        
        # ADX（重要！）
        adx_indicator = ta.trend.ADXIndicator(high, low, close, window=14)
        df['adx'] = adx_indicator.adx()
        df['adx_pos'] = adx_indicator.adx_pos()
        df['adx_neg'] = adx_indicator.adx_neg()
        
        # Williams R（重要！）
        df['williams_r'] = ta.momentum.williams_r(high, low, close, lbp=14)
        
        # 布林带
        bollinger = ta.volatility.BollingerBands(close, window=20, window_dev=2)
        df['bb_upper'] = bollinger.bollinger_hband()
        df['bb_middle'] = bollinger.bollinger_mavg()
        df['bb_lower'] = bollinger.bollinger_lband()
        df['bb_width'] = bollinger.bollinger_wband()  # 带宽
        df['bb_position'] = (close - df['bb_lower']) / (df['bb_upper'] - df['bb_lower'])  # 价格在带内位置
        
        # ATR
        df['atr'] = ta.volatility.average_true_range(high, low, close, window=14)
        
        # EMA
        df['ema_20'] = ta.trend.ema_indicator(close, window=20)
        df['ema_50'] = ta.trend.ema_indicator(close, window=50)
        df['ema_diff'] = df['ema_20'] - df['ema_50']
        
        # 成交量相关
        df['volume_ma_10'] = df['volume'].rolling(10).mean()
        df['volume_ratio'] = df['volume'] / df['volume_ma_10']
        
        return df
    
    def _add_candle_patterns(self, df):
        """K线形态特征"""
        # 涨跌
        df['is_green'] = (df['close_price'] > df['open_price']).astype(int)
        
        # 连续涨跌
        df['consecutive_green'] = df['is_green'].rolling(3).sum()
        
        # 简化的形态识别
        range_val = df['high_price'] - df['low_price']
        range_val = range_val.replace(0, 0.01)  # 避免除零
        
        df['is_doji'] = (abs(df['close_price'] - df['open_price']) / range_val < 0.1).astype(int)
        
        df['is_hammer'] = ((df['lower_shadow'] > 2 * df['body_size']) & 
                          (df['upper_shadow'] < df['body_size'])).astype(int)
        
        return df
    
    def _add_time_features(self, df):
        """时间特征"""
        df['hour'] = df['timestamp'].dt.hour
        df['day_of_week'] = df['timestamp'].dt.dayofweek
        df['is_trading_hour'] = ((df['hour'] >= 9) & (df['hour'] <= 16)).astype(int)
        
        return df
    
    def create_labels(self, df, forward_periods=12, threshold=0.003):
        """
        创建标签（分类）
        
        Args:
            forward_periods: 向前看多少个周期（12 = 1小时，5分钟K线）
            threshold: 涨跌阈值（0.003 = 0.3%）
        """
        print(f"\n🏷️ 创建标签（向前{forward_periods}期，阈值{threshold*100}%）...")
        df = df.copy()
        
        # 未来收益率
        df['future_return'] = df['close_price'].shift(-forward_periods) / df['close_price'] - 1
        
        # 分类标签
        df['label'] = 0  # 默认震荡
        df.loc[df['future_return'] > threshold, 'label'] = 1   # 上涨
        df.loc[df['future_return'] < -threshold, 'label'] = -1  # 下跌
        
        # 删除最后forward_periods行（没有标签）
        df = df.iloc[:-forward_periods]
        
        print(f"\n📊 标签分布:")
        label_counts = df['label'].value_counts().sort_index()
        print(f"  下跌(-1): {label_counts.get(-1, 0)} 条 ({label_counts.get(-1, 0)/len(df)*100:.1f}%)")
        print(f"  震荡(0):  {label_counts.get(0, 0)} 条 ({label_counts.get(0, 0)/len(df)*100:.1f}%)")
        print(f"  上涨(1):  {label_counts.get(1, 0)} 条 ({label_counts.get(1, 0)/len(df)*100:.1f}%)")
        
        return df
    
    def get_feature_columns(self):
        """获取特征列名"""
        feature_cols = [
            # 价格特征
            'returns', 'returns_5', 'returns_10',
            'volatility_10', 'volatility_20',
            'momentum_5', 'momentum_10',
            'amplitude', 'body_size',
            'upper_shadow', 'lower_shadow',
            
            # 技术指标
            'rsi_14', 'rsi_7',
            'macd', 'macd_signal', 'macd_diff',
            'adx', 'adx_pos', 'adx_neg',
            'williams_r',
            'bb_width', 'bb_position',
            'atr',
            'ema_diff',
            'volume_ratio',
            
            # K线形态
            'is_green', 'consecutive_green',
            'is_doji', 'is_hammer',
            
            # 时间特征
            'hour', 'day_of_week', 'is_trading_hour'
        ]
        
        return feature_cols

# 测试代码
if __name__ == '__main__':
    from data_loader import KlineDataLoader
    
    # 加载数据
    loader = KlineDataLoader()
    df = loader.load_data()
    
    # 特征工程
    engineer = FeatureEngineer()
    df = engineer.create_features(df)
    df = engineer.create_labels(df)
    
    # 查看特征
    print(f"\n📝 特征列表 (共{len(df.columns)}个):")
    for i, col in enumerate(df.columns, 1):
        print(f"{i}. {col}")
    
    print(f"\n数据形状: {df.shape}")
    
    # 保存
    output_path = '../data/featured_data.csv'
    df.to_csv(output_path, index=False)
    print(f"\n✅ 特征数据已保存到 {output_path}")
