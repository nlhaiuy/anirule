# animeko-rulegen

自动生成 Animeko `web-selector` 数据源规则（目前只覆盖"搜索条目"这一步）。

输入：搜索页 HTML + 一个能搜到结果的关键词 + 搜索链接模板。
输出：规则 JSON（格式参考 open-ani/animeko PR #3473，version 2），并用同一份页面自动测试。

## 目录

- `proto/`：Python 原型，已用 unittest 跑通 9 个用例（`cd proto && python3 -m unittest -v test_rulegen`）
- `core/`：Kotlin/JVM 版本（Jsoup + kotlinx.serialization），由原型移植，测试用例一一对应
  - 运行：`gradle :core:test`（需要能访问 Maven Central / Google Maven）
  - 注意：此版本尚未在编译环境里跑过

## 生成逻辑

1. 找出文本包含关键词的最深元素（跳过 script / input / form 等）。
2. 按结构分组，挑"可解析的不同链接最多"的一组（排除页头回显关键词）。
3. 名称在 `a` 里 → `a` 格式；否则 → `indexed`（名称与链接两条选择器）。
4. 选择器只用 tag + 稳定 class + `>`，取能精确选中同一批元素的最短形式，不用 nth。
5. 测试：条目数 > 0、名称数 = 链接数、无空链接、标题中含关键词。

## 只输入网址（Discover）

`discover(url, fetch)`：抓首页 → 从 GET 搜索表单推导搜索链接模板（含 hidden 参数）→ 失败则试常见搜索路径 →
依次用热门番名搜索 → 生成并测试规则。`fetch` 由调用方注入（Android 上用 OkHttp）。
遇到 POST 表单、JSON 响应、请求失败时，会在 `notes` 里写明原因。

## json-api 数据源（JsonApi / jsonapi.py）

输入：搜索、详情、播放三个阶段各自的"请求模板 + 一份 JSON 响应样本"，输出 json-api v1 规则。
- 搜索：找出含关键词的对象数组 → `selectItems / selectName / selectId / selectAliases`
- 详情：找带集数字段的数组 → 剧集各字段；同级的另一个名称数组 → `grouped` 线路
- 播放：找 http 视频地址 → `selectCandidates / selectCandidateChannel / selectVideoUrl`
- 请求里的关键词字面量自动换成 `{keyword}`；`wrapExport` 生成 `schemaVersion: 2` 的导入包装
- 默认配置与编辑器导出的空白模板逐字段一致（`fixtures/blank_json_api.json` 校验），
  并校验生成结果包含真实样例（`fixtures/sample_json_api.json`，密钥已脱敏）的全部字段
- 仍需用户提供请求（网址、方法、请求体）。页面 HTML 里看不出接口，需要从浏览器开发者工具的"网络"面板复制

## 全自动流程（Flow + capture/capture.js + app/）

只输入网址：应用在内置 WebView 里自动完成全部步骤。
1. `capture/capture.js` 在页面加载前注入：劫持 fetch / XHR / pushState，记录请求（含请求头、请求体）与响应；
   并提供 `__rg.fillSearch / openSearch / clickResult / clickEpisode / clickPlay / dom`。
2. `Flow.run(url, browser)`：自动填搜索框 → 抓到含关键词的 JSON 响应 → 点进第一个结果 → 点第一集（必要时再点播放）。
   - 请求里的关键词、条目 ID、剧集 ID、页码、每页条数自动换成 `{keyword}` `{id}` `{page}` `{pageSize}`
   - 详情页/播放页的网址自动变成 `subjectUrlTemplate` / `episodeUrlTemplate`
   - 请求头里由 JS 设置的自定义头（如 apikey）自动写入 `headers`
   - 搜索结果不是 JSON 时，改用渲染后的 DOM 生成 HTML 规则
   - 抓不全时给出"部分成功"（只有搜索阶段，或缺播放阶段）并写明原因
3. `app/`：WebView 适配层（`WebViewBrowser`）与一个输入框的界面（`MainActivity`）。

测试：`cd capture && node --test capture.test.js`（15 个）；`cd proto && python3 -m unittest`（47 个）。
`app/` 与 Kotlin 部分尚未编译。

## 尚未支持

- 只输入网址就自动发现 json-api 接口（做不到可靠推导）
- `{page}` / `{pageSize}` 分页占位符、`subjectUrlTemplate`、`episodeUrlTemplate` 的自动推导（目前留空或默认）
- 线路在剧集内部嵌套的响应（`channelFormatGrouped` 的相对路径语义未确认）
- 线路/剧集（channel）格式的 HTML 选择器、JS 动态加载的页面、AI 兜底、点选修正
