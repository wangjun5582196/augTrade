#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模型训练主脚本
"""
import pandas as pd
import numpy as np
from lightgbm import LGBMClassifier
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
import joblib
import json
import os

class ModelTrainer:
    """模型训练器"""
    
    def __init__(self):
        self.model = None
        self.feature_cols = None
        
    def train(self, train_df, feature_cols):
        """训练模型"""
        self.feature_cols = feature_cols
        
        # 准备数据
        X_train = train_df[feature_cols].fillna(0)
        y_train = train_df['label']
        
        print(f"\n📊 训练数据: X={X_train.shape}, y={y_train.shape}")
        
        # 类别权重（平衡数据）
        class_counts = y_train.value_counts()
        total = len(y_train)
        weights = {
            -1: total / (3 * class_counts.get(-1, 1)),
            0: total / (3 * class_counts.get(0, 1)),
            1: total / (3 * class_counts.get(1, 1))
        }
        
        print(f"类别权重: {weights}")
        
        # 训练模型
        self.model = LGBMClassifier(
            n_estimators=500,
            learning_rate=0.05,
            max_depth=7,
            num_leaves=31,
            min_child_samples=20,
            subsample=0.8,
            colsample_bytree=0.8,
            class_weight=weights,
            random_state=42,
            n_jobs=-1,
            verbose=-1
        )
        
        print("\n🚀 开始训练...")
        self.model.fit(X_train, y_train)
        print("✅ 训练完成！")
        
        # 训练集准确率
        train_pred = self.model.predict(X_train)
        train_acc = accuracy_score(y_train, train_pred)
        print(f"训练集准确率: {train_acc*100:.2f}%")
        
        # 特征重要性
        self._show_feature_importance(X_train.columns)
        
        return self.model
    
    def evaluate(self, test_df):
        """评估模型"""
        print("\n📈 评估模型...")
        
        X_test = test_df[self.feature_cols].fillna(0)
        y_test = test_df['label']
        
        # 预测
        y_pred = self.model.predict(X_test)
        y_proba = self.model.predict_proba(X_test)
        
        # 评估指标
        print("\n=== 分类报告 ===")
        print(classification_report(y_test, y_pred, 
                                   target_names=['下跌(-1)', '震荡(0)', '上涨(1)'],
                                   zero_division=0))
        
        # 混淆矩阵
        print("\n=== 混淆矩阵 ===")
        print("           预测下跌  预测震荡  预测上涨")
        cm = confusion_matrix(y_test, y_pred, labels=[-1, 0, 1])
        for i, label in enumerate(['实际下跌', '实际震荡', '实际上涨']):
            print(f"{label:8} {cm[i][0]:8} {cm[i][1]:8} {cm[i][2]:8}")
        
        # 准确率
        accuracy = accuracy_score(y_test, y_pred)
        print(f"\n✅ 总准确率: {accuracy*100:.2f}%")
        
        # 保存预测结果
        test_df = test_df.copy()
        test_df['pred_label'] = y_pred
        test_df['pred_proba_down'] = y_proba[:, 0]
        test_df['pred_proba_hold'] = y_proba[:, 1]
        test_df['pred_proba_up'] = y_proba[:, 2]
        test_df['pred_confidence'] = y_proba.max(axis=1)
        
        return test_df
    
    def _show_feature_importance(self, feature_names, top_n=15):
        """显示特征重要性"""
        importance = self.model.feature_importances_
        indices = np.argsort(importance)[::-1][:top_n]
        
        print(f"\n=== Top {top_n} 重要特征 ===")
        for i, idx in enumerate(indices, 1):
            print(f"{i:2}. {feature_names[idx]:20} : {importance[idx]:.4f}")
    
    def save_model(self, model_dir='../models'):
        """保存模型"""
        os.makedirs(model_dir, exist_ok=True)
        
        model_path = os.path.join(model_dir, 'lgbm_model.pkl')
        config_path = os.path.join(model_dir, 'model_config.json')
        
        # 保存模型
        joblib.dump(self.model, model_path)
        print(f"✅ 模型已保存: {model_path}")
        
        # 保存配置
        config = {
            'feature_cols': self.feature_cols,
            'model_type': 'LGBMClassifier',
            'n_classes': 3,
            'n_features': len(self.feature_cols)
        }
        with open(config_path, 'w') as f:
            json.dump(config, f, indent=2)
        print(f"✅ 配置已保存: {config_path}")
    
    def load_model(self, model_dir='../models'):
        """加载模型"""
        model_path = os.path.join(model_dir, 'lgbm_model.pkl')
        config_path = os.path.join(model_dir, 'model_config.json')
        
        self.model = joblib.load(model_path)
        
        with open(config_path, 'r') as f:
            config = json.load(f)
        self.feature_cols = config['feature_cols']
        
        print(f"✅ 模型已加载: {model_path}")
        return self.model

# 主训练流程
def main():
    print("=" * 60)
    print("  ML模型训练流程")
    print("=" * 60)
    
    from data_loader import KlineDataLoader
    from feature_engineering import FeatureEngineer
    
    # Step 1: 加载数据
    print("\n【Step 1/7】加载数据...")
    loader = KlineDataLoader()
    df = loader.load_data()
    
    if len(df) < 1000:
        print("❌ 错误：数据量太少（<1000条），请先导出更多历史数据")
        return
    
    # Step 2: 特征工程
    print("\n【Step 2/7】特征工程...")
    engineer = FeatureEngineer()
    df = engineer.create_features(df)
    df = engineer.create_labels(df, forward_periods=12, threshold=0.003)
    
    # Step 3: 划分训练集/测试集
    print("\n【Step 3/7】划分数据集...")
    train_df, test_df = loader.get_train_test_split(df, train_ratio=0.8)
    
    # Step 4: 训练模型
    print("\n【Step 4/7】训练模型...")
    trainer = ModelTrainer()
    feature_cols = engineer.get_feature_columns()
    trainer.train(train_df, feature_cols)
    
    # Step 5: 评估模型
    print("\n【Step 5/7】评估模型...")
    test_results = trainer.evaluate(test_df)
    
    # Step 6: 保存模型
    print("\n【Step 6/7】保存模型...")
    trainer.save_model()
    
    # Step 7: 保存测试结果
    print("\n【Step 7/7】保存测试结果...")
    output_path = '../data/test_results.csv'
    test_results.to_csv(output_path, index=False)
    print(f"✅ 测试结果已保存: {output_path}")
    
    print("\n" + "=" * 60)
    print("  训练完成！")
    print("=" * 60)
    print("\n📌 下一步:")
    print("  1. 查看测试结果: cat ../data/test_results.csv")
    print("  2. 运行回测验证: python backtest.py")
    print("  3. 启动预测服务: python prediction_service.py")

if __name__ == '__main__':
    main()
