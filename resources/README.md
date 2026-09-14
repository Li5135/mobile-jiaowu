# 自定义图标素材

把图标源文件放到**本目录**，GitHub Actions 构建时会自动生成全套 Android 图标与启动图。
不放也可以，构建会跳过这一步并沿用仓库内现有图标。

## 需要放的图

| 文件名 | 尺寸 | 说明 |
|---|---|---|
| `icon.png` | 1024×1024 | 必需。应用图标（圆角由系统裁切，四角留白即可） |
| `icon-foreground.png` | 1024×1024 | 可选。自适应图标前景层，图形要放中间约 60% 安全区内 |
| `icon-background.png` | 1024×1024 | 可选。自适应图标背景层，纯色或纹理 |
| `splash.png` | 2732×2732 | 可选。启动图，不放则用 `icon.png` 居中生成 |

只有 `icon.png` 时也能生成完整图标集，Android 8+ 会拿它当作自适应图标的前景层使用。

## 生成的命令

CI 里执行的是：

```bash
npx @capacitor/assets generate --android
```

本地想先看效果可以跑同样的命令（需要 Node 环境），生成结果会直接覆盖
`android/app/src/main/res/mipmap-*` 与 `drawable-*` 下的图标文件。

## 为什么不是直接替换 png

`android/app/src/main/res/mipmap-*` 下每种密度都需要对应尺寸的图标，手工裁剪
容易在某个密度上糊掉或缺文件。用工具从一张 1024×1024 源图生成可以保证
各密度一致，也方便以后换图标时只改一张图。
