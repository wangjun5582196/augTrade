# 飞书@提醒功能测试指南

## 📋 测试前准备

### 1. 配置飞书用户ID

在运行测试之前，请先在 `application.yml` 中配置你的飞书用户ID：

```yaml
feishu:
  notification:
    enabled: true
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/your-webhook-url"
    secret: ""
  mention:
    enabled: true                    # ✅ 启用@提醒
    user-id: "ou_xxxxxxxxxxxxx"     # ⚠️ 请填写你的飞书用户ID
```

### 2. 确认配置有效

确保：
- ✅ 飞书通知已启用 (`feishu.notification.enabled: true`)
- ✅ Webhook URL 已正确配置
- ✅ @提醒已启用 (`feishu.mention.enabled: true`)
- ✅ 用户ID已填写 (`feishu.mention.user-id`)

## 🧪 运行测试

### 方式一：使用IDE运行（推荐）

1. 在 IntelliJ IDEA 中打开 `src/test/java/FeishuMentionTest.java`
2. 选择要运行的测试方法
3. 右键点击 -> Run 'testXxx()'

### 方式二：使用Maven命令行

```bash
# 运行所有测试
mvn test -Dtest=FeishuMentionTest

# 运行单个测试方法
mvn test -Dtest=FeishuMentionTest#testOpenPositionNotificationWithMention

# 运行完整测试套件
mvn test -Dtest=FeishuMentionTest#testAllNotificationScenarios
```

## 📝 测试用例说明

### 1. testOpenPositionNotificationWithMention
测试开仓通知是否带@提醒

**预期结果**：
- 收到一条开仓通知
- 通知中有@你的用户名
- 显示完整的开仓信息（订单ID、价格、止损、止盈等）

### 2. testClosePositionNotificationWithMention_Profit
测试平仓通知（盈利场景）是否带@提醒

**预期结果**：
- 收到一条平仓通知（绿色卡片）
- 通知中有@你的用户名
- 显示盈利金额和持仓时间

### 3. testClosePositionNotificationWithMention_Loss
测试平仓通知（亏损场景）是否带@提醒

**预期结果**：
- 收到一条平仓通知（红色卡片）
- 通知中有@你的用户名
- 显示亏损金额和持仓时间

### 4. testAllNotificationScenarios（推荐）
完整测试所有通知场景

**测试内容**：
1. 开仓通知（做多）
2. 开仓通知（做空）
3. 平仓通知（盈利）
4. 平仓通知（亏损）

**预期结果**：
- 依次收到4条通知
- 每条通知都有@提醒
- 通知内容和颜色正确

### 5. testBasicNotification
测试基础通知功能（不带@）

**预期结果**：
- 收到一条测试通知
- **不会**有@提醒（这是正常的）

## ✅ 验证清单

运行测试后，请在飞书群中验证：

- [ ] 是否收到测试通知
- [ ] 通知中是否有@你的用户名
- [ ] 点击@是否能跳转到你的个人信息
- [ ] 通知内容是否完整显示
- [ ] 卡片颜色是否正确
  - 做多/盈利 = 绿色
  - 做空/亏损 = 红色

## 🐛 故障排查

### 问题1：收到通知但没有@

**可能原因**：
- `mention.enabled` 未设置为 `true`
- `mention.user-id` 为空或格式错误

**解决方案**：
```bash
# 检查配置
grep -A 3 "mention:" src/main/resources/application.yml
```

### 问题2：完全收不到通知

**可能原因**：
- Webhook URL 错误
- 飞书通知未启用
- 网络问题

**解决方案**：
1. 检查 `feishu.notification.enabled: true`
2. 验证 Webhook URL 是否正确
3. 查看控制台日志

### 问题3：@显示"用户不存在"

**可能原因**：
- user-id 格式错误（应该是 `ou_` 开头）
- 用户不在群中
- user-id 过期

**解决方案**：
1. 重新获取最新的 user-id
2. 确认格式为 `ou_xxxxxxxxxxxxx`
3. 确认你在机器人所在的群中

## 📊 测试结果示例

### 成功的测试输出

```
=== 测试开仓通知（带@提醒） ===
✅ 飞书开仓通知发送成功（尝试1/3）
添加@提醒: userId=ou_xxxxxxxxxxxxx
✅ 开仓通知已发送，请检查飞书群是否收到@提醒
```

### 失败的测试输出

```
=== 测试开仓通知（带@提醒） ===
⚠️ 飞书开仓通知发送失败（尝试1/3）- 状态码: 400
❌ 飞书开仓通知发送最终失败，已重试3次
```

## 🎯 快速测试流程

1. **配置用户ID**（只需一次）
   ```bash
   # 编辑配置文件
   vi src/main/resources/application.yml
   ```

2. **运行完整测试**（推荐）
   ```bash
   mvn test -Dtest=FeishuMentionTest#testAllNotificationScenarios
   ```

3. **检查飞书群**
   - 应该收到4条通知
   - 每条都有@提醒

4. **验证成功！** ✅

## 📞 技术支持

如果测试失败，请提供以下信息：
1. 配置文件内容（隐藏敏感信息）
2. 控制台日志输出
3. 飞书群截图（如果收到了通知但没有@）

## 🔄 下一步

测试通过后：
1. 将测试用的 user-id 替换为你真实的 user-id
2. 重启交易系统
3. 实际交易时会自动发送带@的通知

---

**提示**：测试类会发送多条通知到飞书群，建议在测试专用群中测试，避免打扰其他成员。
