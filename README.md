# Typecho App

一个给 [Typecho](https://typecho.org/) 博客用的原生 Android 客户端：**Kotlin + Jetpack Compose + Material 3**，数据全部走 Typecho 自带的 XML-RPC 接口，不依赖任何服务端插件就能看文章、写文章、管评论。

界面没有用 Material 默认那套（药丸高亮、tone 容器、TopAppBar），而是**深度仿小红书**：双列瀑布流、单列大图日记、笔记式详情页、自绘底部导航 + 居中的「+ 发布」方块。

它是我给自己博客（团子笔记）写的日常用 App，所以「一个博主 + 一台手机」这条路径上的细节做得比较满：离线补交、文章缓存、图片压缩上传、字体缩放全局生效。

想直接装：[Releases](https://github.com/qine233/typecho-app/releases/latest) 里有 CI 打好的 debug 包（Android 8.0+ 可装）。

| 首页：双列瀑布流 + 分类筛选条 | 日记动态：按日期分组的单列大图 | 详情页：笔记式正文 + 固定互动栏 |
| --- | --- | --- |
| ![首页](docs/screenshots/1-home.png) | ![日记](docs/screenshots/2-diary.png) | ![详情](docs/screenshots/3-post.png) |

## 能做什么

**阅读**
- 首页双列瀑布流，顶部搜索药丸 + 分类横向筛选条；翻页走站点真实分页参数，配骨架屏与进场动画
- 「日记动态」标签：把归在日记分类下的文章按日期倒序排成时间线，单列大图
- 详情页：多图 `HorizontalPager` 轮播 + 全屏查看器、正文图片懒加载、评论按 `parentId` 还原成嵌套回复树
- 底部固定互动栏：赞 / 收藏 / 评论入口，点赞收藏状态写本机，下次进来还在

**写作与管理**（登录账号是管理员时才会出现「管理」标签）
- 编辑器：Markdown 正文 + 实时预览、撤销/重做、全屏沉浸写作、分类与标签、存草稿
- 插图：从相册选图 → 本地压缩 → `metaWeblog.newMediaObject` 上传 → 正文内联
- 管理台：文章列表（发布/草稿/私密状态切换、删除）、评论审核（通过 / 垃圾 / 删除 / 回复）、页面列表

**健康记录**（可选，需要站点装 NoLineApi 插件，见下）
- 体重 / 体脂率按天记录，概览页给出当前值、BMI、目标进度、7/30/90 天变化与趋势折线图
- 断网时先进本机队列，恢复后自动补交；接口按天整体覆盖，重放安全

**通用**
- 深色模式（跟随系统 / 浅色 / 深色）、字体缩放（全局生效，包括正文渲染区）
- 文章列表持久化缓存，冷启动先出内容再刷新
- 退出登录会清掉本机保存的凭证

## 技术栈

| | |
| --- | --- |
| 语言 / UI | Kotlin 2.1、Jetpack Compose（compose-bom 2024.12.01）、Material 3 |
| 架构 | 单 Activity + Navigation-Compose；`AppContainer` 手写依赖注入；ViewModel + StateFlow |
| 网络 | OkHttp 4.12；XML-RPC 自己实现（`XmlRpcClient` 手写编解码，无第三方 XML-RPC 库）；NoLine 走 kotlinx.serialization |
| 图片 | Coil 3 |
| 存储 | DataStore Preferences（配置、互动状态）、Android Keystore AES-256-GCM（接口密钥）、JSON 文件（文章缓存 / 健康离线队列） |
| 构建 | AGP 8.7.3、Gradle 8.9 wrapper、JDK 17、minSdk 26 / target & compileSdk 35 |
| 测试 | JUnit 4 + kotlinx.serialization，39 个单元测试（`app/src/test`） |

## 目录结构

```
app/src/main/java/noline/zhouzhou/
├── MainActivity.kt / AppContainer.kt      # 单 Activity + 依赖容器
├── data/
│   ├── model/       # Post / Comment / HealthOverview 等数据类
│   ├── remote/      # XmlRpcClient（协议编解码）、TypechoApi（业务方法）、NolineApi（REST）
│   └── repository/  # 缓存、密钥、离线队列、各业务的仓库层
└── ui/
    ├── navigation/  # NavGraph + 自绘 TypechoTabBar
    ├── components/  # 卡片、评论项、小红书式头部/引导文案
    ├── screens/     # home / diary / post / editor / admin / health / settings / login
    └── theme/       # 配色（暖棕强调色 + 灰底白卡）、字体
```

## 快速开始

```bash
git clone https://github.com/qine233/typecho-app.git
cd typecho-app
# 指向本机 Android SDK（这个文件已在 .gitignore 里）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 和 Android SDK 35。包名 `noline.zhouzhou`，debug 构建带 `.debug` 后缀（`noline.zhouzhou.debug`），可以和 release 并存。

跑测试：

```bash
./gradlew testDebugUnitTest
```

## 自动构建与下载

`.github/workflows/build-apk.yml` 在 GitHub 上跑编译，不用本地装 Android Studio：

| 触发 | 结果 |
| --- | --- |
| 推 `v*` 标签（如 `v1.0.0`） | 建 Release，把 APK 作为附件挂上去，可直接下载装机 |
| push 到 `main` / PR / 手动 Run workflow | 只跑测试 + 编译，APK 放在那次运行的 **Artifacts** 里 |

所以想要一个能装的新包：合并到 `main` 后去 Actions 页面拿 Artifacts；想给外人一个稳定入口，就打 tag。

发的是 **debug 签名**包（Android 通用 debug keystore），能直接安装，但每次覆盖安装都算同一个签名。要出正式签名的 release 包，需要自己补两步：

1. 仓库 Settings → Secrets and variables → Actions 里加 `KEYSTORE_BASE64`（`base64 -w0 your.jks` 的结果）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
2. `app/build.gradle.kts` 的 `release` 里加 `signingConfig`，从上面几个 secret 读，再在工作流里加一步 `assembleRelease` 并把产物一起上传

没有把 keystore 塞进工作流，是因为私钥一旦进过仓库历史就收不回来。

## 登录：直连站点自带的 XML-RPC

App 只认站点自己的 XML-RPC 端点，不需要额外配置。

1. 登录页填三项：
   - **站点地址**：`https://your-blog.com`（必须 HTTPS —— `network_security_config` 关掉了明文流量，http 站点会被系统直接掐断）
   - **XML-RPC 路径**：默认 `/action/xmlrpc`，改过伪静态的按实际填
   - **用户名 / 密码**：Typecho 账号
2. 「记住登录状态」勾上才会把密码留在本机（DataStore 明文，见「已知限制」）

XML-RPC 是 Typecho 核心自带的（`var/Widget/XmlRpc.php`，路由 `do(action='xmlrpc')`），后台没有开关，装了站点就能直连。

登录时先调 `blogger.getUsersBlogs` 验证，并从返回里读 `isAdmin`，决定要不要给你看「管理」和「健康」标签。

用到的接口方法：`blogger.getUsersBlogs` / `blogger.deletePost`、`metaWeblog.getRecentPosts` / `getPost` / `newPost` / `editPost` / `getCategories` / `newMediaObject`、`wp.getComments` / `newComment` / `editComment` / `deleteComment` / `getPages`。

> Typecho 的 XML-RPC 和 WordPress 有几处不一致（参数顺序、`post_status` 命名、PHP 8 的 warning 会混进响应头），`XmlRpcClient` 里都按 Typecho 的实际行为处理了。

## 健康记录：需要 NoLineApi 插件

这个功能是可选的，不装插件其余功能照常。「健康」标签只对管理员账号显示，读者账号看不到；没配好接口时点进去是一页配置引导，不会白屏。前提是站点装了配套的 `NolineApi` Typecho 插件（REST 端点：`ping`、`theme`、`health`、`health/records`）。

配置四步：

1. 后台装好并启用 NolineApi 插件
2. App 设置 →「健康记录接口」→ 填接口密钥（就是站点的账号密码，插件按这个鉴权）
3. 只想在 App 里看不记的话，后台别开「允许写入」；要记就打开
4. 点「保存并自检」

自检会真的跑一遍读写：先按 `/api/…` → `/index.php/api/…` → `/?noline_api=…` 三种 URL 形态探测出可用的一种并记住，再发一次读取和一次「必定无效」的写入探针（weight 0，服务端会直接拒掉，不落库），从而区分「密钥错」「站点关了」「只读」「路径不通」。结果卡片会用不同文案告诉你卡在哪一步，包括把服务器原话和解析报错分开显示。

写记录前 App 本地先校验：日期必须是不晚于今天的 `yyyy-MM-dd`（严格解析，`2026-02-30` 这种会被拒）、体重在 20–400 kg、体脂率异常只提示不拦。

## 已知限制

- **登录密码**：勾了「记住登录状态」时以明文存在 DataStore 里（XML-RPC 每次请求都要带原文密码）。NoLine 密钥则走 Keystore 加密。想统一的话，把密码也搬进 `SecretStore` 就行。
- **明文站点**：`network_security_config` 全局禁了 cleartext，http 站点用不了，需要自己放开。
- **多作者场景没做过**：分类判定、日记时间线这些逻辑是按「站主一个人写」设计的，换个多人博客可能要做适配。
- **点赞 / 收藏只在本机**：Typecho 没有对应的原生接口，所以是本地状态，换设备不会同步。
- 没写 LICENSE，默认保留所有权利；要用先找我。

## 说明

仓库里的截图来自 Android 模拟器，展示的是我自己的博客内容。
