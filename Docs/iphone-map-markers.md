# iPhone 地点标记

在现有地图连接器中增加 Whaleness 自有的命名地点标记，可通过 Agent 保存、查询、修改、删除，并将返回的坐标用于路线规划或在苹果地图显示落点。记录保存在来源手机，按主机分别保存；不会自动写入苹果地图的个人收藏。本次未新增本地标记管理页面，沿用对话中的 Agent 工具入口。

## 使用方式

- “把这里标记为停车位，备注 B2 电梯旁”：先查询来源手机位置，再保存明确的坐标、名称和备注。需要精准停车位时优先取精确位置，不猜测坐标。
- “把这个酒店地址保存为出差酒店”：先解析地址，多义地点需要明确目标，再保存坐标。
- “找一下我的停车位”“导航到停车位”：按名称查询标记，使用返回的坐标计算路线或打开苹果地图。
- “把停车位改名为周末停车位”“删除旧停车位”：使用已查询的 markerId 更新/删除，不能仅凭同名任意选择。

临时显示落点仍可直接调用 maps.open；持久保存需要明确调用标记创建工具。同名标记允许并存，不自动覆盖。

## 工具

四个工具属于 location 权限模块，新版手机需在 tools 中明确声明，旧版手机不会收到新操作；App 与网关需同时更新。

| 快捷工具 | 原生工具 | 参数 |
| --- | --- | --- |
| phone_maps_marker_create | maps.markers.create | operationId、name、latitude、longitude 必填；address、notes 可选 |
| phone_maps_markers_list | maps.markers.list | query、offset、limit 可选，默认 offset=0、limit=20 |
| phone_maps_marker_update | maps.markers.update | operationId、markerId 必填；按需提供 name、成对经纬度、address、notes、expectedRevision |
| phone_maps_marker_delete | maps.markers.delete | operationId、markerId 必填；expectedRevision 可选 |

name 最多 200、address 最多 400、notes 最多 1000 个 UTF-16 代码单元；不接受 NUL。地址和备注可用空字符串清除，名称不能为空。更新至少包含一个实际可修改字段，坐标必须同时给出。列表每页最多 20 条，避免长备注超过传输限额；query 按名称/地址/备注作字面包含匹配，百分号和下划线不会变成 SQL 通配符。返回 total、hasMore、nextOffset，按更新时间倒序并以 ID 稳定排序。

标记包含 UUID、名称、经纬度、地址、备注、revision、createdAt、updatedAt。更新只合并给出的字段。expectedRevision 可检测读取后发生的其他修改，冲突返回 marker_revision_conflict，不能覆盖后继续谎报成功。修改不存在的 ID 返回 marker_not_found；删除不存在的 ID 返回 existed=false。

## 保存、重试与权限

创建/更新/删除需要 operationId UUID。同一用户操作重试须保持 operationId 及参数不变。标记变化和成功回执存于同一个 SQLite 事务，不能出现标记保存但回执没有提交的半次写入。相同操作返回原回执且 replayed=true，不重复创建，不把旧修改重放到已删除标记上；改变参数却复用操作 ID 返回 operation_conflict。历史回执不是当前状态，最新状态应查询列表。删除将标记从活动列表移除；用于防重放的私有操作回执仍保留历史结果，不应把删除描述为清除了此前主机会话或所有本地历史。

数据库位于 App 私有 Application Support/phone-map-markers，文件名为主机 ID 摘要，目录仅当前 App 可访问并使用文件保护。回执按主机、会话、工具和操作 ID 隔离。无固定标记数量上限，列表分页返回；不上传整个数据库，不同步到其他主机。重连/重启 App 保留记录，App 数据删除或卸载的行为由系统决定。

连接器仍要求手机/网关开关开启、真实来源设备绑定及前台调用；保存用户明确提供的坐标不额外读取 GPS。关闭权限禁止后续远程调用，已经保存的标记保留，只有明确删除操作才移除。关闭某主机的连接器不会让其他主机读取其标记。新建标记不会自行打开地图，地图跳转仍由独立的明确指令触发。

## 验证

已验证跨实例持久保存、重复调用、参数冲突、主机隔离、同名标记、字面搜索、分页、部分更新、修改版本冲突、删除及历史回执不恢复记录。用 SQLite 触发器模拟回执写入失败，验证整次事务回滚且相同操作可重试；存储不可用不会假报成功或空列表。

测试使用临时数据库与虚构坐标，不操作用户真实标记或苹果地图收藏。网关覆盖白名单、位置授权、旧版设备、撤权时不明写入结果及真实 WebSocket 请求/回包。尚需真机验收“取位置 → 保存 → 重启后查询 → 在苹果地图打开”的完整流程。
