# 真实歌词搜索回归测试

显式运行线上搜索和取词，直接编译应用的 QQ、网易、LRCLIB 客户端、签名、QRC 解密、匹配及解析代码。不会使用录制响应代替实测。需要 Python 3、JDK 17+ 和网络，不需要 Android SDK。首次运行下载固定版本的 Maven 依赖。

```sh
# 仅离线回归，不访问歌词服务
python scripts/live-search/run.py --unit-only

# 固定的 100 首榜单样本，每首分别测试三个来源
python scripts/live-search/run.py --output build/live-search/hot-100.jsonl

# 三个历史问题样本
python scripts/live-search/run.py --dataset scripts/live-search/regressions.json --limit 3 \
  --output build/live-search/regressions.jsonl

# 显式生成新榜单样本；不会自动覆盖已有测试数据
python scripts/live-search/sample.py --output build/live-search/hot-100-new.json
```

`hot-100.json` 冻结中、日、美 Apple Music 热门歌曲榜各 40 / 30 / 30 首，跨榜按歌曲名和歌手去重，按榜单次序补足。每首保存原始歌名、歌手、榜单排名、官方链接；专辑和时长通过 Apple iTunes Lookup 补全，查不到时保持未知。它是三个地区榜单的抽样，不代表全球综合前 100 名。

每个来源单独有 **10 秒总预算**，默认最多 3 个并发任务，每个来源的任务启动至少间隔 3 秒，任务结束后另等待 250ms，减少批量测试引发的限流。可通过 `--source-spacing-ms` 调整节流间隔，排队等待不占生产请求的 10 秒预算。遇到 429 会停止后续该来源的请求并标记 `SKIPPED_RATE_LIMIT`（未实际测试），应等待服务允许后另开批次，不用反复重试把限流混成无歌词。这里会独立测试 LRCLIB，便于比较服务情况；应用中它仍只在网易、QQ 均无可用结果时兜底。应用的主源并行竞争和缓存规则没有改成三源竞争。

输出 JSONL 逐项落盘，重复运行同一个输出路径会跳过已完成的歌曲/来源；全新复测应使用新路径。输出旁的 manifest 保存源码和数据集指纹，代码或样本变化后会拒绝复用旧输出，避免把两版结果混为一轮。记录查询、候选元数据、使用别名和合唱次序校验前后的匹配分（都保留本轮的版本、时长校验）、选中歌曲 ID、时长、逐字/逐行、失败类别，不保存歌词正文或认证信息。成功取得歌词不等于人工核验了每个字和每个版本，低分和版本差异仍需审查。生产代码会剔除歌名和制作名单，并将明显短预览回退到逐行歌词；完整性判定属于启发式，不能替代全曲听校。

状态：`WORD` 逐字、`LINE` 逐行、`INSTRUMENTAL` 服务明确标注纯音乐、`NO_MATCH` 无候选通过匹配、`NO_SYNCED_LYRICS` 有匹配但无同步歌词、`REQUEST_FAILED` 请求失败、`TIMEOUT` 超过总预算。

主机适配仅替代 Android 的 Bitmap/Context/Build 和 Base64/HTML 实体工具。网络、搜索、签名、解密、解析、评分、取消均使用生产代码。主机 JDK、出口、HTTP 缓存和手机环境不同，因此结果用于复现和回归，不等于手机网络可用率。工具识别无认证的 HTTPS_PROXY；有认证代理需自行配置 JVM，避免泄露凭据。

线上测试不加入每次提交的必过 CI，以免第三方波动导致构建不稳定。Android CI 继续运行离线单元测试和 APK 构建。
