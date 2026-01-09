# 飞书@提醒功能使用指南

## 📋 功能说明

在发送飞书开仓和平仓通知时，系统会自动@指定用户，确保你不会错过重要的交易通知。

## ✅ 已实现功能

- ✅ 开仓通知带@提醒
- ✅ 平仓通知带@提醒
- ✅ 可配置开关（启用/禁用@功能）
- ✅ 灵活配置用户ID

## 🔧 配置步骤

### 1. 获取你的飞书用户ID

有以下几种方式获取飞书用户ID（open_id）：

#### 方式一：通过飞书群获取（推荐）
1. 在飞书群中，找到你自己的头像
2. 点击头像 -> 点击右上角"..."菜单
3. 选择"复制用户ID"
4. 得到的ID格式类似：`ou_xxxxxxxxxxxxx`

#### 方式二：通过飞书机器人测试获取
1. 发送一条消息到你的飞书机器人群
2. 在机器人后台查看消息详情
3. 可以看到发送者的open_id

#### 方式三：使用飞书开放平台API
```bash
# 使用飞书API获取用户列表
curl -X GET 'https://open.feishu.cn/open-apis/contact/v3/users' \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

### 2. 配置application.yml

在 `src/main/resources/application.yml` 中配置：

```yaml
# 飞书通知配置
feishu:
  notification:
    enabled: true                          # ✅ 启用飞书通知
  webhook:
    url: "https://open.feishu.cn/open-apis/bot/v2/hook/your-webhook-url"
    secret: ""                             # 如果启用了签名校验，填写密钥
  mention:
    enabled: true                          # ✅ 启用@提醒功能
    user-id: "ou_xxxxxxxxxxxxx"           # 填写你的飞书用户ID（open_id）
```

### 3. 配置说明

| 配置项 | 说明 | 默认值 | 示例 |
|--------|------|--------|------|
| `feishu.mention.enabled` | 是否启用@提醒 | `false` | `true` |
| `feishu.mention.user-id` | 飞书用户ID | 空 | `ou_xxxxxxxxxxxxx` |

## 📝 使用示例

### 示例1：启用@提醒（推荐）

```yaml
feishu:
  mention:
    enabled: true
    user-id: "ou_7d8f9e0a1b2c3d4e"
```

当交易系统开仓或平仓时，你会收到带@的通知，确保不会错过。

### 示例2：禁用@提醒

```yaml
feishu:
  mention:
    enabled: false
    user-id: ""
```

通知正常发送，但不会@任何人。

### 示例3：临时禁用@提醒

如果你暂时不想被@，只需要将 `enabled` 设置为 `false`，不需要删除 `user-id`：

```yaml
feishu:
  mention:
    enabled: false                    # 临时禁用
    user-id: "ou_7d8f9e0a1b2c3d4e"   # 保留配置，方便后续启用
```

## 🎯 通知示例

### 开仓通知效果

```
@你的名字

💰 开仓通知

订单ID: POS-2026-01-09-001
交易品种: XAUTUSDT
方向: 🔥 做多
开仓价: $2650.50
数量: 10
止损价: $2635.50
止盈价: $2680.50
策略: BalancedAggressiveStrategy
时间: 2026-01-09 16:30:00
```

### 平仓通知效果

```
@你的名字

🔔 平仓通知

订单ID: POS-2026-01-09-001
交易品种: XAUTUSDT
方向: 做多
开仓价: $2650.50
平仓价: $2680.50
数量: 10
盈亏: ✅ 盈利 $300.00
持仓时间: 0小时25分30秒
平仓原因: 止盈
时间: 2026-01-09 16:55:30
```

## 🔍 验证配置

### 1. 检查日志

启动系统后，查看日志中是否有以下信息：

```
✅ 飞书通知配置成功
📋 @提醒已启用，用户ID: ou_xxxxxxxxxxxxx
```

### 2. 测试通知

你可以通过系统的测试接口发送一条测试通知：

```bash
curl -X POST http://localhost:3131/api/test/feishu
```

## ⚠️ 注意事项

1. **用户ID格式**：
   - 必须使用 `open_id` 格式（通常以 `ou_` 开头）
   - 不要使用 `user_id` 或邮箱地址

2. **群权限**：
   - 确保你在飞书机器人所在的群中
   - 机器人需要有@成员的权限

3. **配置优先级**：
   - 如果 `mention.enabled = false`，即使配置了 `user-id` 也不会@
   - 如果 `mention.enabled = true` 但 `user-id` 为空，则不会@

4. **多用户场景**：
   - 当前版本只支持@一个用户
   - 如需@多个用户，可以联系我扩展功能

## 🐛 故障排查

### 问题1：配置了user-id但没有@提醒

**可能原因**：
- `mention.enabled` 未设置为 `true`
- user-id格式不正确
- 用户不在群中

**解决方案**：
1. 检查配置文件中 `mention.enabled: true`
2. 确认user-id格式为 `ou_xxxxx`
3. 确认你在机器人所在的群中

### 问题2：无法获取user-id

**解决方案**：
1. 在群中发送消息 `@机器人 我的ID是什么？`
2. 或者先不配置user-id，待我帮你添加获取ID的功能

### 问题3：@了但显示"用户不存在"

**可能原因**：
- user-id错误或过期
- 用户已退出群

**解决方案**：
1. 重新获取最新的user-id
2. 确认你还在群中

## 📞 技术支持

如有问题，请检查：
1. 配置文件格式是否正确（YAML格式对缩进敏感）
2. 系统日志中的错误信息
3. 飞书机器人是否正常运行

## 🔄 版本历史

- **v1.0** (2026-01-09)
  - ✅ 实现开仓通知@提醒
  - ✅ 实现平仓通知@提醒
  - ✅ 支持配置开关
  - ✅ 支持灵活配置用户ID

## 🚀 未来计划

- [ ] 支持@多个用户
- [ ] 支持根据盈亏金额决定是否@
- [ ] 支持按时间段启用/禁用@提醒
- [ ] 自动获取用户ID功能

---

**注意**：修改配置文件后，需要重启系统才能生效。
