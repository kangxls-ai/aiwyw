# MarkDo 标准应用介绍

核对日期：2026-11-04。资料基于仓库 main 的 0674d138ed442ee5e14380081bd7f365102254a7。README 含旧版本操作说明；涉及手势与配对时采用最新0.5.2/0.4.1说明，并核对当前源文件。

## GitHub 仓库简短描述（可粘贴 About）

中文：Windows 与 Android 本地优先待办工具，支持日历提醒、完成反馈、局域网加密多设备同步与 Markdown 导出。

English: Local-first to-dos for Windows and Android, with reminders, completion feedback, encrypted LAN sync and Markdown export.

建议应用名：MarkDo · 本地待办与局域网同步

列表摘要：在 Windows 与 Android 上记录待办、安排日历提醒、回顾完成反馈，通过局域网加密同步多台设备，并导出 Markdown。

建议标签：效率工具、待办清单、Windows、Android、局域网同步。

## 中文应用详情

## 把待办、行动与回顾放在一起



MarkDo 是一款面向 Windows 和 Android 的本地优先待办工具。随手记下事项，按需补充日期、时间和标签；任务完成后，可记录投入、评分与感想，方便回顾自己的计划与行动。


## 主要功能


- **快速记录：**只填写描述即可新增任务，也可补充开始与截止日期、时间及标签。
- **日历安排：**按月查看任务，在开始日、截止日显示待办计数，完成记录归入实际完成日。
- **完成与反馈：**点击圆圈或左滑快速完成；右滑填写耗时、五星评分和备注后完成，支持撤销完成及事后补充反馈。
- **时间提醒：**支持开始、截止相关提醒。实际到达受系统权限、通知设置和设备省电策略影响。
- **多设备同步：**电脑作为同步中心，支持多部手机配对。扫码或手动输入六位确认码，在可信局域网中完成连接；设备运行且连通时进行加密同步。
- **Markdown 导出：**按日期范围导出计划、完成情况、投入和感想，可复制文案或保存、分享 Markdown 文件。
- **轻量桌面体验：**Windows 支持托盘隐藏、恢复及可选开机启动；深浅主题在各设备独立保存。

## 适合哪些场景



个人工作待办、学习计划、日常事务与阶段回顾；适合希望在电脑和手机间同步任务、以本地保存为主的用户。


## 开始使用


- 安装对应的 Windows 或 Android 版本，先添加一条待办。
- 如需提醒，在系统设置中允许通知；Android 按应用提示配置精确闹钟权限。
- 如需同步，在电脑开启配对，手机扫描二维码或手动输入地址与确认码。
- 任务完成后快速勾选，或补充耗时、评分和备注，再按需要导出回顾。

## 使用说明



同步需要电脑与手机运行并处于可连通的局域网，电脑是同步中心；离线修改需重连后合并，不提供随时在线的云同步承诺。建议两端均升级至兼容版本。相机、通知、开机启动和省电策略的效果需以实际设备为准。更新 Android 安装包前请确认签名兼容及数据已妥善保留。


项目仓库：[MarkDo](https://github.com/kangxls-ai/markdo)

## English app description

## Plan, act and reflect



MarkDo is a local-first to-do app for Windows and Android. Capture a task with a description, optionally add dates, times and tags, then record effort, a five-star rating and notes when you finish.


## Features


- Quick task entry with optional start dates, deadlines and tags.
- A monthly calendar with pending-task counts on start and due dates.
- Tap the circle or swipe left to finish quickly; swipe right to finish with feedback. Undo completion or add feedback later.
- Start and deadline reminders, subject to system permissions and battery restrictions.
- Encrypted LAN synchronization between a Windows hub and multiple paired Android phones, using QR or manual pairing.
- Date-range Markdown export for plans, progress, effort and reflections.
- Windows tray controls, optional startup and device-specific light or dark themes.

## Getting started



Install the app, add a task and enable notification permissions if you want reminders. For synchronization, open pairing on your computer and connect your phone on a trusted local network.


## Requirements and limitations



Synchronization requires the computer and phones to be running and reachable on the local network. Offline changes merge after reconnection; this is not an always-online cloud service. Use compatible versions on all devices. Camera, notification and startup behavior depend on the device and operating system. Check Android signing compatibility and preserve your data before upgrading.


[Project repository](https://github.com/kangxls-ai/markdo)

## 上架资料核对

- 仓库清单版本为0.5.2+7；网站版本号应与实际上传的安装包一致，不因生成介绍就替换现有文件版本。
- 价格/积分、商业许可、下载包、真实截图及图标需按实际商品资料维护，文案不作推定。未见许可证文件，不宣称允许任意商用或免费授权。
- 支持平台据现有源码和构建目标为Windows、Android，不宣称支持iOS、macOS或云同步。
- 实际相机、通知及升级签名兼容需按设备验证，不宣称所有华为手机已经兼容。
- 本次仅编制文案，没有修改MarkDo远程仓库，也没有把它改造成平台激活客户端。
- 后台应用编辑页在公开地址为markdo时提供“填入MarkDo介绍”，经提示后填入双语名称、摘要、图文和建议标签，保留价格、版本、文件及截图；之后正常保存草稿和发布。

## 依据

[README](https://github.com/kangxls-ai/markdo/blob/0674d138ed442ee5e14380081bd7f365102254a7/README.md)、[版本及依赖](https://github.com/kangxls-ai/markdo/blob/0674d138ed442ee5e14380081bd7f365102254a7/pubspec.yaml)、[当前左右滑动实现](https://github.com/kangxls-ai/markdo/blob/0674d138ed442ee5e14380081bd7f365102254a7/lib/swipe_complete.dart)。
