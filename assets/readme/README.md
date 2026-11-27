# README 视觉资源说明

本目录存放 `README.md`（中文）与 `README.en.md`（英文）使用的视觉资产，采用 **Hybrid SVG 混合图层合成首屏（Hero PNG）+ 纯静态 SVG 流程图** 方案。

## 文件清单

| 文件 | 用途 | 尺寸 / 画布 |
| --- | --- | --- |
| [`hero.png`](hero.png) | 中文 README 首屏主视觉横幅（由 `source/hero-layout.svg` 与 `source/hero-subject.png` 合成） | 1600 × 560（1200 × 420 坐标系） |
| [`hero-en.png`](hero-en.png) | 英文 README 首屏主视觉横幅（由 `source/hero-layout-en.svg` 与 `source/hero-subject.png` 合成） | 1600 × 560（1200 × 420 坐标系） |
| [`hero-banner.svg`](hero-banner.svg) | 纯 SVG 静态矢量备用首屏横幅 | 1200 × 420 |
| [`architecture.svg`](architecture.svg) | 中文「三部分怎么配合工作」大白话结构图 | 1200 × 540 |
| [`architecture-en.svg`](architecture-en.svg) | 英文「How Do the Three Parts Work Together」结构图 | 1200 × 540 |
| [`security-model.svg`](security-model.svg) | 中文「决定权永远在你的手机上」四步把关与安全红线图 | 1200 × 460 |
| [`security-model-en.svg`](security-model-en.svg) | 英文「Your Phone Always Has the Final Say」权限把关图 | 1200 × 460 |
| [`source/hero-layout.svg`](source/hero-layout.svg) | 中文首屏可编辑 SVG 排版源图层 | 1200 × 420 |
| [`source/hero-layout-en.svg`](source/hero-layout-en.svg) | 英文首屏可编辑 SVG 排版源图层 | 1200 × 420 |
| [`source/hero-subject.png`](source/hero-subject.png) | 抠图去绿边后的透明背景工业风手机与轨道节点图层 | 1024 × 1024 RGBA |
| [`source/hero-prompt.txt`](source/hero-prompt.txt) | 主体插画生成提示词记录 | 文本 |
