# ML预测辅助完整实施指南
**方向1：AI预测辅助传统策略**  
**适合人群**: 希望渐进式升级、保留策略可解释性  
**预期收益**: 提升10-20%胜率  
**实施时间**: 1-2个月

---

## 📋 目录

1. [总体思路](#总体思路)
2. [数据准备](#数据准备)
3. [特征工程](#特征工程)
4. [模型训练](#模型训练)
5. [模型评估](#模型评估)
6. [集成到系统](#集成到系统)
7. [实战代码](#实战代码)

---

## 🎯 一、总体思路

### 1.1 工作流程

```
┌─────────────────┐
│  历史K线数据     │
│  (数据库)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  特征工程        │
│  (技术指标)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  标签生成        │
│  (未来收益)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  模型训练        │
│  (XGBoost)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  模型评估        │
│  (回测验证)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  集成到策略      │
│  (Java调用)     │
└─────────────────┘
```

### 1.2 预测目标

**选择1：分类问题（推荐）**
```python
# 预测未来1小时（12个5分钟K线）的价格变化方向
Label:
  - 1: 上涨（涨幅 > 0.3%）
  - 0: 震荡（-0.3% ~ 0.3%）
  - -1: 下跌（跌幅 < -0.3%）
```

**选择2：回归问题**
```python
# 预测未来1小时的收益率
Label: float（-1.0 ~ 1.0，代表-100%~100%）
```

**建议**: 从分类问题开始，更容易理解和使用

---

## 📊 二、数据准备

### 2.1 从数据库提取数据

**SQL脚本**：

```sql
-- 1. 提取历史K线数据（最近6个月）
SELECT 
    id,
    symbol,
    timestamp,
    open_price,
    high_price,
    low_price,
    close_price,
    volume
FROM t_kline
WHERE 
    symbol = 'XAUTUSDT'
    AND timestamp >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
ORDER BY timestamp ASC;
```

**保存到CSV**：
```bash
# 在项目根目录执行
source ~/.bash_profile && mysql -h localhost -P 3306 -u root -p'12345678' test -e "
SELECT 
    timestamp,
    open_price,
    high_price,
    low_price,
    close_price,
    volume
FROM t_kline
WHERE 
    symbol = 'XAUTUSDT'
    AND timestamp >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
ORDER BY timestamp ASC
" > data/raw_klines.csv
```

### 2.2 Python数据加载

创建文件：`ml/data_loader.py`

```python
import pandas as pd
import numpy as np
from datetime import datetime

class KlineDataLoader:
    """K线数据加载器"""
    
    def __init__(self, csv_path='data/raw_klines.csv'):
        self.csv_path = csv_path
        
    def load_data(self):
        """加载数据"""
        # 读取CSV
        df = pd.read_csv(self.csv_path)
        
        # 数据清洗
        df = self._clean_data(df)
        
        # 时间排序
        df = df.sort_values('timestamp').reset_index(drop=True)
        
        return df
    
    def _clean_data(self, df):
        """数据清洗"""
        # 删除重复行
        df = df.drop_duplicates(subset=['timestamp'])
        
        # 删除缺失值
        df = df.dropna()
        
        # 转换时间格式
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        
        # 确保价格为正数
        price_cols = ['open_price', 'high_price', 'low_price', 'close_price']
        for col in price_cols:
            df = df[df[col] > 0]
        
        return df
    
    def get_train_test_split(self, df, train_ratio=0.8):
        """训练集/测试集划分"""
        split_idx = int(len(df) * train_ratio)
        
        train_df = df[:split_idx].copy()
        test_df = df[split_idx:].copy()
        
        print(f"训练集: {len(train_df)} 条 ({train_df['timestamp'].min()} ~ {train_df['timestamp'].max()})")
        print(f"测试集: {len(test_df)} 条 ({test_df['timestamp'].min()} ~ {test_df['timestamp'].max()})")
        
        return train_df, test_df

# 使用示例
if __name__ == '__main__':
    loader = KlineDataLoader()
    df = loader.load_data()
    print(f"加载数据: {len(df)} 条")
    print(df.head())
```

---

## 🔧 三、特征工程

### 3.1 技术指标计算

创建文件：`ml/feature_engineering.py`

```python
import pandas as pd
import numpy as np
import ta  # pip install ta

class FeatureEngineer:
    """特征工程"""
    
    def __init__(self):
        pass
    
    def create_features(self, df):
        """创建所有特征"""
        df = df.copy()
        
        # 1. 价格特征
        df = self._add_price_features(df)
        
        # 2. 技术指标
        df = self._add_technical_indicators(df)
        
        # 3. K线形态特征
        df = self._add_candle_patterns(df)
        
        # 4. 时间特征
        df = self._add_time_features(df)
        
        # 删除前100行（指标计算需要历史数据）
        df = df.iloc[100:].reset_index(drop=True)
        
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
        df['is_doji'] = (abs(df['close_price'] - df['open_price']) / 
                        (df['high_price'] - df['low_price']) < 0.1).astype(int)
        
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
            forward_periods: 向前看多少个周期（12 = 1小时）
            threshold: 涨跌阈值（0.003 = 0.3%）
        """
        df = df.copy()
        
        # 未来收益率
        df['future_return'] = df['close_price'].shift(-forward_periods) / df['close_price'] - 1
        
        # 分类标签
        df['label'] = 0  # 默认震荡
        df.loc[df['future_return'] > threshold, 'label'] = 1   # 上涨
        df.loc[df['future_return'] < -threshold, 'label'] = -1  # 下跌
        
        # 删除最后forward_periods行（没有标签）
        df = df.iloc[:-forward_periods]
        
        print(f"标签分布:")
        print(df['label'].value_counts().sort_index())
        print(f"上涨比例: {(df['label']==1).sum()/len(df)*100:.1f}%")
        print(f"震荡比例: {(df['label']==0).sum()/len(df)*100:.1f}%")
        print(f"下跌比例: {(df['label']==-1).sum()/len(df)*100:.1f}%")
        
        return df

# 使用示例
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
    print(f"\n特征列表:")
    print(df.columns.tolist())
    print(f"\n数据形状: {df.shape}")
    
    # 保存
    df.to_csv('data/featured_data.csv', index=False)
    print("\n特征数据已保存到 data/featured_data.csv")
```

### 3.2 特征选择

```python
# 在feature_engineering.py中添加

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
```

---

## 🤖 四、模型训练

### 4.1 训练脚本

创建文件：`ml/train_model.py`

```python
import pandas as pd
import numpy as np
from lightgbm import LGBMClassifier
from sklearn.metrics import classification_report, confusion_matrix
import joblib
import json

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
        
        print(f"训练数据: X={X_train.shape}, y={y_train.shape}")
        
        # 类别权重（平衡数据）
        class_counts = y_train.value_counts()
        total = len(y_train)
        weights = {
            -1: total / (3 * class_counts[-1]),
            0: total / (3 * class_counts[0]),
            1: total / (3 * class_counts[1])
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
        
        print("开始训练...")
        self.model.fit(X_train, y_train)
        print("训练完成！")
        
        # 特征重要性
        self._show_feature_importance(X_train.columns)
        
        return self.model
    
    def evaluate(self, test_df):
        """评估模型"""
        X_test = test_df[self.feature_cols].fillna(0)
        y_test = test_df['label']
        
        # 预测
        y_pred = self.model.predict(X_test)
        y_proba = self.model.predict_proba(X_test)
        
        # 评估指标
        print("\n=== 分类报告 ===")
        print(classification_report(y_test, y_pred, 
                                   target_names=['下跌', '震荡', '上涨']))
        
        # 混淆矩阵
        print("\n=== 混淆矩阵 ===")
        cm = confusion_matrix(y_test, y_pred)
        print(cm)
        
        # 准确率
        accuracy = (y_test == y_pred).sum() / len(y_test)
        print(f"\n总准确率: {accuracy*100:.2f}%")
        
        # 保存预测结果
        test_df = test_df.copy()
        test_df['pred_label'] = y_pred
        test_df['pred_proba_down'] = y_proba[:, 0]
        test_df['pred_proba_hold'] = y_proba[:, 1]
        test_df['pred_proba_up'] = y_proba[:, 2]
        
        return test_df
    
    def _show_feature_importance(self, feature_names, top_n=20):
        """显示特征重要性"""
        importance = self.model.feature_importances_
        indices = np.argsort(importance)[::-1][:top_n]
        
        print(f"\n=== Top {top_n} 重要特征 ===")
        for i, idx in enumerate(indices, 1):
            print(f"{i}. {feature_names[idx]}: {importance[idx]:.4f}")
    
    def save_model(self, model_path='models/xgb_model.pkl', 
                   config_path='models/model_config.json'):
        """保存模型"""
        # 保存模型
        joblib.dump(self.model, model_path)
        print(f"模型已保存: {model_path}")
        
        # 保存配置
        config = {
            'feature_cols': self.feature_cols,
            'model_type': 'LGBMClassifier',
            'n_classes': 3
        }
        with open(config_path, 'w') as f:
            json.dump(config, f, indent=2)
        print(f"配置已保存: {config_path}")
    
    def load_model(self, model_path='models/xgb_model.pkl',
                   config_path='models/model_config.json'):
        """加载模型"""
        self.model = joblib.load(model_path)
        
        with open(config_path, 'r') as f:
            config = json.load(f)
        self.feature_cols = config['feature_cols']
        
        print(f"模型已加载: {model_path}")
        return self.model

# 主训练流程
if __name__ == '__main__':
    from data_loader import KlineDataLoader
    from feature_engineering import FeatureEngineer
    
    print("=== 开始训练ML模型 ===\n")
    
    # 1. 加载数据
    print("Step 1: 加载数据...")
    loader = KlineDataLoader()
    df = loader.load_data()
    
    # 2. 特征工程
    print("\nStep 2: 特征工程...")
    engineer = FeatureEngineer()
    df = engineer.create_features(df)
    df = engineer.create_labels(df, forward_periods=12, threshold=0.003)
    
    # 3. 划分训练集/测试集
    print("\nStep 3: 划分数据集...")
    train_df, test_df = loader.get_train_test_split(df, train_ratio=0.8)
    
    # 4. 训练模型
    print("\nStep 4: 训练模型...")
    trainer = ModelTrainer()
    feature_cols = engineer.get_feature_columns()
    trainer.train(train_df, feature_cols)
    
    # 5. 评估模型
    print("\nStep 5: 评估模型...")
    test_results = trainer.evaluate(test_df)
    
    # 6. 保存模型
    print("\nStep 6: 保存模型...")
    trainer.save_model()
    
    # 7. 保存测试结果
    test_results.to_csv('data/test_results.csv', index=False)
    print("测试结果已保存: data/test_results.csv")
    
    print("\n=== 训练完成！ ===")
```

---

## 📈 五、模型评估

### 5.1 回测验证

创建文件：`ml/backtest.py`

```python
import pandas as pd
import numpy as np

class MLBacktest:
    """ML模型回测"""
    
    def __init__(self, test_results_df):
        self.df = test_results_df.copy()
        
    def backtest_with_ml(self, confidence_threshold=0.6):
        """
        使用ML预测进行回测
        
        Args:
            confidence_threshold: 置信度阈值（只在高置信度时交易）
        """
        trades = []
        position = None
        
        for idx, row in self.df.iterrows():
            # ML预测
            pred_up_prob = row['pred_proba_up']
            pred_down_prob = row['pred_proba_down']
            
            # 实际收益
            actual_return = row['future_return']
            
            # 交易逻辑
            if position is None:  # 无持仓
                # 高置信度看多
                if pred_up_prob > confidence_threshold:
                    position = {
                        'entry_time': row['timestamp'],
                        'entry_price': row['close_price'],
                        'direction': 'LONG',
                        'confidence': pred_up_prob
                    }
                # 高置信度看空
                elif pred_down_prob > confidence_threshold:
                    position = {
                        'entry_time': row['timestamp'],
                        'entry_price': row['close_price'],
                        'direction': 'SHORT',
                        'confidence': pred_down_prob
                    }
            
            else:  # 有持仓，检查平仓
                # 简单策略：持有12个周期后平仓
                if idx - position.get('entry_idx', idx-12) >= 12:
                    # 计算收益
                    if position['direction'] == 'LONG':
                        pnl = actual_return
                    else:
                        pnl = -actual_return
                    
                    trades.append({
                        'entry_time': position['entry_time'],
                        'exit_time': row['timestamp'],
                        'direction': position['direction'],
                        'confidence': position['confidence'],
                        'pnl': pnl,
                        'pnl_dollars': pnl * 10 * 5000  # 10手 * 5000美元
                    })
                    
                    position = None
        
        # 统计
        trades_df = pd.DataFrame(trades)
        if len(trades_df) == 0:
            print("没有交易！")
            return None
        
        print(f"\n=== 回测结果（置信度≥{confidence_threshold}) ===")
        print(f"总交易次数: {len(trades_df)}")
        print(f"做多次数: {(trades_df['direction']=='LONG').sum()}")
        print(f"做空次数: {(trades_df['direction']=='SHORT').sum()}")
        
        win_trades = trades_df[trades_df['pnl'] > 0]
        print(f"\n胜率: {len(win_trades)/len(trades_df)*100:.1f}%")
        print(f"平均盈亏: ${trades_df['pnl_dollars'].mean():.2f}")
        print(f"总盈亏: ${trades_df['pnl_dollars'].sum():.2f}")
        print(f"最大单笔盈利: ${trades_df['pnl_dollars'].max():.2f}")
        print(f"最大单笔亏损: ${trades_df['pnl_dollars'].min():.2f}")
        
        return trades_df
    
    def compare_with_baseline(self):
        """与基准策略对比"""
        print("\n=== 策略对比 ===")
        
        # 基准1：随机交易
        random_return = self.df['future_return'].mean()
        print(f"随机交易平均收益: {random_return*100:.3f}%")
        
        # 基准2：永远做多
        always_long_return = self.df['future_return'].mean()
        print(f"永远做多平均收益: {always_long_return*100:.3f}%")
        
        # ML策略（不同置信度）
        for threshold in [0.5, 0.6, 0.7, 0.8]:
            result = self.backtest_with_ml(threshold)
            if result is not None:
                print(f"\nML策略（置信度≥{threshold}）:")
                print(f"  交易次数: {len(result)}")
                print(f"  胜率: {(result['pnl']>0).sum()/len(result)*100:.1f}%")
                print(f"  平均收益: ${result['pnl_dollars'].mean():.2f}")

# 使用示例
if __name__ == '__main__':
    # 加载测试结果
    test_results = pd.read_csv('data/test_results.csv')
    test_results['timestamp'] = pd.to_datetime(test_results['timestamp'])
    
    # 回测
    backtest = MLBacktest(test_results)
    backtest.compare_with_baseline()
```

---

## 🔗 六、集成到Java系统

### 6.1 Python预测服务

创建文件：`ml/prediction_service.py`

```python
from flask import Flask, request, jsonify
import joblib
import json
import pandas as pd
import numpy as np

app = Flask(__name__)

# 全局变量
model = None
feature_cols = None

def load_model():
    """加载模型"""
    global model, feature_cols
    
    model = joblib.load('models/xgb_model.pkl')
    
    with open('models/model_config.json', 'r') as f:
        config = json.load(f)
    feature_cols = config['feature_cols']
    
    print("模型加载成功！")

@app.route('/predict', methods=['POST'])
def predict():
    """预测接口"""
    try:
        # 获取请求数据
        data = request.json
        
        # 转换为DataFrame
        df = pd.DataFrame([data])
        
        # 提取特征
        X = df[feature_cols].fillna(0)
        
        # 预测
        pred_label = model.predict(X)[0]
        pred_proba = model.predict_proba(X)[0]
        
        # 返回结果
        result = {
            'success': True,
            'prediction': {
                'label': int(pred_label),  # -1/0/1
                'label_name': ['下跌', '震荡', '上涨'][pred_label+1],
                'probability': {
                    'down': float(pred_proba[0]),
                    'hold': float(pred_proba[1]),
                    'up': float(pred_proba[2])
                },
                'confidence': float(max(pred_proba))
            }
        }
        
        return jsonify(result)
    
    except Exception as e:
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({'status': 'ok'})

if __name__ == '__main__':
    # 加载模型
    load_model()
    
    # 启动服务
    print("启动预测服务...")
    print("API地址: http://localhost:5001/predict")
    app.run(host='0.0.0.0', port=5001, debug=False)
```

### 6.2 Java调用代码

创建文件：`src/main/java/com/ltp/peter/augtrade/ml/MLPredictionService.java`

```java
package com.ltp.peter.augtrade.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class MLPredictionService {
    
    private static final String ML_API_URL = "http://localhost:5001/predict";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * ML预测
     */
    public MLPrediction predict(MarketContext context) {
        try {
            // 准备请求数据
            Map<String, Object> requestData = prepareFeatures(context);
            
            // 调用Python API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestData, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                    ML_API_URL, request, String.class);
            
            // 解析响应
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            
            if (jsonNode.get("success").asBoolean()) {
                JsonNode pred = jsonNode.get("prediction");
                
                return MLPrediction.builder()
                        .label(pred.get("label").asInt())
                        .labelName(pred.get("label_name").asText())
                        .probUp(pred.get("probability").get("up").asDouble())
                        .probHold(pred.get("probability").get("hold").asDouble())
                        .probDown(pred.get("probability").get("down").asDouble())
                        .confidence(pred.get("confidence").asDouble())
                        .build();
            } else {
                log.error("ML预测失败: {}", jsonNode.get("error").asText());
                return null;
            }
            
        } catch (Exception e) {
            log.error("ML预测异常", e);
            return null;
        }
    }
    
    /**
     * 准备特征数据
     */
    private Map<String, Object> prepareFeatures(MarketContext context) {
        Map<String, Object> features = new HashMap<>();
        
        // 从context中提取特征（需要与Python训练时的特征一致）
        features.put("rsi_14", context.getIndicator("RSI"));
        features.put("adx", context.getIndicator("ADX"));
        features.put("williams_r", context.getIndicator("WilliamsR"));
        // ... 其他特征
        
        return features;
    }
}

@Data
@Builder
class MLPrediction {
    private int label;           // -1/0/1
    private String labelName;    // 下跌/震荡/上涨
    private double probUp;       // 上涨概率
    private double probHold;     // 震荡概率
    private double probDown;     // 下跌概率
    private double confidence;   // 最高概率（置信度）
}
```

### 6.3 集成到策略

修改`CompositeStrategy.java`：

```java
@Autowired
private MLPredictionService mlPredictionService;

@Override
public TradingSignal generateSignal(MarketContext context) {
    // 1. 原有策略信号
    TradingSignal traditionalSignal = super.generateSignal(context);
    
    // 2. ML预测
    MLPrediction mlPred = mlPredictionService.predict(context);
    
    if (mlPred == null) {
        // ML服务不可用，使用传统策略
        log.warn("[{}] ML预测服务不可用，使用传统策略", STRATEGY_NAME);
        return traditionalSignal;
    }
    
    // 3. 融合决策
    if (traditionalSignal.isBuy()) {
        // 传统策略看多
        if (mlPred.getProbUp() > 0.6) {
            // ML也看多，确认信号
            log.info("[{}] ✅ ML确认做多 (置信度:{:.2f})", 
                    STRATEGY_NAME, mlPred.getConfidence());
            return traditionalSignal;
        } else if (mlPred.getProbDown() > 0.5) {
            // ML看空，过滤信号
            log.warn("[{}] ❌ ML不支持做多 (看跌概率:{:.2f})", 
                    STRATEGY_NAME, mlPred.getProbDown());
            return createHoldSignal("ML预测不支持");
        } else {
            // ML中性，降低信号强度
            log.info("[{}] ⚠️ ML中性，降低信号强度", STRATEGY_NAME);
            traditionalSignal.setStrength(traditionalSignal.getStrength() * 0.7);
            return traditionalSignal;
        }
    }
    
    // 类似处理做空信号...
    
    return traditionalSignal;
}
```

---

## 🚀 七、完整执行步骤

### 步骤1：环境准备

```bash
# 1. 创建目录结构
mkdir -p ml data models

# 2. 安装Python依赖
pip install pandas numpy scikit-learn lightgbm ta flask joblib

# 3. 安装Java依赖（在pom.xml中添加）
# - spring-boot-starter-web (已有)
# - jackson-databind (已有)
```

### 步骤2：数据准备

```bash
# 从数据库导出数据
cd /Users/peterwang/IdeaProjects/AugTrade
source ~/.bash_profile && mysql -h localhost -P 3306 -u root -p'12345678' test -e "
SELECT 
    timestamp,
    open_price,
    high_price,
    low_price,
    close_price,
    volume
FROM t_kline
WHERE 
    symbol = 'XAUTUSDT'
    AND timestamp >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
ORDER BY timestamp ASC
" > data/raw_klines.csv

echo "数据已导出到 data/raw_klines.csv"
```

### 步骤3：训练模型

```bash
# 运行训练脚本
cd ml
python train_model.py

# 预期输出：
# - 模型文件: models/xgb_model.pkl
# - 配置文件: models/model_config.json
# - 测试结果: data/test_results.csv
```

### 步骤4：启动预测服务

```bash
# 后台启动Python服务
cd ml
nohup python prediction_service.py > prediction.log 2>&1 &

# 测试服务
curl http://localhost:5001/health
```

### 步骤5：集成到Java

```bash
# 1. 添加MLPredictionService.java
# 2. 修改CompositeStrategy.java
# 3. 重启Java应用
./restart.sh
```

### 步骤6：监控和优化

```bash
# 查看预测服务日志
tail -f ml/prediction.log

# 查看Java应用日志
tail -f logs/aug-trade.log | grep ML
```

---

## 📊 八、预期效果

### 8.1 性能指标

| 指标 | 纯传统策略 | 传统+ML | 提升 |
|------|-----------|---------|------|
| 胜率 | 50-55% | 60-65% | +10% |
| 月收益 | 5-8% | 8-12% | +40% |
| 最大回撤 | -8% | -6% | +25% |
| 交易次数 | 100次 | 70次 | -30% |

### 8.2 预期改进

1. **更准确的信号过滤**：ML帮助过滤低质量信号
2. **减少交易次数**：只在高置信度时交易
3. **提高胜率**：双重确认机制
4. **保持可解释性**：策略逻辑清晰

---

## ⚠️ 九、注意事项

1. **数据质量**：确保数据完整、无缺失
2. **特征对齐**：Java和Python的特征计算必须一致
3. **模型更新**：定期重训练（建议每月）
4. **API稳定性**：Python服务挂掉时有fallback
5. **性能监控**：记录ML预测的准确率

---

## 🎯 十、下一步优化

完成方向1后，可以考虑：

1. **方向2**：参数自适应（用RL优化ADX门槛等）
2. **集成多模型**：XGBoost + LSTM + Transformer投票
3. **在线学习**：实时更新模型
4. **特征自动选择**：用AutoML优化特征

---

**文档创建时间**: 2026-01-27  
**预计完成时间**: 1-2个月  
**难度评级**: ⭐⭐⭐  
**收益预期**: 提升10-20%胜率
