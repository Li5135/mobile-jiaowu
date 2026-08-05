# 移动教务 Android App（WebView 套壳）

把衡阳师范学院移动教务 H5（https://hysfjwyd.hynu.edu.cn/dist/）打包成 Android App。
App 内嵌 WebView 直接加载线上网站，网站更新后 App 自动跟随，无需重新安装。

## 构建方式：GitHub Actions 云端构建（无需本地安装任何 Android 工具）

### 第一次使用

1. **在 GitHub 上新建一个仓库**（Public 或 Private 均可），例如 `mobile-jiaowu`。不要勾选"Add a README"等初始化选项。
2. 把本目录推上去（在本目录执行，`YOUR_REPO` 换成你的仓库地址）：

   ```bash
   git init
   git add .
   git commit -m "init: 移动教务 WebView App"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

3. 打开 GitHub 仓库页面 → 顶部 **Actions** 标签 → 会看到一个名为 `Build Android APK` 的工作流正在运行。等它跑完（约 3~5 分钟），点进该次运行，底部 **Artifacts** 区域会出现一个 `移动教务-debug-apk` 压缩包。
4. 下载解压得到 **`app-debug.apk`**，传到手机（Android 7.0+）直接安装即可。安装时提示"未知来源"，允许即可。

### 之后想再打一次包（改了配置或想重新构建）

两种方式任选：
- 仓库页面 → **Actions** → `Build Android APK` → 右侧 **Run workflow** 按钮手动触发；
- 或改任意文件后 `git push`（push 到 main 分支会自动触发构建）。

## 常用配置（改完记得 push 重新打包）

配置文件：`capacitor.config.json`

| 配置项 | 含义 |
|---|---|
| `server.url` | App 打开的网址。学校换域名时改这里 |
| `server.allowNavigation` | 允许在 App 内跳转的域名白名单 |
| `appName` / `appId` | 应用名称 / 应用唯一标识（上架前不要改 appId） |

## 自定义 App 图标

默认使用 Capacitor 自带图标。替换方法（二选一）：
- 简单：把 `android/app/src/main/res/mipmap-*` 各尺寸的 `ic_launcher*.png` 换成自己的同名图片（需要 48/72/96/144/192px 等规格）；
- 规范：在 GitHub Actions 里加一步用 `capacitor-assets` 工具自动生成全套图标（可后续补充）。

## 常见问题

- **APK 提示"已安装应用签名冲突"**：不同机器/不同方式打的 debug 包签名不同。一直用 GitHub Actions 的包即可；正式长期使用建议做 release 签名（可后续补充）。
- **打开后白屏或无法访问**：确认手机网络能访问 `hysfjwyd.hynu.edu.cn`（校园网/公网均可）。
- **App 里点链接跳到外部浏览器**：Capacitor 默认行为；如需全在 App 内打开，可调整 `allowNavigation`。

## App 内置功能（壳层）

- **免重复登录（尽力而为）**：登录后 App 自动把登录 cookie 快照存到本地，下次启动自动注入（转成长效 cookie）。注意：学校网站用的是"会话级"登录态（手机浏览器里关掉重开同样要重登），此功能能否生效取决于学校服务器会话有效期；如果服务器会话很短，仍可能要求重新登录。
- **退出登录 / 切换账号**：App 左下角有半透明"退出"悬浮按钮 → 点"退出"清除登录状态回到登录页，重新登录即切换账号。
- **返回键行为**：站内先返回上一级（SPA hash 路由由 App 维护导航栈），回到首页后再按一次返回键才退出应用（2 秒内连按两次）。
  - 相关代码：`android/app/src/main/java/com/hynu/jiaowu/MainActivity.java`（cookie 快照保存/注入、悬浮按钮、返回键导航栈）。
  - 提示：悬浮按钮可能遮挡网页左下角内容，后续可做成可拖拽或折叠菜单。

