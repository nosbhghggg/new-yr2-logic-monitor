# yr2-logic-monitor


这是一个 Mindustry 逻辑辅助模组。
This is a Mindustry logic-assistance mod.


- Logic Monitor — real-time logic & hardware inspector.
- QQGroup: 615081546 (adapted for v160.5, desktop & Android merged jar)

Language / 语言: English · **[中文说明](#功能介绍中文)**

## Features

### 1. Main window (yr2lm)

- add: Pick a block to monitor
- copy: Copy block config & code
- paste: Paste config to target block
- Monitor List:
  - Toggle window visibility
  - Manually refresh window
  - Remove & close window

### 2. Logic Processor

- Variable Tools:
  - Reset @counter to zero
  - Reset debug session globally
  - Persistent entity tracer line
  - Toggle page: variables / editor
  - Search: Filter variable names
  - \[Cc\] Case-sensitive toggle
  - \[W\] Whole-word match toggle
  - textBuffer: Print-buffer monitor
- Code Editor:
  - Reload block code
  - Save & commit code to block
  - Toggle single / dual-pane
  - Pause / Resume
  - Step one instruction
  - Run to next breakpoint
  - Toggle trace panel
- Trace Timeline:
  - Export trace log
  - Clear trace history
  - \[L.. -> L..\] Auto-collapse sequential steps
  - Diff: Mark jumps & multi-variable changes
  - Click a step: Time-travel snapshot rollback
  - \[<<\] step#: Current snapshot indicator
  - Restore real-time state
- Code Line:
  - Click line head: Toggle breakpoint
  - \>\> arrow: Live execution pointer
  - Aurora-green bar: Paused highlight & trail
  - 5 micro edit buttons (non-trace mode):
    - Inline edit this line
    - Insert blank line below
    - Paste code block (jumps re-mapped)
    - Reset line to original
    - Delete this line

### 3. Memory Bank & Cell

- Start/End index: Observation range
- Decimals (0-10): Precision slider
- Columns per row (1-16): Display columns slider
- In-place edit & save mode
- Warm-gold glow: Data-mutation flash
- World tracer line: Floating-cell world connection

### 4. Message Monitor

- Pause / resume capture
- Manual refresh
- Clear all logs
- 128-entry ring buffer (anti-spam)
- Copy / delete a single log

### 5. Global UX

- Pin to world building (scales with map)
- Toggle holographic / 1:1 UI scaling
- Collapse into 30px title capsule
- Close window safely
- Corner drag-resize with size memory
- Gear injection & spawn from config bar
- \[y\] Aurora-green button: Bottom-right reopen
- Dual-end: Built-in classes.dex, all-platform jar

## 功能介绍(中文)

- 逻辑监视器 — 逻辑与硬件实时交互监视器。
- 交流群: 615081546 (适配 v160.5 双端合一)

### 1. 主窗口 (yr2lm)

- add: 选取方块打开监视
- copy: 复制方块配置与代码
- paste: 粘贴配置至目标方块
- 方块列表:
  - 窗口显示/隐藏
  - 窗口手动刷新
  - 移除关闭窗口

### 2. 逻辑块

- 变量表:
  - 将 @counter 置零
  - 全局重置调试会话
  - 全局常驻实体引线
  - 切换变量表/编辑器
  - 搜索框: 过滤变量名
  - \[Cc\] 区分大小写开关
  - \[W\] 全词匹配开关
  - textBuffer: 打印缓冲区监控
- 编辑器:
  - 重新读取方块代码
  - 保存提交代码到方块
  - 切换单双栏并排
  - 暂停/继续执行
  - 单步推演一条指令
  - 运行至下一个断点
  - 智能流水面板开关
- 智能流水时序:
  - 导出时序追踪日志
  - 清空流水历史记录
  - \[L.. -> L..\] 顺序步自动折叠
  - Diff 差分: 跳转与多变量突变标记
  - 点击单步: 时间旅行快照历史回溯
  - \[<<\] 步号: 当前快照指示
  - 恢复实时运行状态
- 代码行:
  - 单击行首: 切换代码行断点
  - \>\> 箭头: 运行态实时执行指针
  - 极光绿条: 暂停态停驻高亮与光轨
  - 5 个微型编辑按键 (非流水模式):
    - 单行就地编辑
    - 向下插入空白代码行
    - 导入代码块(jump 变换)
    - 还原单行初始代码
    - 删除本行代码

### 3. 内存块 (Memory Bank 与 Cell)

- 起始/末尾下标: 观察范围输入
- 保留小数 (0-10): 精度调节滑条
- 每行个数 (1-16): 显示列数滑条
- 内存就地编辑与保存
- 暖金微光: 数值修改突变闪烁
- 世界引线: 悬浮格子显示世界连线

### 4. 信息板

- 暂停捕获记录
- 手动刷新消息
- 清空所有历史日志
- 128 条环形缓冲: 防爆内存循环记录
- 单条复制与删除

### 5. 通用交互

- 钉在世界建筑上随地图缩放
- 切换全息随镜缩放 / 1:1 UI
- 折叠为 30px 标题迷你胶囊
- 安全关闭监视窗口
- 右下角微型拖拽缩放并记忆尺寸
- 配置条追加齿轮,单击展开,拖拽唤出
- \[y\] 极光绿按钮: 右下角常驻唤出
- 双端合一: 内置 classes.dex 全平台通用
