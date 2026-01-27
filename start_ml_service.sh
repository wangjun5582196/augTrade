#!/bin/bash
# ML预测服务启动脚本

echo "============================================"
echo "  启动ML预测服务"
echo "============================================"

# 检查是否已经运行
PID=$(lsof -ti:5001)
if [ ! -z "$PID" ]; then
    echo "⚠️  端口5001已被占用 (PID: $PID)"
    echo "是否停止旧服务并重启？(y/n)"
    read -r response
    if [ "$response" = "y" ]; then
        echo "停止旧服务..."
        kill $PID
        sleep 2
    else
        echo "取消启动"
        exit 1
    fi
fi

# 检查Python依赖
echo ""
echo "检查Python依赖..."
python3 -c "import flask, joblib, pandas, lightgbm" 2>/dev/null
if [ $? -ne 0 ]; then
    echo "❌ 缺少Python依赖"
    echo "请运行: pip3 install -r ml/requirements.txt"
    exit 1
fi
echo "✅ Python依赖检查通过"

# 检查模型文件
echo ""
echo "检查模型文件..."
if [ ! -f "models/lgbm_model.pkl" ]; then
    echo "❌ 模型文件不存在: models/lgbm_model.pkl"
    echo "请先训练模型: cd ml && python3 train_model.py"
    exit 1
fi
echo "✅ 模型文件存在"

# 启动服务
echo ""
echo "🚀 启动ML预测服务..."
cd ml
nohup python3 prediction_service.py > ../logs/ml_service.log 2>&1 &
ML_PID=$!

# 等待服务启动
echo "等待服务启动..."
sleep 3

# 检查服务状态
curl -s http://localhost:5001/health > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo ""
    echo "============================================"
    echo "  ✅ ML预测服务启动成功！"
    echo "============================================"
    echo ""
    echo "📌 服务信息:"
    echo "  - PID: $ML_PID"
    echo "  - 端口: 5001"
    echo "  - 日志: logs/ml_service.log"
    echo ""
    echo "📌 API端点:"
    echo "  - 健康检查: http://localhost:5001/health"
    echo "  - 单次预测: http://localhost:5001/predict"
    echo "  - 批量预测: http://localhost:5001/predict/batch"
    echo "  - 特征列表: http://localhost:5001/features"
    echo ""
    echo "📝 查看日志: tail -f logs/ml_service.log"
    echo "🛑 停止服务: ./stop_ml_service.sh"
    echo ""
else
    echo ""
    echo "❌ ML预测服务启动失败"
    echo "查看日志: cat logs/ml_service.log"
    exit 1
fi
