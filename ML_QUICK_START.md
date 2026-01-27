# ML训练数据快速开始指南

**方向1：ML预测辅助传统策略**  
**预计时间**: 30分钟完成第一次训练

---

## 🚀 快速开始（5步搞定）

### 步骤1：安装Python依赖（5分钟）

```bash
cd /Users/peterwang/IdeaProjects/AugTrade

# 安装依赖
pip install -r ml/requirements.txt

# 验证安装
python -c "import pandas, lightgbm, ta; print('✅ 依赖安装成功')"
```

### 步骤2：导出数据（2分钟）

```bash
# 给脚本执行权限
chmod +x export_data.sh

# 导出6个月的K线数据
./export_data.sh
```

**预期输出**:
```
✅ 导出成功！共 35000+ 行
📁 文件位置: data/raw_klines.csv
```

### 步骤3：训练模型（10分钟）

```bash
cd ml

# 开始训练
python train_model.py
```

**训练过程**会经历7个步骤：
1. 加载数据
2. 特征工程（计算技术指标）
3. 划分训练集/测试集
4. 训练LightGBM模型
5. 评估模型
6. 保存模型
7. 保存测试结果

**预期输出**:
```
✅ 总准确率: 55-65%
✅ 模型已保存: ../models/lgbm_model.pkl
✅ 测试结果已保存: ../data/test_results.csv
```

### 步骤4：查看结果（1分钟）

```bash
# 查看模型文件
ls -lh ../models/

# 查看测试结果（前10行）
head ../data/test_results.csv
```

### 步骤5：完成！🎉

现在你已经有了一个训练好的ML模型！

---

## 📂 文件结构

训练完成后的文件结构：

```
AugTrade/
├── ml/                          # ML相关代码
│   ├── data_loader.py          # 数据加载器 ✅
│   ├── feature_engineering.py  # 特征工程 ✅
│   ├── train_model.py          # 训练脚本 ✅
│   └── requirements.txt        # Python依赖 ✅
├── data/                        # 数据文件
│   ├── raw_klines.csv          # 原始K线数据 ✅
│   └── test_results.csv        # 测试结果 ✅
├── models/                      # 模型文件
│   ├── lgbm_model.pkl          # 训练好的模型 ✅
│   └── model_config.json       # 模型配置 ✅
└── export_data.sh              # 数据导出脚本 ✅
```

---

## 📊 理解训练结果

### 准确率含义

```
总准确率: 60%
```

**解释**：
- 在测试集上，模型预测正确的比例是60%
- 高于随机猜测（33.3%）
- 接近或高于55%就可以尝试使用

### 分类报告示例

```
              precision    recall  f1-score   support

   下跌(-1)       0.52      0.48      0.50      1500
   震荡(0)        0.61      0.65      0.63      2000
   上涨(1)        0.58      0.55      0.56      1500

   accuracy                           0.58      5000
```

**关键指标**：
- **precision（精确率）**: 预测为某类时，有多少是对的
- **recall（召回率）**: 实际为某类时，预测对了多少
- **f1-score**: 精确率和召回率的调和平均
- **support**: 每类的样本数量

### 混淆矩阵示例

```
           预测下跌  预测震荡  预测上涨
实际下跌      720      600      180
实际震荡      400     1300      300
实际上涨      250      425      825
```

**如何看**：
- 对角线（720, 1300, 825）是预测正确的
- 非对角线是预测错误的

---

## 🔍 常见问题

### Q1: 训练时报错 "No module named 'XXX'"

**解决**：
```bash
pip install XXX
```

### Q2: 数据导出失败

**检查**：
1. MySQL是否运行：`mysql -h localhost -u root -p'12345678' -e "SELECT 1"`
2. 数据库是否有数据：`SELECT COUNT(*) FROM t_kline WHERE symbol='XAUTUSDT'`

### Q3: 准确率只有40%

**可能原因**：
1. 数据量太少（<10000条）
2. 标签阈值设置不合理
3. 市场太随机，本身就难预测

**解决**：
- 增加数据量（导出更多历史数据）
- 调整阈值（在`train_model.py`的`create_labels`函数中）
- 尝试不同的预测周期（forward_periods）

### Q4: 如何重新训练？

```bash
# 删除旧模型
rm -rf ../models/*

# 重新训练
python train_model.py
```

---

## 📈 下一步

训练完成后，可以：

### 1. 调整参数重新训练

编辑 `train_model.py`，修改：

```python
# 预测周期（当前是12个5分钟 = 1小时）
forward_periods=12  # 可改为 6（30分钟）或 24（2小时）

# 涨跌阈值（当前是0.3%）
threshold=0.003  # 可改为 0.005（0.5%）或 0.002（0.2%）
```

### 2. 查看特征重要性

训练完成后会自动显示Top 15重要特征，例如：

```
=== Top 15 重要特征 ===
 1. adx                 : 0.0842
 2. rsi_14              : 0.0756
 3. williams_r          : 0.0698
 ...
```

**解读**：数值越大，该特征对预测越重要

### 3. 启动预测服务（可选）

```bash
# 创建prediction_service.py后运行
python prediction_service.py
```

### 4. 集成到Java系统

按照 `ML_TRAINING_GUIDE.md` 的第六章进行

---

## 💡 训练技巧

### 技巧1：增加数据量

```bash
# 导出12个月数据（更准确）
mysql ... "... DATE_SUB(NOW(), INTERVAL 12 MONTH) ..." > data/raw_klines.csv
```

### 技巧2：调整模型参数

编辑 `train_model.py` 的 `LGBMClassifier`：

```python
LGBMClassifier(
    n_estimators=500,      # 树的数量，可增加到1000
    learning_rate=0.05,    # 学习率，可降到0.01（慢但准）
    max_depth=7,           # 树深度，可增加到10
    # ...
)
```

### 技巧3：特征选择

编辑 `feature_engineering.py`，添加新特征或删除不重要的特征

---

## 🎯 预期效果

| 指标 | 纯技术指标 | +ML辅助 |
|------|-----------|---------|
| 准确率 | - | 55-65% |
| 胜率提升 | 基准 | +10% |
| 交易次数 | 基准 | -30%（更精准）|

---

## 📞 需要帮助？

1. **查看详细文档**: `ML_TRAINING_GUIDE.md`
2. **查看代码注释**: Python文件中有详细注释
3. **调试模式**: 运行单个Python文件测试

---

**创建时间**: 2026-01-27  
**预计完成时间**: 30分钟  
**难度**: ⭐⭐ (简单)
