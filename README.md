# 白沫播放器 · 二次开发版

> 本仓库是 [Baimo1209/ASMR-Player](https://github.com/Baimo1209/ASMR-Player) 的**非官方二次开发分支**，不是原作者发布的版本。
>
> 原始设计、界面与代码版权归原作者 **Baimo（Baimo1209）** 所有。本分支只在其基础上扩展功能、修复问题，保持「本地播放器、不附带任何音声资源」的定位。
>
> 如果你是原项目的使用者，建议优先使用[原项目](https://github.com/Baimo1209/ASMR-Player)。本分支的问题请提到本仓库，不要去原仓库打扰作者。

一款安卓本地 ASMR 音声播放器，安装后显示名为"白沫播放器"。用于管理手机本地（或局域网 SMB/FTP）的音频、封面图片和 WebVTT / LRC 同步台词，适合 `RJ01534688/1-普通版/MP3` 这类多层作品目录。

**本仓库只包含播放器源码，不包含任何音频、图片或台词资源。**

## 相对原版的改动

| 方向 | 改动 |
|---|---|
| 网络路径 | 新增 SMB（jcifs-ng）与 FTP 支持：添加/测试连接、递归扫描、流式播放、`Range` 拖动定位、远程封面与字幕读取 |
| 图片加载 | 作品列表只加载每个作品的封面；播放页优先封面，滑动到哪张才解码哪张；封面缩略图落盘缓存，同一张图不重复走网络 |
| 大库稳定性 | 扫描结果改为流式 JSON 缓存（作品级图片池 + 原子替换），修复大库 SMB 扫描时 `OutOfMemoryError` |
| 排序规则 | 统一 `NameOrder`：`track` / `tr` 视为序号整体，全角字符归一化，数字 > 英文 > 中文 |
| 封面判定 | 独立 `CoverNames`：`main` / `cover` / `封面` / `表紙` / `ジャケット` 等多语言关键词，支持编号变体，无匹配时以排序第一张兜底 |
| 播放页图片 | 拖动切换改为旧图与新图并排同步滑动，方向由位移决定（不再被松手前的回弹劫持），支持打断 |
| 列表动画 | 音轨列表返回作品列表的覆盖式转场：快照层、坐标修正、筛选按钮淡入 |
| 台词 | 台词页左右滑动返回播放页，异步解析（网络路径不再触发主线程网络异常） |
| 界面 | 白色极简主题 + 深色切换、真实波形进度条、顶部状态栏留白、设置页可滚动、全屏看图白底 |

完整版本差异见 [CHANGELOG.md](CHANGELOG.md)。

## 安装

- 版本：**v1.5.4**（versionCode 9）
- 包名：`com.codex.asmrplayer`
- 系统要求：Android 6.0（API 23）及以上，targetSdk 35
- 下载：本仓库 **Releases** 页的 `*-release.apk`

> **与原版不兼容的地方**：包名相同，但本分支使用**自己的签名**，与原版签名不同。因此
> 1. 无法覆盖安装官方版，需先卸载；
> 2. 卸载会清除应用内缓存、排序、SMB/FTP 配置；
> 3. 两者不能共存。如需并存，需自行修改 `applicationId` 后重新构建。

## 网络路径（SMB / FTP）

设置页 →「添加网络路径」，填写类型、地址、端口、共享名/目录、账号密码，可先「测试连接」再保存。播放时通过本地 `127.0.0.1` HTTP 代理转流，因此支持进度条拖动定位。

```text
SMB：smb://192.168.1.10/共享名/子目录
FTP：ftp://192.168.1.10:21/子目录
```

注意：

- 目前账号密码以明文 JSON 保存在应用私有 `SharedPreferences`（其他应用无法读取，但 root 或备份可获取）。请**不要使用重要的通用密码**，建议为播放器单独建一个只读账号。
- 仅支持 SMB1/2/3（走 jcifs-ng）与 FTP（非 FTPS）。
- 局域网地址请自行确保可信，本应用不校验服务器证书。

## 构建

需要 **JDK 17**、Android SDK（compileSdk 35）。首次构建前请让 Gradle 找到 SDK：

```properties
# local.properties（不要提交）
sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

或设置环境变量 `ANDROID_HOME`。

```bash
# 调试包
./gradlew assembleDebug

# 正式签名包（需要根目录 keystore.properties + keystore 文件，两者均不入库）
./gradlew assembleRelease
```

Windows 下也可用项目自带的脚本，它优先使用仓库内的便携环境（`.gradle-local/`、`.android-sdk/`，均已被忽略），找不到时回退到 Wrapper：

```powershell
.\build-apk.ps1            # debug
.\build-apk.ps1 -Release   # release
```

> 首次运行 `gradlew` 会从 `services.gradle.org` 下载 Gradle 8.10.2（约 130MB）。国内网络较慢时可把
> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip` 之类的镜像。

`keystore.properties` 格式：

```properties
storeFile=your-release.jks
storePassword=******
keyAlias=your-alias
keyPassword=******
```

缺少该文件时 `assembleRelease` 会直接报错退出，不会生成未签名包。

依赖：`eu.agno3.jcifs:jcifs-ng`、`commons-net:commons-net`、`org.slf4j:slf4j-nop`。

## 权限与隐私

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 仅「台词悬浮窗」功能使用，不开启则不需要 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 仅用于你**主动添加**的 SMB/FTP 局域网路径；不访问互联网 |

- 不申请全盘存储权限，只通过系统文件夹选择器读取你授权的目录。
- 不收集、不上传任何文件列表、播放记录、音频、图片或台词；无统计 SDK、无广告。
- 明文 HTTP 仅允许 `127.0.0.1` / `localhost`（供内部转流代理使用）。
- 本地缓存包含作品列表、排序、近期播放记录与网络源配置，已关闭系统自动备份。

## 已知问题

- 路径中含有 `%` 的作品（如 `亲密度100%…`）在网络路径下读取图片会失败：`net://` URI 未做百分号编码，jcifs 会误判为非法转义。待修复。
- 大库首次升级到 v1.5.3+ 会重建一次缓存（`library_cache_v4.json`），期间列表稍慢。

## 免责声明

- 本项目仅为播放器工具，不包含、不分发任何音频、图片、字幕资源，也不提供任何内容获取途径。
- 请确保你播放的内容来源合法，并遵守当地法律法规。
- 二次开发分支按「现状」提供，不附带任何明示或暗示的担保；使用风险自负。

## 授权与致谢

- 原项目：[Baimo1209/ASMR-Player](https://github.com/Baimo1209/ASMR-Player)，作者 **Baimo**。原仓库未附开源许可证，本分支以 GitHub Fork 形式发布并保留原作者署名与链接；如原作者对本分支有异议，将立即调整或下线。
- 本分支新增代码同样不声明额外许可，默认沿用原项目的授权状态。
- 第三方库授权：jcifs-ng（LGPL-2.1）、Apache Commons Net（Apache-2.0）、SLF4J（MIT）。

如果这个分支对你有帮助，请先给[原项目](https://github.com/Baimo1209/ASMR-Player)点个 Star。
