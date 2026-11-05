# 第二阶段维护补充

新增账户、钱包、核销、整单退款与通知维护规则见[PHASE-2.md](PHASE-2.md)，结构见[COMMERCE-SCHEMA.sql](database/COMMERCE-SCHEMA.sql)。金额与积分最小单位不同，模拟命名空间不可改成真实数据；严禁直接改余额和删除分摊修复账目。MySQL锁顺序与持久发信箱也在该手册说明。

下方基础、目录和存储维护规则继续有效。

---

# AI万有网 人工排查与恢复手册

适用范围：已实现的基础身份/配置、商品目录、首页栏目、静态发布、存储和多应用编辑。存储恢复细节见[STORAGE-AND-EDITING.md](STORAGE-AND-EDITING.md)。交易钱包、API计费等未完成业务不能按本手册猜测表结构或人工补账。本手册配合 `database/IMPLEMENTED-SCHEMA.sql`、源码中文注释及 `DEVELOPMENT.md` 阅读。

## 1 先识别资料，避免恢复到错误的库

- 开发启动默认HTTP 3000、后端18080。其他服务的8080不属于本项目，不能一并停止。
- `backend/data/` 是默认本地运行资料，不是源码。`master.key` 是32字节根密钥，`bootstrap.enc` 是加密数据库连接；二者必须与对应数据库成套保留。H2开发库为 `local.mv.db`，不是生产MySQL。
- `publications/商品UUID/发布UUID.html` 是正式生成的静态详情文件。它与数据库中的发布快照配对；只备份数据库而不备份该目录不能完整恢复。
- `payment-images/` 包含付款码。除选定的模拟图片外，未开放真实付款码仅管理员可读。存储目录禁止直接映射成公开网站目录。
- `install.token` 仅未安装时存在，安装后删除。不要通过删除 `bootstrap.enc` 或改为新库来修复登录问题。
- 生产域名、邮件、渠道、积分及存储等运营参数从后台配置；本文没有要求运营者手改运行配置文件。

## 2 表与关系

实际MySQL建表/字段说明见 `database/IMPLEMENTED-SCHEMA.sql`；运行时由 `Database.schema`、`CatalogSchema`、`SchemaComments`、`StorageSchema`和`CatalogQuerySchema` 执行幂等建表与注释迁移。P1旧表会补中文COMMENT，不删除数据。`schema_migration` 登记已完成注释迁移。MySQL DDL隐式提交，升级前必须先完成成套备份。

| 表 | 主键/关系 | 排查用途 |
| --- | --- | --- |
| app_user | id；email唯一 | 密码摘要、角色、加密TOTP、最近已使用验证码步长 |
| app_session | token_hash → user_id | 会话有效期和本次MFA状态；只存令牌摘要 |
| system_config | config_key | 配置值、版本和更新时间；secret类型值为密文 |
| config_audit | id → user_id | 配置键、动作及版本，无敏感旧值/新值 |
| catalog_guard | 唯一id=1 | 栏目和发布写入锁、目录版本；不是缓存表，不能删 |
| catalog_product | id；slug唯一 | 待发布草稿、revision、status、当前发布指针 |
| catalog_publication | id → product_id | 不可变公开/私有快照、静态文件名、SHA256、操作者 |
| home_section | id稳定 | 栏目配置JSON与revision；含停用总数最多6 |
| catalog_metric | 商品+日期+访客摘要+类型 | 北京时间按日去重；VIEW浏览，DOWNLOAD成功发放交付 |
| catalog_audit | id → actor_id、entity_id | 商品/栏目操作与发布版本摘要，不含私有交付链接 |
| storage_profile | id；fingerprint唯一 | AES-GCM加密连接快照，切换模式不影响旧文件位置 |
| storage_asset | id → profile_id | 文件元数据、SHA256、状态，不以原始文件名存放 |
| storage_reference | 资源+所属类型+所属ID | 草稿/历史发布引用保护，禁止手工删除绕过 |
| storage_download_ticket | token_hash → asset_id | 短期授权摘要和过期时间，无令牌明文 |
| storage_audit | id → actor_id、entity_id | 上传、删除、失败重试和凭据同步结果 |
| catalog_product_tag | 商品+标签 | 当前草稿查询索引，与保存商品同事务更新 |

JSON不是任意扩展字段。类型定义分别为 `Catalog.ProductInput` 和 `Catalog.SectionInput`。`draft_json` 包含私有交付地址，禁止直接复制到公开接口；`snapshot_json` 是净化的公开字段，`private_json` 保留发布当时交付资料。首页接口进一步裁剪富文本/截图，详情从静态文件读取。

## 3 状态、版本与写入顺序

商品初始 `DRAFT`；保存只增加revision，不改变公开快照。发布后为 `PUBLISHED`；下架为 `UNLISTED`，保留公开介绍及历史发布。已经发布的slug不能修改。`first_published_at` 是首次上架时间，修改介绍或重发不将旧应用伪装成新上架。

发布按如下顺序执行：

1. 数据库事务锁住 `catalog_guard.id=1`，核对客户端revision。
2. 再校验并净化富文本，生成新的HTML到新的发布UUID路径，不覆盖旧文件。
3. 写入不可变 `catalog_publication`，保存SHA256与公开/私有快照。
4. 更新 `catalog_product.active_publication`、状态和revision，并写入审计。
5. 提交成功后失效当前进程的公开缓存；读者才看到新版本。

生成失败或数据库回滚不改变旧指针。回滚后遗留的新文件是孤立文件，不会自动公开。恢复版本先检查文件存在且摘要吻合，再切指针；不覆盖当前草稿，也不自动重新上架。读取当前文件也会核验摘要，损坏返回503而不输出错误内容。

公开目录缓存最多30秒刷新统计；本进程的商品/栏目修改提交后立即失效。若将来扩展多实例，需要共享发布存储与配置/缓存失效消息；当前不能把多个独立本地目录当成一致的发布集群。静态详情不会在浏览器请求商品查询接口，浏览上报与点击下载后的授权请求仍是动态操作。

## 4 常见问题

| 现象 | 优先检查 | 处理 |
| --- | --- | --- |
| 首页没有markdo | 商品仍是草稿或没有人工加入推荐 | 补齐真实资料后发布；推荐栏目选中该应用。初始化只有名称，不编造售价/文件 |
| 草稿已改但前台未变 | 是否点击发布；当前发布ID是否改变 | 保存后显式发布，或在发布记录恢复旧版 |
| 发布提示文件失败 | 数据目录空间、服务账户权限、路径是否被误建为文件 | 修复磁盘/权限后重试，旧指针无需改动 |
| 页面503完整性失败 | 发布文件缺失或SHA256不匹配 | 从同版本备份恢复文件，或从发布记录切换已验证的历史版本 |
| 第7栏目保存失败 | 包含停用栏目是否已有6个 | 编辑现有栏目，或删除不需要栏目；不删除商品 |
| 显示版本冲突 | 表单revision与数据库不同 | 先保留未保存内容，重载后合并，不能强行覆盖版本号 |
| 下载数未增加 | 是否成功获得交付链接，同访客当天是否已计数 | 按每天去重，统计缓存最多30秒；二维码弹窗、失败申请不计下载 |
| 付款码上传后不能真实收款 | `payment.mode`仍为SIMULATED | 这是当前边界；真实资金验收完成前不能改库绕开模式校验 |
| 配置密文无法解密 | master.key和数据库是否来自同一备份 | 恢复配套密钥；不要填新随机key或将密文改空来掩盖错误 |
| 管理员被要求再次绑定MFA | 账户是否未完成绑定、会话是否旧/已撤销 | 使用原验证器登录或按下节离线恢复；不得把所有会话设成已验证 |

## 5 极端情况下的人工干预

常规操作优先用后台和已鉴权接口，因为它们包含并发校验、净化、文件校验、审计和缓存失效。界面不可用时，先记录故障时间/现象、数据库与文件备份版本，停止相关写流量，再由有权限的维护者干预；干预后执行对应检查并保留工单。

可先执行以下只读排查（替换占位参数，不将私有JSON或凭据复制到聊天/工单）：

```sql
SELECT id, slug, status, revision, active_publication, first_published_at, updated_at
FROM catalog_product WHERE slug = :slug;

SELECT id, product_id, file_name, sha256, actor_id, created_at
FROM catalog_publication WHERE product_id = :product_id ORDER BY created_at DESC;

SELECT id, actor_id, entity_id, action_name, detail, created_at
FROM catalog_audit WHERE entity_id = :product_id ORDER BY created_at DESC;

SELECT config_key, revision, updated_at FROM system_config ORDER BY config_key;
SELECT id, email, role_name, mfa_enabled, last_totp_step FROM app_user WHERE email = :email;
SELECT user_id, expires_at, mfa_verified FROM app_session WHERE user_id = :user_id;
```

冒号参数为维护工具绑定参数，不是要求直接复制整段到命令行执行。导出数据前确认不包含 `draft_json`、`private_json`、密码摘要、TOTP或配置值。

必须手动恢复发布指针时：先验证发布记录确实属于该商品，核对文件实际SHA256；在一个事务里先锁guard，再按预期revision修改一个商品的指针并递增revision，保留原status/first_published_at；添加对应人工恢复审计。受影响行数必须为1，否则回滚调查。提交后重启相应应用缓存或等待刷新，并验证公开详情、搜索及旧权益。不能通过修改静态HTML冒充发布，也不能删除旧快照/权益来“对齐”数据。

MFA丢失时优先恢复验证器安全备份。确需离线重绑时，由站点所有者在维护窗口核验身份并保留证据，停止全部应用实例、成套备份；仅对目标管理员清除加密TOTP、将mfa_enabled设false、last_totp_step归-1，撤销该用户全部会话。启动后必须重新登录并绑定MFA，服务器仍应禁止其在绑定前进入配置。此操作不修改密码/角色，不批量关闭MFA，不直接把会话标为mfa_verified。操作人、目标用户、时间和原因须进入维护记录；当前没有开放远程绕过MFA的恢复接口。

配置人工修复不得把敏感值当明文写库。恢复已验证的密文必须搭配原master.key；非敏感值也需按Settings定义检查类型/范围、递增revision并写审计。不要静默重写历史审计。交易与权益后续接入时，资金纠错必须采用补偿流水，禁止直接改余额。

## 6 恢复后核对

安装入口仍锁定；原用户与MFA策略有效；敏感接口无越权；目录只展示已发布应用；栏目不超6；静态文件摘要吻合；草稿与公开内容隔离；免费交付正常且不公开私有URL；旧发布可查看；配置和目录操作审计可追溯。真实收款继续关闭，直到独立验收及批准启用。

已完成独立MySQL8.4建表与目录联测；发布前仍需完成实际生产环境、SMTP/存储、完整交易/API/客服、负载及备份恢复联测。当前开发测试通过不能替代这些上线证据。
