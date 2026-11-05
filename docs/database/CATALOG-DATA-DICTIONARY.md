# 商品与栏目JSON数据字典

数据库字段级COMMENT见IMPLEMENTED-SCHEMA.sql；本文解释JSON内部字段，供维护者阅读。实际类型和校验由Catalog.java与web/app/types/catalog.ts维护，修改模型必须同步迁移和接口，不能自行加键假设生效。

## ProductInput：draft_json / private_json / snapshot中的content

| 字段 | 含义与约束 |
| --- | --- |
| slug | 小写字母、数字和连字符，最长80；首次发布后不可变，公开地址为/apps/slug |
| nameZh / nameEn | 中文/英文应用名称，最长100；中文必填；英文未填可回退中文 |
| summaryZh / summaryEn | 中英文摘要，最多500；发布时中文摘要必填 |
| bodyZh / bodyEn | 中英文富文本，最多10万字符；白名单标签净化，禁止事件、脚本、style和iframe；发布时中文正文必填 |
| tags | 唯一标签列表，最多20，每个最多80；标签独立于首页栏目 |
| iconUrl | 图标地址，可空；仅站内路径或HTTPS，不允许协议相对、userinfo或控制字符 |
| screenshots | 最多12张截图地址，同样校验地址；公开目录不携带数组，静态详情包含 |
| type | SOFTWARE软件、TOOL工具、API接口、DOCUMENT文档、RESOURCE资源 |
| pricePoints | 非负积分价格，最多两位小数；不是人民币金额，也不是可由浏览器直接扣减的余额 |
| version | 真实版本标识，最多60；发布时必填，不从版本字符串臆测历史授权范围 |
| deliveryMode | DOWNLOAD文件、EXTERNAL外链、LICENSE激活、API服务；目前正式接通免费链接交付，其他流程分阶段接入 |
| downloadUrl | 私有交付地址；草稿/private_json保存，公开snapshot恒为空；公开目录和静态HTML不得输出 |
| revision | 提交保存时使用当前商品revision；JSON内历史值不作为数据库事实，必须读catalog_product.revision |

保存应用不会发布；发布时在同一事务中生成新的不可变快照并切指针。公开Product还包含id、slug、status、publicationId、firstPublishedAt和聚合views/downloads。商品目录接口不包含全文和私有交付地址，减少首页响应体。

## SectionInput：home_section.config_json

| 字段 | 含义与约束 |
| --- | --- |
| titleZh / titleEn | 中文名称最长40、英文最长80；中文必填，改名不改变栏目id及更多链接 |
| subtitleZh / subtitleEn | 中英文说明，分别最多100/160 |
| icon | 预定义站点图标名，不接受自定义HTML/脚本 |
| enabled | 是否显示；停用仍占6个名额，删除释放名额但不删商品 |
| position | 0—1000，越小越靠前，相同值按稳定ID排序 |
| mode | MANUAL人工选品，TAG按标签，AUTO自动选品 |
| sort | LATEST首次上架倒序、VIEWS浏览量倒序、DOWNLOADS有效交付倒序；人工栏目按productIds顺序，不按sort重新打乱 |
| days | 热门统计窗口：7、30或0累计；默认30，按北京时间日期包含当天 |
| limit | 首页展示1—60条，默认20；更多页基于完整选品结果分页，每页9条 |
| tags | TAG模式匹配任一所选标签；未配置匹配标签时结果为空，不擅自展示全站商品 |
| productIds | 人工选品有序UUID列表，最多300；草稿选择可保留，但仅公开PUBLISHED商品 |
| revision | 提交时使用home_section.revision；服务端乐观锁拒绝旧表单覆盖 |

总数上限包含停用栏目，通过catalog_guard行锁与事务内计数保证；不能把“先在浏览器查数量再新增”当作并发约束。初始化只在guard.revision=0时建立默认四栏目；用户删除栏目后重启不会悄悄重新创建。

## 审计与异常

catalog_audit.detail只允许动作摘要和发布UUID，不写下载签名、配置密钥或完整富文本。草稿/发布状态检查、revision和文件摘要校验任一失败都必须停止该操作；不要手动删除历史记录来规避冲突。发布文件与数据库的灾难恢复步骤见MAINTENANCE.md。


## 存储与分页补充

catalog_product.last_published_at记录最近一次成功发布时刻；first_published_at仍用于首次上架的排序语义，回滚不改变首次时间。search_text与catalog_product_tag在保存草稿同一事务内更新，供后台查询使用，不能当作公开发布资料。V003迁移在catalog_guard锁内回填，异常回滚且不写迁移成功标记。

storage_reference的owner_type=DRAFT时owner_id为商品UUID，PUBLICATION时为不可变发布UUID。草稿同步仅移除该草稿的旧引用，绝不移除历史发布引用。写引用与发布指针在同一业务事务，锁顺序为catalog_guard后按UUID排序锁资源；删除只锁资源，避免反向锁导致死锁。任何READY以外资源不可用于新引用。

storage_profile.config_enc为包含物理位置及凭据的加密快照，fingerprint仅用于连接复用；不得把解密明文粘到数据库或公开接口。storage_download_ticket只存随机凭据摘要，外部不能拿asset_id直接签发。详细状态与恢复方式见../STORAGE-AND-EDITING.md及IMPLEMENTED-SCHEMA.sql。
