# WristOTP

[English](README.md) | 简体中文

WristOTP 是一个独立原生 Wear OS 验证码应用，面向手表本地使用。

它适合没有 Google Mobile Services、Play Services、手机节点同步、外部存储权限或可用系统文件选择器的手表环境。验证码数据保存在手表本地，备份导入通过手表临时开启的 HTTP 上传页面完成，用户可以用同一局域网内的手机浏览器上传备份文件。

## 截图

以下截图使用自动生成的演示验证码数据。

<p>
  <img src="docs/screenshots/wristotp-list.png" width="220" alt="WristOTP 列表页">
  <img src="docs/screenshots/wristotp-detail.png" width="220" alt="WristOTP 详情页">
</p>
<p>
  <img src="docs/screenshots/wristotp-drawer.png" width="220" alt="WristOTP 顶部抽屉">
  <img src="docs/screenshots/wristotp-import.png" width="220" alt="WristOTP 导入页">
</p>

## 功能

- 独立 Wear OS APK
- 不依赖 Google Play Services、Wearable API、手机端伴侣应用或云同步
- 列表页直接显示 TOTP 验证码
- 每个 TOTP 条目使用横向倒计时进度条
- 详情页显示更大的验证码和倒计时
- 顶部抽屉包含 `All`、`Import backup` 和备份分类
- 列表支持长按拖动排序
- 主数据存储在应用私有目录 `context.filesDir`
- 通过手表临时 HTTP 服务导入备份
- 内置英文和简体中文文案

## 支持的 OTP 数据

- `otpauth://totp/...`
- SHA1、SHA256、SHA512
- 6 位和 8 位验证码
- 支持自定义 TOTP period，默认 30 秒
- Base32 secret 兼容大小写、空格、连字符和常见 padding 差异
- HOTP 元数据可导入保留，但暂不实现动态 HOTP 生成

## 备份导入

在手表端打开 `Import backup`。WristOTP 会临时启动端口为 `8765` 的 HTTP 服务，并显示类似下面的局域网地址：

```text
http://192.168.x.x:8765/
```

用同一局域网内的手机浏览器打开该地址，选择备份文件；如果备份已加密，在网页中输入备份密码。HTTP 服务只在导入页面打开期间运行。

支持输入：

- `.txt`：每行一个 `otpauth://` URI
- `.html` / `.htm`：从 HTML 的 code/link/text 内容中解析 OTP URI
- `.stratum` 或未知扩展名：兼容 Stratum 备份 JSON、强加密备份和旧版加密备份

Stratum 兼容逻辑基于公开备份格式实现。本仓库不 vendoring，也不复制 Stratum 源代码树。

## 存储

运行时数据保存在应用私有内部存储目录：

```text
context.filesDir/
  authenticators.json
  categories.json
  preferences.json
  icons/<icon-id>
```

首版会将 OTP secret 以明文 JSON 保存到应用私有目录。Android 应用沙盒能避免普通跨应用访问，但这不是额外加密层。清除缓存不应删除验证码；清除应用数据会删除验证码。

## 构建

依赖：

- JDK 17
- Android SDK API 36 平台和匹配的 build tools
- Gradle 可访问 Maven 仓库

构建 debug APK：

```bash
./gradlew :app:assembleDebug
```

运行 core 测试：

```bash
./gradlew :core:test
```

构建未签名 release APK：

```bash
./gradlew :app:assembleRelease
```

签名密钥、本地 SDK、APK 和其他生成的 release 产物不会提交到仓库。

## 项目结构

```text
app/   Wear OS Android 应用和 UI
core/  OTP 生成、备份解析、加解密、HTTP 导入和存储
```

## 许可证

本项目使用 MIT License。见 [LICENSE](LICENSE)。
