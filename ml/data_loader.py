#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
K线数据加载器
"""
import pandas as pd
import numpy as np
from datetime import datetime

class KlineDataLoader:
    """K线数据加载器"""
    
    def __init__(self, csv_path='../data/raw_klines.csv'):
        self.csv_path = csv_path
        
    def load_data(self):
        """加载数据"""
        print(f"从 {self.csv_path} 加载数据...")
        
        # 读取CSV
        df = pd.read_csv(self.csv_path, sep='\t')  # MySQL导出的是tab分隔
        
        # 数据清洗
        df = self._clean_data(df)
        
        # 时间排序
        df = df.sort_values('timestamp').reset_index(drop=True)
        
        print(f"✅ 加载完成: {len(df)} 条数据")
        return df
    
    def _clean_data(self, df):
        """数据清洗"""
        print("数据清洗中...")
        
        # 删除重复行
        before = len(df)
        df = df.drop_duplicates(subset=['timestamp'])
        after = len(df)
        if before > after:
            print(f"  - 删除重复行: {before - after} 条")
        
        # 删除缺失值
        before = len(df)
        df = df.dropna()
        after = len(df)
        if before > after:
            print(f"  - 删除缺失值: {before - after} 条")
        
        # 转换时间格式
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        
        # 确保价格为正数
        price_cols = ['open_price', 'high_price', 'low_price', 'close_price']
        for col in price_cols:
            before = len(df)
            df = df[df[col] > 0]
            after = len(df)
            if before > after:
                print(f"  - 删除{col}<=0的行: {before - after} 条")
        
        print("✅ 清洗完成")
        return df
    
    def get_train_test_split(self, df, train_ratio=0.8):
        """训练集/测试集划分（时间序列，不能随机划分）"""
        split_idx = int(len(df) * train_ratio)
        
        train_df = df[:split_idx].copy()
        test_df = df[split_idx:].copy()
        
        print(f"\n📊 数据集划分:")
        print(f"训练集: {len(train_df)} 条 ({train_df['timestamp'].min()} ~ {train_df['timestamp'].max()})")
        print(f"测试集: {len(test_df)} 条 ({test_df['timestamp'].min()} ~ {test_df['timestamp'].max()})")
        
        return train_df, test_df

# 测试代码
if __name__ == '__main__':
    loader = KlineDataLoader()
    df = loader.load_data()
    print(f"\n数据预览:")
    print(df.head())
    print(f"\n数据信息:")
    print(df.info())
