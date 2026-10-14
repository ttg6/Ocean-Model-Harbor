# 发布与更新说明

每个版本都必须在根目录 `CHANGELOG.md` 中写清本次新增、修复或调整的内容，以及客户需要执行的升级步骤。历史版本按新到旧排列，不能只修改版本号。

## 发布流程

1. 修改版本号时，同步更新 `build.gradle.kts`、`AppIdentity.kt` 和客户教程。
2. 在 `CHANGELOG.md` 顶部添加对应的 `## [版本号]` 条目，使用实际完成并验证的变更；已知限制和必要升级步骤一起注明。
3. 构建 Windows 发布文件，再执行 `scripts/Package-Customer.ps1`。
4. 检查客户包中的“本次更新说明.txt”“更新记录.txt”“使用教程.txt”和“开源致谢.txt”。
5. GitHub Release 的说明使用相同版本条目生成，上传压缩包和对应 SHA256 文件。

两个打包脚本都会校验当前版本的更新条目；缺少条目、条目重复或没有内容时，直接停止打包。客户包自动包含带 UTF-8 BOM 的纯文本更新说明，完整历史与当前版本说明来自同一份文件。

生成某个版本的 GitHub Release 说明示例：

```powershell
$notes = & scripts/Get-ReleaseNotes.ps1 -Version '1.0.12'
[IO.File]::WriteAllText((Join-Path (Get-Location) '.tools/release-notes.md'), $notes.ReleaseMarkdown, [Text.UTF8Encoding]::new($false))
# 使用 gh 的 --notes-file 参数读取该文件，保留实际换行。
```
