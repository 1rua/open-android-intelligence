# KaTeX 0.16.22 离线资源（数学公式渲染）

本目录是 Android 聊天界面数学公式渲染用的 **KaTeX 离线资源**，由
`com.openandroidintelligence.conversation.workbench.MarkdownMath` 通过
`WebView.loadDataWithBaseURL("file:///android_asset/katex/", …)` 加载。
`src/main/assets/` 会被 AGP 自动打进 AAR/APK，**不需要任何构建脚本改动**，也不新增 Gradle 依赖。

## 来源与版本

| 项目 | 值 |
| --- | --- |
| 上游项目 | [KaTeX](https://katex.org/) |
| 版本 | `katex@0.16.22`（npm 发行包） |
| 许可证 | MIT（© 2013-2020 Khan Academy and other contributors，全文见本目录 `LICENSE`） |
| 下载日期 | 2026-10-21 |
| 下载源 | jsDelivr npm CDN（`https://cdn.jsdelivr.net/npm/katex@0.16.22/`） |

逐文件下载地址（均为同一版 `dist/` 产物）：

- `katex.min.js` ← `https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.js`
- `katex.min.css` ← `https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.css`
- `LICENSE` ← `https://cdn.jsdelivr.net/npm/katex@0.16.22/LICENSE`
- `fonts/KaTeX_*.woff2` ← `https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/fonts/KaTeX_*.woff2`（共 20 个）

**未修改任何第三方文件内容**：`katex.min.js`、`katex.min.css`、`fonts/*.woff2`、`LICENSE`
与上游逐字节一致（SHA256 见下表）。唯一差异是**没有下载** `woff` / `ttf` 字体，原因见下一节。

## 为什么只保留 woff2

KaTeX 的 `katex.min.css` 为每个字族声明了三级回退：

```css
src: url(fonts/KaTeX_Main-Regular.woff2) format("woff2"),
     url(fonts/KaTeX_Main-Regular.woff) format("woff"),
     url(fonts/KaTeX_Main-Regular.ttf) format("truetype");
```

- 本应用的 `minSdk = 31`，系统 WebView 至少在 Chromium 90 以上，**全部支持 WOFF2**；
  浏览器只会下载第一种能用的格式，`woff` / `ttf` 永远不会被请求。
- 保留三级回退声明会多带约 1.7 MB 无效资源（woff ≈ 570 KB、ttf ≈ 1.1 MB），
  而这些文件在目标设备上永远不会被使用。
- 因此这里**只裁掉字体文件**，`katex.min.css` 里的 `woff` / `ttf` 兜底项**保持上游原样**，
  避免修改第三方文件产生分叉。若未来要支持不支持 WOFF2 的老 WebView，
  需要按同一版本补回 `fonts/*.woff`，无需改动 CSS。
- 没有裁剪任何字形：20 个 woff2 覆盖了 KaTeX 全量字族（AMS、Caligraphic、Fraktur、
  Main、Math、SansSerif、Script、Size1-4、Typewriter）。

## 体积

| 内容 | 字节 |
| --- | ---: |
| `katex.min.js` | 276,701 |
| `katex.min.css` | 23,352 |
| `fonts/*.woff2`（20 个） | 259,792 |
| `LICENSE` | 1,107 |
| **合计** | **560,952**（约 548 KiB） |

## 逐文件 SHA256

```
e8d885505949f3a5f4abdd5dd0d53696bd1371ad26ffbf4f310dcd77c8cdae89  katex.min.js
19095127357ed6d29fe0a63a6b000c913a89f7f1963b765dd3715e97c9852e75  katex.min.css
766ccc1f306c885aa45542a9846bbd0a505b27a0374f146778171c2254ce18e3  LICENSE
0cdd387c9590a1a9f9794560022dbb59654a7d86f187aa0c81495ad42d3a7308  fonts/KaTeX_AMS-Regular.woff2
de7701e42cf1f4cf0b766c03fb27977207eee2f4fd5d76fa82188406da43ea4c  fonts/KaTeX_Caligraphic-Bold.woff2
5d53e70ad607c2352162dec9e0923fb54ecdafaccbf604cd8dcf7d00facb989b  fonts/KaTeX_Caligraphic-Regular.woff2
74444efd593c005e3f4573b44524704c0af0a937fe911cca9e94068d0d140d3f  fonts/KaTeX_Fraktur-Bold.woff2
51814d270d06ff0255dba0799994fa4d8c84d11f09951d47595f4abb1f3602dc  fonts/KaTeX_Fraktur-Regular.woff2
99cd42a3c072d918f2f44984a807cf7aa16e13545fd0875fc07c6c65f99e715b  fonts/KaTeX_Main-BoldItalic.woff2
0f60d1b897938ec918c8ce073092411baf9438f6739465693ff18b0f9d20b021  fonts/KaTeX_Main-Bold.woff2
97479ca6cce906abc961ecac96faa5f9ca2e61b8e7670d475826bcdee9a7c267  fonts/KaTeX_Main-Italic.woff2
c2342cd8b869e01752a9321dc17213fc40d4d04c79688c1d43f2cf316abd7866  fonts/KaTeX_Main-Regular.woff2
dc47344dbb6cb5b655c8460d561f4df5f501b90c804ad3c6cec65fe322351ab1  fonts/KaTeX_Math-BoldItalic.woff2
7af58c5ec8f132a2ddde9027c6d7814decce4d3b822a11192a42a20e2e973264  fonts/KaTeX_Math-Italic.woff2
e99ae51144bf1232efcc1bfe5add36262c6866b0faab24fa75740e1b98577a62  fonts/KaTeX_SansSerif-Bold.woff2
00b26ac825e2095056396e0553b8ac26d3f8ad158c3826e28b4c45b385c4714a  fonts/KaTeX_SansSerif-Italic.woff2
68e8c73ef42afd3ccec58bf0fba302cce448938e7fc020a5e31f8a952eee1342  fonts/KaTeX_SansSerif-Regular.woff2
036d4e95149b69ff9bcc0cd55771efeb25ffa3947293e69acd78d5ac328c684b  fonts/KaTeX_Script-Regular.woff2
6b47c40166b6dbe21a5dfca7718413f2147fd2399be1ba605d8ad39cedf25dfe  fonts/KaTeX_Size1-Regular.woff2
d04c54219f9eaec6d4d4fd42dfb28785975a4794d6b2fc71e566b9cd6db842dd  fonts/KaTeX_Size2-Regular.woff2
73d591271b1604960cb10bb90fee021670af7297017e0e98480b332d11f51995  fonts/KaTeX_Size3-Regular.woff2
a4af7d414440a1c1790825cfb700cf9cf43b0f2c4b04f0ebc523011ad9853ec0  fonts/KaTeX_Size4-Regular.woff2
71d517d67827787cfabdf186914cc3358eda539e37931941f2b2fd4a21f68c0b  fonts/KaTeX_Typewriter-Regular.woff2
```

复现校验：`sha256sum katex.min.js katex.min.css LICENSE fonts/*.woff2`。

## 更新方式

升级版本时按「来源与版本」一节重新下载同版本 `dist/` 的 `katex.min.js`、`katex.min.css`、
`LICENSE` 与 `fonts/*.woff2`，覆盖本目录后更新上表的版本号、体积与 SHA256；
同步检查 `MarkdownMath.kt` 用到的 KaTeX 选项（`displayMode` / `throwOnError` / `errorColor`）
在该版本仍然支持。**不要**引入网络加载（CDN）作为回退：公式渲染必须在完全离线、
`WebSettings.blockNetworkLoads = true` 的 WebView 里工作。
