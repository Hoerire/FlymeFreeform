<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_mark.png" width="112" alt="Flyme 小窗" />
</p>

<h1 align="center">Flyme 小窗（FlymeFreeform）</h1>

<p align="center">
  <img src="https://img.shields.io/badge/ROM-ColorOS-00A862" alt="面向 ColorOS" />
  <img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android" alt="最低 Android 16" />
  <img src="https://img.shields.io/badge/libxposed-API_102-4285F4" alt="libxposed API 102" />
  <img src="https://img.shields.io/badge/version-1.3.6-blue" alt="版本 1.3.6" />
</p>

把魅族的 Flyme 小窗交互搬到 ColorOS：从屏幕底角斜向内上滑呼出扇形菜单，滑到目标应用松手，当即以自由浮窗打开；点击浮窗外侧即可收起。

模块以 Xposed 模块形式运行，不安装常驻服务，不接管窗口管理，只补齐入口与开关。多任务、分屏、窗口堆叠等行为保持系统原生。

## 预览

<p align="center">
  <img src="docs/images/preview.gif" width="360" alt="小窗交互预览" />
</p>

## 功能

设置分为三组：扇形相关、小窗交互与生效范围。以下按分组说明。

<p align="center">
  <img src="docs/images/home.jpg" width="330" alt="模块首页：三组入口" />
</p>

### 扇形相关

呼出扇形菜单的方式，以及手指与图标的判定范围。

- **触发位置**：左右下角、仅左下角、仅右下角三档
- **保持挂起**：松手后菜单不收起，可以继续滑动点选应用
- **触发范围**：底角手势的识别区域大小，24–160 dp
- **命中范围**：手指与图标的判定半径，40–140 dp，默认 100 dp
- **数量上限**：扇形中同时显示的条目数，1–6；「更多」入口不占位

<p align="center">
  <img src="docs/images/trigger-range.jpg" width="330" alt="触发范围设置" />
</p>

<p align="center">
  <img src="docs/images/hit-range-preview.jpg" width="330" alt="拖动命中范围时的实时预览" />
</p>

拖动命中范围滑条时，屏幕上会按当前数值画出每一格的判定圈，松手即保存。

### 扇形应用

菜单由模块自行绘制，条目与顺序独立于智能侧边栏。

- **混合固定**：应用、分身应用、侧边栏工具与快捷入口可加入同一份列表
- **拖动排序**：长按拖动调整顺序，最多六项
- **滑动选中**：滑到某一格时该图标放大至 1.2 倍作为反馈，判定依据是手指与图标圆心的距离，松手即启动

<p align="center">
  <img src="docs/images/radial-apps.jpg" width="330" alt="扇形应用页" />
</p>

### 「更多」面板

扇形菜单末尾的「更多」用于打开完整列表，内容取自智能侧边栏的「全部」面板。

- **两组内容**：「收藏」沿用智能侧边栏的设置与排序，「最近」列出近期打开过的应用
- **搜索**：面板顶部可按名称搜索工具、快捷入口与应用
- **位置**：固定在扇形末尾，不占用条目上限

<p align="center">
  <img src="docs/images/more-panel.jpg" width="330" alt="更多面板：收藏与最近" />
</p>

### 小窗交互

决定应用以何种方式打开、同时存在多少个浮窗，以及打开后的交互方式。

- **窗外关闭**：点击小窗外部区域时可选不执行、单击关闭或双击关闭
- **小窗数量**：将系统允许同时存在的自由浮窗个数提升至指定值，1–5
- **解除限制**：原本不支持自由浮窗的应用也能以小窗打开
- **小窗跳转**：通知中心、通知横幅、控制中心磁贴、面板工具、应用跳转、桌面快捷项与负一屏卡片均支持以自由浮窗打开；文件选择、授权、卸载确认与分享面板保持全屏。该功能受系统版本差异影响，部分应用表现可能不同，遇到异常时把该应用加入排除列表即可
- **快速上滑**：在普通小窗底部横条上快速上滑，切换为迷你小窗

<p align="center">
  <img src="docs/images/settings-zoom.jpg" width="330" alt="小窗交互设置页" />
</p>

### 生效范围

决定模块在什么情况下暂停。三项都只暂停扇形呼出，小窗相关能力不受影响。

- **排除列表**：列表内的应用在前台时停止全部修改，离开后自动恢复
- **横屏模式**：横屏时暂停扇形呼出，恢复竖屏后自动启用
- **游戏模式**：系统进入游戏模式时暂停扇形呼出，退出后自动启用

<p align="center">
  <img src="docs/images/settings-scope.jpg" width="330" alt="生效范围设置页" />
</p>

### 其他

- **锁屏与息屏停用**：设备锁屏或熄屏时停止一切响应，解锁后恢复
- **详细日志**：按需开启，用于反馈问题时导出诊断信息；关闭时不产生任何诊断开销

## 安装

1. 在设备上安装并启用 **LSPosed**（需支持 libxposed API 102）
2. 安装本模块，在 LSPosed 中启用，并勾选四项作用域：**系统框架**、**系统界面**、**桌面**、**智能侧边栏**
3. 重启上述进程或重启设备
4. 打开「Flyme 小窗」，确认框架服务已连接，再按需调整设置

模块首页会显示框架连接状态与作用域授权状态，缺少作用域时可直接在页内请求授权。

## 兼容性

| 项 | 要求 |
|---|---|
| 系统 | ColorOS 16 与 17（Android 16 及以上）；其他版本尚未测试 |
| 框架 | LSPosed，libxposed API 102 及以上 |
| 导航方式 | 手势导航（三键导航下底角区域与返回键冲突，模块保持关闭） |
| 侧边栏 | 「更多」面板依赖智能侧边栏，未开启时该面板不可用 |

## 工作原理

扇形菜单由模块自绘于独立悬浮层，仅在呼出期间存在；「更多」面板复用智能侧边栏的「全部」面板及其数据；小窗本身仍由系统自由浮窗服务创建。模块在系统框架、系统界面与智能侧边栏进程内改写既有入口，不新增常驻组件，因此不会改变系统原有的窗口与多任务逻辑。

## 已知限制

- 依赖厂商私有的自由浮窗与智能侧边栏接口，仅适用于 ColorOS 16 与 17；其他版本尚未测试
- 通知与控制中心的浮窗启动需要系统对应的入口存在
- 小布识屏与屏幕翻译固定到扇形菜单后，受系统版本差异影响无法直接拉起；需要这两项功能时，从「更多」面板中打开可正常使用

## 致谢

- [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform)：本项目的上游来源，原作者 Mangi-11
- [Flyme](https://www.flyme.com/)：小窗交互的设计参考
- [libxposed API](https://github.com/libxposed/api)：现代 Xposed API
- [Miuix](https://github.com/compose-miuix-ui/miuix)：UI 组件库

## 许可证

本项目基于 GPL-3.0 开源，详情请参阅 [LICENSE](LICENSE)。