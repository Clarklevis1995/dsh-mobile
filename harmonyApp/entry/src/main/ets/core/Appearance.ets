import { UiText, resolveUiMessage } from './UiMessages';
import { drawing } from '@kit.ArkGraphics2D';
import { AppearanceData } from './Models';

const EN: Map<string, string> = new Map([
  ['表格','Table'],['行','rows'],['旋转屏幕','Rotate screen'],['下载 CSV','Download CSV'],['无法旋转屏幕','Could not rotate screen'],
  ["需要你的回答","Your answer is needed"],
  ["网关已接收，等待会话继续…","Received. Waiting for the conversation\u2026"],
  ["取消回答","Cancel answers"],
  ["可选择多项，也可补充说明","Select multiple options or add details"],
  ["选择一项，或填写自己的答案","Choose an option or write your answer"],
  ["自定义答案 / 补充说明","Your answer / additional details"],
  ["输入你的回答","Enter your answer"],
  ["提交中…","Submitting\u2026"],
  ["提交回答","Submit answers"],
  ["Agent 正在等待审批","Agent is waiting for approval"],
  ["等待审批","Awaiting approval"],
  ["已恢复","Restored"],
  ["展开审批","Expand approval"],
  ["收起审批","Collapse approval"],
  ["收起参数","Hide arguments"],
  ["查看参数","View arguments"],
  ["网关未提供调用参数，请根据工具名称和原因确认。","The gateway did not provide arguments. Review the tool and reason."],
  ["已允许此次调用，等待会话继续…","Allowed once. Waiting for the conversation\u2026"],
  ["已拒绝，等待网关确认…","Rejected. Waiting for gateway confirmation\u2026"],
  ["拒绝","Reject"],
  ["允许一次","Allow once"],
  ['Agent 预设','Agent presets'],
  ["标准模式", "Standard mode"],
  ["PTC 模式", "PTC mode"],
  ["极简模式", "Minimal mode"],
  ["创造模式", "Creative mode"],
  ["当前默认", "Current default"],
  ["预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。", "Presets determine the tools, prompts and capabilities used by an Agent. Changes apply to new conversations."],
  ["正在读取 Agent 预设…", "Loading Agent presets…"],
  ["没有可用的 Agent 预设，请确认网关支持此能力并保持连接", "No Agent presets available. Check gateway support and your connection."],
  ["由 DeepSeek Harness 提供的 Agent 预设。", "An Agent preset provided by DeepSeek Harness."],
  ["功能完整的编码 Agent，支持文件编辑、Shell、检索、Skills、计划与工作流。", "A coding Agent with file editing, Shell, search, Skills, plans and workflows."],
  ["通过 Code Mode SDK 组合多步工具操作。", "Combines multiple tool operations through the Code Mode SDK."],
  ["精简工具集合，适合轻量、直接的编码任务。", "A focused tool set for lightweight coding tasks."],
  ["用于创建和维护自定义 Agent 预设。", "Creates and maintains custom Agent presets."],
  ["该预设存在配置错误，暂时不能设为默认值", "This preset has a configuration error and cannot be set as default."],
  ["服务端支持编写自定义预设，并提供预设配置文档。", "The host supports custom presets and provides configuration documentation."],
  ["服务端支持编写自定义 Agent 预设。", "The host supports custom Agent presets."],
  ["服务端提供 Agent 预设配置文档。", "The host provides Agent preset configuration documentation."],

  ['本地存储','Local storage'],['主机扩展','Host extensions'],['当前版本','Current version'],['新会话默认配置','New conversation defaults'],['未读取','Not loaded'],['默认模型','Default model'],['尚未配置主机','No host configured'],['Ping 网关','Ping gateway'],['网关已响应','Gateway responded'],['网关未响应，请检查连接','No gateway response. Check the connection.'],['与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。','Shares deployment settings with WebUI. Changes apply to new conversations; running conversations keep their startup configuration.'],
  ['描述你想要构建的内容','Describe what you want to build'],['工作区写入','Workspace write'],['完全访问','Full access'],['默认权限','Default permissions'],['请先在桌面端启用语音输入','Enable voice input on the desktop first'],['探索未至之境','Into the Unknown'],['模型','Model'],['权限','Permissions'],['上下文','Context'],['停止生成','Stop generation'],
  ['键盘','Keyboard'],['语音','Voice'],['按住说话','Hold to speak'],['松手取消','Release to cancel'],['松手转文字','Release to transcribe'],
  ['按住说话，松手转文字，上滑取消','Hold to speak, release to transcribe, swipe up to cancel'],
  ['正在转写，期间可以继续编辑草稿','Transcribing; you can keep editing your draft'],['正在结束录音','Finishing recording'],
  ['草稿已修改，识别文字保留在这里','Your draft changed; the transcript is kept here'],['插入草稿','Insert into draft'],['丢弃','Discard'],
  ['桌面端正在准备语音模型','Preparing the voice model on the host'],['准备语音模型','Prepare voice model'],
  ['语音服务尚未就绪，请在桌面端启用语音输入插件','Voice is not ready. Enable the voice input plugin on the host'],
  ['任务通知','Task notifications'],['任务已结束','Task ended'],['任务已完成','Task completed'],['有事项需要处理','Action required'],
  ['点击进入对应主机和会话查看','Tap to open the host and session'],['点击开关申请系统通知授权','Enable to request notification permission'],
  ['未识别到人声','No speech detected'],['系统通知暂不可用，请使用应用内待处理列表','System notifications are unavailable; use the inbox in the app'],
  ['通知未能显示，可在应用内查看任务与待处理事项','Notification could not be shown; check tasks and the inbox in the app'],
  ['通知对应会话暂不可用，请从会话列表重新选择','This session is unavailable; select it from the session list'],
  ['定时任务','Scheduled tasks'],['已结束','Ended'],['搜索定时任务','Search scheduled tasks'],['暂无定时任务','No scheduled tasks'],['每天','Daily'],['每周','Weekly'],['单次','Once'],['详情','Details'],['执行规则','Schedule rule'],['下次计划时间','Next scheduled time'],['所属会话','Session'],['最近投递','Last delivery'],['暂无投递记录','No delivery records'],['删除','Delete'],['删除定时任务？','Delete scheduled task?'],['编辑定时任务','Edit scheduled task'],['标题','Title'],['任务内容','Prompt'],['保留原规则','Keep current rule'],['修改执行时间','Change execution time'],['改为指定日期单次执行','Run once on a specific date'],['设备时区','Device time zone'],['请选择有效的未来时间','Choose a valid future time'],['任务状态待确认，请返回列表刷新','Task state unconfirmed. Return to the list and refresh.'],

  ['已连接会话','Attached sessions'],['未提供','Not provided'],['插件','Plugins'],['插件详情','Plugin details'],['组件详情','Component details'],['网关已断开，状态待确认','Gateway disconnected; state unconfirmed'],['已安装','Installed'],['暂无插件','No plugins'],['官方','Official'],['已开启','Enabled'],['已停用','Disabled'],['名称','Name'],['版本','Version'],['说明','Description'],['包含的组件','Components'],['标识','Identifier'],['模块','Module'],['状态','Status'],['配置项','Configuration'],['运行失败','Failed'],['卸载中','Unloading'],['待启动','Pending'],['空闲','Idle'],['关闭移动网关？','Disable mobile gateway?'],['关闭后本设备会断开连接，需要在主机上重新启用网关。','This disconnects this device. Re-enable the gateway on the host to reconnect.'],

  ['扫码配对','Scan to pair'],['正在扫码','Scanning'],['二维码内容无效','Invalid QR code'],['系统扫码不可用，请粘贴配对内容','Scanner unavailable. Paste the pairing payload instead.'],
  ['命令结果未确认','Command result not confirmed'],
  ['命令与技能','Commands and skills'],['关闭','Close'],['没有匹配命令','No matching commands'],
  ['预计上下文','Projected context'],['缓存读取 Token','Cache read tokens'],['缓存写入 Token','Cache write tokens'],['思考 Token','Reasoning tokens'],['首字耗时','Time to first token'],
  ['会话 Agent','Session agent'],['已锁定','Locked'],['刷新 Agent','Refresh agent'],['排队','Queue'],['插话','Steer'],['排队消息','Queued messages'],['个附件','attachments'],['重新连接','Reconnect'],
  ['会话设置','Session settings'],['默认配置','Defaults'],['模型与思考强度','Model and reasoning'],['主机未提供可选模型','No models offered by host'],['默认权限','Default permissions'],['会话权限','Session permissions'],['默认 Agent','Default agent'],['上下文与统计','Context and stats'],['上下文窗口','Context window'],['当前压力','Context pressure'],['输入 Token','Input tokens'],['输出 Token','Output tokens'],['轮次','Turns'],['步骤','Steps'],['模型耗时','Model time'],['工具耗时','Tool time'],
  ['轨迹详情','Timeline details'],['对话','Chat'],['轨迹','Timeline'],['全部','All'],['工具','Tools'],['模型请求','Model requests'],['暂无轨迹记录','No timeline records'],['实时更新','Live'],['代码','Code'],['复制','Copy'],['复制代码','Copy code'],['复制表格','Copy table'],['已复制','Copied'],['复制失败','Copy failed'],['选择文字','Select text'],['消息操作','Message actions'],['预览表格','Preview table'],['全选','Select all'],['文件副本已失效，请重新下载','The local copy expired. Download it again.'],['已保存','Saved'],['文件保存失败','Could not save file'],['内容解析失败','Could not parse content'],['展开完整代码','Show full code'],['收起代码','Collapse code'],['回到底部','Jump to latest'],['有新消息 · 回到底部','New messages · Jump to latest'],
  ['关闭会话侧栏','Close sessions'],['Deepseek Harness 预览版','DeepSeek Harness Preview'],['前往','Go'],['设置','Settings'],['返回','Back'],['主机','Hosts'],['选择主机','Choose host'],['未连接','Not connected'],['已连接','Connected'],
  ['连接中','Connecting'],['工作区','Workspace'],['未分组','Ungrouped'],['全部会话','All sessions'],['最近会话','Recent sessions'],['新建会话','New session'],
  ['字体大小','Font size'],['跟随系统','Follow system'],['标准','Standard'],['恢复默认','Reset to default'],['搜索会话内容','Search session content'],['搜索会话','Search sessions'],['暂无会话','No sessions yet'],['连接主机后开始聊天','Connect a host to get started'],['刷新','Refresh'],['运行中','Running'],
  ['主机管理','Manage hosts'],['添加主机','Add host'],['认证','Authenticate'],['编辑','Edit'],['移除','Remove'],['取消','Cancel'],['保存','Save'],['连接','Connect'],['断开连接','Disconnect'],
  ['主机名称','Host name'],['别名','Alias'],['候选地址（每行一个）','Candidate addresses (one per line)'],['服务器','Server'],['删除全部主机','Remove all hosts'],
  ['外观','Appearance'],['界面','Interface'],['系统','System'],['浅色','Light'],['深色','Dark'],['语言','Language'],['字号','Font size'],['跟随系统字号','Follow system font size'],['跟随系统字体大小','Follow system font size'],
  ['减少动态效果','Reduce motion'],['隐私','Privacy'],['关于','About'],['存储与隐私','Storage and privacy'],['应用版本','App version'],['构建版本','Build'],
  ['新建工作区','New workspace'],['选择目录','Choose directory'],['使用此目录','Use this directory'],['上一级','Parent directory'],['加载中','Loading'],['重试','Retry'],
  ['重命名','Rename'],['归档','Archive'],['归档会话','Archive session'],['会话名称','Session title'],['搜索结果为空','No results'],['正在搜索','Searching'],
  ['发送','Send'],['停止','Stop'],['输入消息','Message'],['更早消息','Earlier messages'],['待处理','Pending'],['会话','Sessions'],['收起','Close'],
  ['配对内容','Pairing payload'],['网关地址','Gateway URL'],['一次性配对码','One-time pairing code'],['粘贴配对内容，或填写地址及配对码','Paste a pairing payload, or enter the URL and code'],
  ['当前主机','Current host'],['已保存主机','Saved hosts'],['无已保存主机','No saved hosts'],['确认移除该主机？','Remove this host?'],['仅移除此设备上的资料和凭据','Removes this device’s saved profile and credentials'],
  ['确认归档此会话？','Archive this session?'],['此操作会在主机上归档会话','This archives the session on the host'],['正在连接主机','Connecting to host'],['尚未连接','Not connected'],['已断开','Disconnected'],
  ['历史加载中','Loading history'],['消息发送中','Sending'],['授权配对','Pair host'],['错误','Error']
]);

@Observed
export class Appearance {
  data: AppearanceData = new AppearanceData();
  systemDark: boolean = false;
  systemEnglish: boolean = false;
  systemFontScale: number = 1;
  get english(): boolean { return this.data.language === 'en' || (this.data.language === 'system' && this.systemEnglish); }
  get dark(): boolean { return this.data.style === 'dark' || (this.data.style === 'system' && this.systemDark); }
  get bg(): string { return this.dark ? '#090D13' : '#F9FAFC'; }
  get settingsBg(): string { return this.dark ? '#0D1118' : '#F1F1F6'; }
  get surface(): string { return this.dark ? '#111720' : '#FFFFFF'; }
  get fg(): string { return this.dark ? '#F1F4FA' : '#0E131A'; }
  get muted(): string { return this.dark ? '#98A8BD' : '#858990'; }
  get line(): string { return this.dark ? '#26364D' : '#E9EBEF'; }
  get accent(): string { return this.dark ? '#7EA8FF' : '#2E6BE6'; }
  font(size: number): number | string { return this.data.fontFollowsSystem ? size : (size * this.data.fontScale).toString() + 'vp'; }
  t(value: UiText = ''): string { if (typeof value !== 'string') { return resolveUiMessage(value, this.english); } return this.english ? EN.get(value) ?? value : value; }
  option(value: string): string { return this.t(value === 'system' ? '系统' : value === 'light' ? '浅色' : value === 'dark' ? '深色' : value); }
}

/** SRC_IN 同时着色填充和描边，保持 Android 单色矢量图标的语义。 */
export function iconTint(color: string): drawing.ColorFilter {
  const rgb = parseInt(color.substring(1), 16);
  return drawing.ColorFilter.createBlendModeColorFilter({ alpha: color.length === 9 ? (rgb >>> 24) & 255 : 255, red: (rgb >> 16) & 255, green: (rgb >> 8) & 255, blue: rgb & 255 }, drawing.BlendMode.SRC_IN);
}
