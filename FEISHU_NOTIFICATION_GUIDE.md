# 飞书机器人通知配置指南

## 🎯 功能概述

系统已集成飞书机器人通知功能，可在以下场景自动发送通知到飞书群：

1. ✅ **开仓通知** - 当系统开仓时
2. ✅ **平仓通知** - 当系统平仓时（止损/止盈/信号反转）
3. ✅ **止损通知** - 触发止损时
4. ✅ **止盈通知** - 触发止盈时
5. ✅ **信号反转通知** - 策略信号反转平仓时

---

## 📋 配置步骤

### 步骤1：在飞书群中添加机器人

#### 1.1 创建或打开飞书群
- 打开飞书APP或网页版
- 创建一个新群或使用现有群

#### 1.2 添加自定义机器人
1. 点击群右上角 **设置** 图标
2. 选择 **群机器人** → **添加机器人**
3. 选择 **自定义机器人**
4. 填写机器人信息：
   - **名称**：AugTrade交易通知
   - **描述**：自动交易系统通知
5. 点击 **添加**

#### 1.3 获取Webhook地址
- 添加成功后会显示 **Webhook地址**
- 格式类似：`https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
- **复制这个地址**（后续配置需要）

---

### 步骤2：配置application.yml

打开 `src/main/resources/application.yml`，修改飞书配置：

```yaml
# 飞书通知配置
feishu:
  notification:
    enabled: true                           # ✅ 改为true启用
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/你的webhook地址"  # 填入步骤1获取的地址
```

**示例**：
```yaml
feishu:
  notification:
    enabled: true
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/abc12345-1234-5678-9abc-123456789012"
```

---

### 步骤3：重启应用

```bash
./restart.sh
```

---

## 📱 通知消息示例

### 1. 开仓通知

```
💰 开仓通知
━━━━━━━━━━━━━━━━
交易品种: XAUUSDT
方向: 🔥 做多
开仓价: $2678.50
数量: 0.01
止损价: $2653.50
止盈价: $2758.50
策略: AggressiveML
时间: 2026-01-08 13:26:41
```

### 2. 止盈通知

```
🎉 止盈通知
━━━━━━━━━━━━━━━━
交易品种: XAUUSDT
方向: 做多
开仓价: $2678.50
平仓价: $2758.50
数量: 0.01
盈亏: $80.00
触发类型: 止盈
时间: 2026-01-08 14:26:41
```

### 3. 止损通知

```
⚠️ 止损通知
━━━━━━━━━━━━━━━━
交易品种: XAUUSDT
方向: 做多
开仓价: $2678.50
平仓价: $2653.50
数量: 0.01
盈亏: $25.00
触发类型: 止损
时间: 2026-01-08 14:26:41
```

### 4. 信号反转平仓通知

```
⚠️ 信号反转平仓
━━━━━━━━━━━━━━━━
交易品种: XAUUSDT
原方向: 做多
开仓价: $2678.50
平仓价: $2690.20
盈亏: $11.70
原因: 策略信号反转
时间: 2026-01-08 14:26:41
```

---

## 🔧 高级配置

### 临时关闭通知

如果想临时关闭通知但不删除配置：

```yaml
feishu:
  notification:
    enabled: false  # 改为false即可
```

### 测试通知功能

系统提供了测试通知功能，可通过代码调用：

```java
@Autowired
private FeishuNotificationService feishuNotificationService;

// 发送测试通知
feishuNotificationService.testNotification();
```

**测试通知示例**：
```
🎯 测试通知
━━━━━━━━━━━━━━━━
系统: AugTrade交易系统
状态: 运行正常
飞书通知: 配置成功
时间: 2026-01-08 13:26:41
```

---

## 📊 通知颜色说明

| 通知类型 | 颜色 | 说明 |
|---------|------|------|
| 开仓（做多） | 🟢 绿色 | 看涨方向 |
| 开仓（做空） | 🔴 红色 | 看跌方向 |
| 止盈 | 🟢 绿色 | 盈利平仓 |
| 止损 | 🟠 橙色 | 止损平仓 |
| 信号反转（盈利） | 🟢 绿色 | 反转且盈利 |
| 信号反转（亏损） | 🟠 橙色 | 反转且亏损 |
| 测试通知 | 🔵 蓝色 | 系统测试 |

---

## 🛡️ 安全建议

### 1. Webhook地址保密
- ❌ 不要将Webhook地址提交到公开的Git仓库
- ✅ 使用环境变量或私有配置文件
- ✅ 定期更换Webhook地址

### 2. 配置环境变量（推荐）

**方法1：使用环境变量**
```bash
export FEISHU_WEBHOOK_URL="https://open.feishu.cn/open-apis/bot/v2/hook/your-webhook-url"
```

然后在application.yml中引用：
```yaml
feishu:
  webhook:
    url: ${FEISHU_WEBHOOK_URL}
```

**方法2：使用application-local.yml**
```yaml
# application-local.yml (不提交到Git)
feishu:
  notification:
    enabled: true
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/your-webhook-url"
```

在`.gitignore`中添加：
```
application-local.yml
```

---

## 🚨 故障排查

### 问题1：通知未发送

**检查清单**：
1. ✅ `feishu.notification.enabled` 是否为 `true`
2. ✅ `feishu.webhook.url` 是否正确配置
3. ✅ Webhook地址是否有效（未过期）
4. ✅ 网络是否正常（能访问飞书API）

**查看日志**：
```bash
tail -f logs/aug-trade.log | grep Feishu
```

**正常日志**：
```
✅ 飞书开仓通知发送成功
✅ 飞书平仓通知发送成功
```

**错误日志**：
```
❌ 飞书开仓通知发送失败
⚠️ 飞书通知未启用或未配置Webhook
```

---

### 问题2：通知发送失败（HTTP错误）

**可能原因**：
1. Webhook地址错误
2. 机器人被移除
3. 网络问题

**解决方法**：
```bash
# 测试Webhook地址
curl -X POST "你的Webhook地址" \
  -H "Content-Type: application/json" \
  -d '{"msg_type":"text","content":{"text":"测试消息"}}'
```

成功响应：
```json
{"code":0,"msg":"success"}
```

---

### 问题3：通知格式错误

**查看错误详情**：
```bash
grep "飞书通知发送失败，状态码" logs/aug-trade.log
```

**常见状态码**：
- `200`：成功
- `400`：请求格式错误
- `404`：Webhook地址不存在
- `500`：飞书服务器错误

---

## 💡 使用建议

### 1. 通知频率控制

由于系统可能频繁交易，建议：
- ✅ 只在重要事件时通知（开仓、平仓）
- ✅ 设置通知免打扰时间（飞书群设置）
- ✅ 使用单独的交易通知群

### 2. 通知内容自定义

如需自定义通知内容，修改 `FeishuNotificationService.java`：

```java
// 修改通知模板
String message = buildCardMessage(
    "💰 开仓通知",  // 标题
    "green",        // 颜色
    content         // 内容
);
```

支持的颜色：
- `red` - 红色
- `orange` - 橙色  
- `yellow` - 黄色
- `green` - 绿色
- `blue` - 蓝色
- `purple` - 紫色
- `grey` - 灰色

### 3. 多群通知

如需发送到多个群，可配置多个Webhook：

```yaml
feishu:
  notification:
    enabled: true
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/webhook1"
    backup-url: "https://open.feishu.cn/open-apis/bot/v2/hook/webhook2"
```

---

## 📚 相关文档

- [飞书开放平台 - 自定义机器人](https://open.feishu.cn/document/ukTMukTMukTM/ucTM5YjL3ETO24yNxkjN)
- [飞书机器人消息格式](https://open.feishu.cn/document/ukTMukTMukTM/uMTM5YjLzETO24yMxkjN)

---

## 📝 配置检查清单

开始使用前，请确认以下项目：

- [ ] 已在飞书群中创建自定义机器人
- [ ] 已复制Webhook地址
- [ ] 已在application.yml中配置Webhook
- [ ] 已将 `feishu.notification.enabled` 设置为 `true`
- [ ] 已重启应用
- [ ] 已发送测试通知验证
- [ ] Webhook地址已妥善保管（不泄露）

---

## ✅ 快速开始（3步配置）

### 1️⃣ 飞书群添加机器人
```
群设置 → 群机器人 → 添加机器人 → 自定义机器人
复制Webhook地址
```

### 2️⃣ 修改配置文件
```yaml
feishu:
  notification:
    enabled: true
  webhook:
    url: "你的Webhook地址"
```

### 3️⃣ 重启应用
```bash
./restart.sh
```

**完成！** 🎉 系统将自动发送交易通知到飞书群。

---

**配置完成时间**：2026年1月8日 13:28  
**功能状态**：✅ 已就绪，可投入使用  
**维护建议**：定期检查Webhook有效性，建议每3个月更换一次
