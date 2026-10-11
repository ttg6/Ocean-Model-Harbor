# Antigravity OAuth 配置

Windows 开源版不携带原应用的 Google OAuth Client ID / Client Secret。Trae、WorkBuddy、Loomy 和自定义中转站使用各自原有的接入流程。

1. 准备自己有权使用的 Google OAuth 桌面应用客户端，参考 [Google 官方原生应用 OAuth 文档](https://developers.google.com/identity/protocols/oauth2/native-app)。本地回调为 `http://localhost:51121/oauth-callback`，需保持该端口可用。
2. 在 Ocean 模型港打开「供应商与账号」，点击「更多操作 → Antigravity OAuth 配置」。填写 Client ID 和 Client Secret，保存后再选择 Antigravity 并点击「浏览器登录」。
3. 完成 Google 授权，回到工具检查账号状态，再刷新模型目录和额度。

配置与账号信息保存在当前 Windows 用户的 DPAPI 加密数据目录。不要将凭据写入源码、GitHub、截图或问题反馈。环境变量 `OCEAN_ANTIGRAVITY_CLIENT_ID` 与 `OCEAN_ANTIGRAVITY_CLIENT_SECRET` 也可提供配置；已保存的非空设置优先。

更换 OAuth 客户端后必须重新授权。旧版账号的刷新令牌绑定旧客户端，不能转移给新客户端。已导入账号的访问令牌失效后同样需要匹配的 OAuth 配置才能刷新。

创建 OAuth 客户端并不自动赋予 Antigravity / Code Assist 服务权限；授权范围、项目资格、账号权限、配额和 Google 的验证要求仍由上游决定。出现拒绝授权或无服务权限时，检查自己项目和账号的资格。自动化验证仅使用本地模拟上游，未验证任意 Google 项目都能接入该服务。
