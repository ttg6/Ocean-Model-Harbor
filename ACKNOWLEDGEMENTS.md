# 开源致谢

Ocean 模型港首先感谢 **夕白（[xibai6668](https://github.com/xibai6668)）**和 **[Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway)** 的所有贡献者。

原项目公开了多供应商接入、多账号管理、协议转换、路由、代理和调用记录等实现，是本 Windows 版的技术基础。感谢原作者以 MIT 许可证分享源代码，也感谢参与代码、测试、文档和问题反馈的社区成员。

## 原项目与来源

- 原仓库：[xibai6668/Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway)
- 原作者：[夕白 / xibai6668](https://github.com/xibai6668)
- 本次移植的上游基线：[15e42951f7ee4a661fc4b2056d8e1f1c1ef133ce](https://github.com/xibai6668/Android-AI-gateway/commit/15e42951f7ee4a661fc4b2056d8e1f1c1ef133ce)
- 原始版权声明：`Copyright (c) 2026 夕白`
- 原始 MIT 许可证全文保留在 [LICENSE](LICENSE)。

`core/` 主要复用上游 Kotlin/JVM 核心和协议测试，并补充了停用供应商路由处理、Antigravity OAuth 运行时配置与回归测试。`desktop/` 提供 Windows 桌面界面、DPAPI 存储与托盘控制；`scripts/` 提供构建和分发流程。Windows 版与原项目的关系和差异见 [移植说明](docs/WINDOWS-PORT.md)。

## 其他开源组件

感谢 Kotlin、Gson、NanoHTTPD、FlatLaf、JNA、JetBrains annotations、Error Prone annotations、Eclipse Temurin / OpenJDK 的维护者。组件版本、来源和许可说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

客户端分发包保留原项目 LICENSE、第三方声明、运行环境许可，并附有中文开源致谢。

欢迎前往原仓库 Star、反馈问题或参与贡献。Windows 版的问题可在本仓库反馈。
