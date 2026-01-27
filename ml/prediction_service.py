#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ML模型预测服务
提供HTTP API供Java调用
"""
from flask import Flask, request, jsonify
import joblib
import json
import pandas as pd
import numpy as np
import os
import logging

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# 全局变量
model = None
feature_cols = None

def load_model():
    """加载模型"""
    global model, feature_cols
    
    try:
        model_path = '../models/lgbm_model.pkl'
        config_path = '../models/model_config.json'
        
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"模型文件不存在: {model_path}")
        
        if not os.path.exists(config_path):
            raise FileNotFoundError(f"配置文件不存在: {config_path}")
        
        # 加载模型
        model = joblib.load(model_path)
        logger.info(f"✅ 模型加载成功: {model_path}")
        
        # 加载配置
        with open(config_path, 'r') as f:
            config = json.load(f)
        feature_cols = config['feature_cols']
        logger.info(f"✅ 配置加载成功，特征数: {len(feature_cols)}")
        
        return True
    except Exception as e:
        logger.error(f"❌ 模型加载失败: {str(e)}")
        return False

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    if model is None:
        return jsonify({
            'status': 'error',
            'message': '模型未加载'
        }), 500
    
    return jsonify({
        'status': 'ok',
        'model_loaded': True,
        'features_count': len(feature_cols) if feature_cols else 0
    })

@app.route('/predict', methods=['POST'])
def predict():
    """预测接口"""
    try:
        if model is None:
            return jsonify({
                'success': False,
                'error': '模型未加载'
            }), 500
        
        # 获取请求数据
        data = request.json
        
        if not data:
            return jsonify({
                'success': False,
                'error': '请求数据为空'
            }), 400
        
        logger.info(f"收到预测请求，特征数: {len(data)}")
        
        # 转换为DataFrame
        df = pd.DataFrame([data])
        
        # 检查缺失特征
        missing_features = set(feature_cols) - set(df.columns)
        if missing_features:
            logger.warning(f"缺失特征: {missing_features}")
            # 用0填充缺失特征
            for feat in missing_features:
                df[feat] = 0
        
        # 提取特征（按正确顺序）
        X = df[feature_cols].fillna(0)
        
        # 预测
        pred_label = int(model.predict(X)[0])
        pred_proba = model.predict_proba(X)[0]
        
        # 标签映射
        label_names = {-1: '下跌', 0: '震荡', 1: '上涨'}
        
        # 返回结果
        result = {
            'success': True,
            'prediction': {
                'label': pred_label,
                'label_name': label_names.get(pred_label, '未知'),
                'probability': {
                    'down': float(pred_proba[0]),
                    'hold': float(pred_proba[1]),
                    'up': float(pred_proba[2])
                },
                'confidence': float(max(pred_proba))
            }
        }
        
        logger.info(f"预测完成: {label_names.get(pred_label)} (置信度: {max(pred_proba):.2%})")
        
        return jsonify(result)
    
    except Exception as e:
        logger.error(f"预测失败: {str(e)}", exc_info=True)
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/predict/batch', methods=['POST'])
def predict_batch():
    """批量预测接口"""
    try:
        if model is None:
            return jsonify({
                'success': False,
                'error': '模型未加载'
            }), 500
        
        # 获取请求数据（数组）
        data_list = request.json
        
        if not isinstance(data_list, list):
            return jsonify({
                'success': False,
                'error': '批量预测需要数组格式数据'
            }), 400
        
        logger.info(f"收到批量预测请求，样本数: {len(data_list)}")
        
        # 转换为DataFrame
        df = pd.DataFrame(data_list)
        
        # 检查并填充缺失特征
        for feat in feature_cols:
            if feat not in df.columns:
                df[feat] = 0
        
        # 提取特征
        X = df[feature_cols].fillna(0)
        
        # 批量预测
        pred_labels = model.predict(X).tolist()
        pred_probas = model.predict_proba(X).tolist()
        
        # 标签映射
        label_names = {-1: '下跌', 0: '震荡', 1: '上涨'}
        
        # 构建结果
        results = []
        for i, (label, proba) in enumerate(zip(pred_labels, pred_probas)):
            results.append({
                'index': i,
                'label': int(label),
                'label_name': label_names.get(int(label), '未知'),
                'probability': {
                    'down': float(proba[0]),
                    'hold': float(proba[1]),
                    'up': float(proba[2])
                },
                'confidence': float(max(proba))
            })
        
        logger.info(f"批量预测完成: {len(results)} 个样本")
        
        return jsonify({
            'success': True,
            'predictions': results,
            'count': len(results)
        })
    
    except Exception as e:
        logger.error(f"批量预测失败: {str(e)}", exc_info=True)
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/features', methods=['GET'])
def get_features():
    """获取所需特征列表"""
    if feature_cols is None:
        return jsonify({
            'success': False,
            'error': '配置未加载'
        }), 500
    
    return jsonify({
        'success': True,
        'features': feature_cols,
        'count': len(feature_cols)
    })

if __name__ == '__main__':
    print("=" * 60)
    print("  ML预测服务启动中...")
    print("=" * 60)
    
    # 加载模型
    if not load_model():
        print("❌ 模型加载失败，退出")
        exit(1)
    
    print("\n✅ 模型加载成功！")
    print("\n📌 API端点:")
    print("  - 健康检查: http://localhost:5001/health")
    print("  - 单次预测: http://localhost:5001/predict")
    print("  - 批量预测: http://localhost:5001/predict/batch")
    print("  - 特征列表: http://localhost:5001/features")
    print("\n🚀 服务启动...")
    print("=" * 60)
    
    # 启动服务
    app.run(host='0.0.0.0', port=5001, debug=False)
