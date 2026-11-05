-- AI万有网 已实现表结构（MySQL）；由SchemaComments/CatalogSchema生成。

-- 仅供结构审查与灾难恢复参考；日常由安装/迁移自动建表，不要对在线库重复初始化。

CREATE TABLE IF NOT EXISTS app_user (
 nickname VARCHAR(40) NOT NULL DEFAULT '' COMMENT '可修改昵称；初始随机字母数字，不作为身份凭据',
  email_verified_at BIGINT COMMENT '邮箱验证成功UTC毫秒，NULL未验证',
  disabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '账户停用且既有会话失效',
  business_email BOOLEAN NOT NULL DEFAULT FALSE COMMENT '业务邮件用户主动开启，验证找回不受影响',
  id VARCHAR(36) NOT NULL COMMENT '账户UUID，不能用邮箱关联历史业务',
  email VARCHAR(254) NOT NULL COMMENT '规范化小写邮箱，唯一索引防重复账户',
  password_hash VARCHAR(512) NOT NULL COMMENT 'PBKDF2迭代参数、随机盐和摘要，非密码明文',
  role_name VARCHAR(30) NOT NULL COMMENT 'ADMIN管理员、OPERATOR商品运营、FINANCE财务、SUPPORT客服、USER用户',
  totp_secret TEXT NULL COMMENT 'AES-GCM加密的TOTP密钥，依赖master.key解密',
  mfa_enabled BOOLEAN NOT NULL COMMENT '是否完成MFA绑定，不等于本次会话已经验证',
  last_totp_step BIGINT NOT NULL DEFAULT -1 COMMENT '最近已使用30秒步长；原子比较更新拒绝验证码重放',
  created_at BIGINT NOT NULL COMMENT '创建时间UTC毫秒',
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_email (email)
) COMMENT='账户及后台角色；MFA密钥加密，密码仅保存不可逆摘要';

CREATE TABLE IF NOT EXISTS app_session (
  token_hash VARCHAR(64) NOT NULL COMMENT 'Cookie随机令牌的SHA256摘要；禁止存令牌原文',
  user_id VARCHAR(36) NOT NULL COMMENT '关联app_user.id',
  expires_at BIGINT NOT NULL COMMENT '会话过期UTC毫秒',
  mfa_verified BOOLEAN NOT NULL COMMENT '本次会话是否完成第二因素验证',
  PRIMARY KEY (token_hash)
) COMMENT='服务端可撤销会话；过期和退出均不得继续访问';

CREATE TABLE IF NOT EXISTS system_config (
  config_key VARCHAR(100) NOT NULL COMMENT 'Settings.DEFINITIONS中的唯一配置键',
  config_value TEXT NOT NULL COMMENT '按定义存文本或AES-GCM密文；敏感值不得手动填明文',
  revision BIGINT NOT NULL COMMENT '每次成功保存递增，用于防止覆盖并发修改',
  updated_at BIGINT NOT NULL COMMENT '最近修改UTC毫秒',
  PRIMARY KEY (config_key)
) COMMENT='运营配置；只允许注册键，敏感值加密，修改必须带revision';

CREATE TABLE IF NOT EXISTS config_audit (
  id VARCHAR(36) NOT NULL COMMENT '审计UUID',
  user_id VARCHAR(36) NOT NULL COMMENT '操作管理员账户ID',
  config_key VARCHAR(100) NOT NULL COMMENT '被修改的配置键',
  revision BIGINT NOT NULL COMMENT '修改后的配置版本',
  action_name VARCHAR(40) NOT NULL COMMENT 'UPDATE修改或CLEAR明确清除敏感值',
  created_at BIGINT NOT NULL COMMENT '操作UTC毫秒',
  PRIMARY KEY (id)
) COMMENT='配置操作审计；仅记键和动作，不保存敏感旧值新值';

CREATE TABLE IF NOT EXISTS catalog_guard (
  id INTEGER PRIMARY KEY COMMENT '固定为1；栏目数量和发布操作的数据库互斥锁',
  revision BIGINT NOT NULL COMMENT '每次目录变更递增，供排查并发更新'
) COMMENT='目录写入锁；不可删除唯一锁行';

CREATE TABLE IF NOT EXISTS catalog_product (
  created_at BIGINT NOT NULL DEFAULT 0 COMMENT '创建时间，历史无审计时取旧更新时间',
  sort_name VARCHAR(200) NOT NULL DEFAULT '' COMMENT '草稿名称排序',
  sort_version VARCHAR(80) NOT NULL DEFAULT '' COMMENT '草稿版本文本排序',
  sort_price DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '草稿积分价格排序',
  sort_tags VARCHAR(1800) NOT NULL DEFAULT '' COMMENT '草稿标签排序',
  id VARCHAR(36) PRIMARY KEY COMMENT '内部UUID，不随名称变化',
  slug VARCHAR(80) NOT NULL UNIQUE COMMENT '公开稳定地址，首次发布后禁止修改',
  draft_json LONGTEXT NOT NULL COMMENT '待发布商品资料；包含私有交付地址，不能直接返回公开接口',
  revision BIGINT NOT NULL COMMENT '乐观锁版本，防止覆盖他人修改',
  active_publication VARCHAR(36) COMMENT '当前发布快照ID，空表示从未发布',
  status VARCHAR(20) NOT NULL COMMENT 'DRAFT草稿、PUBLISHED上架、UNLISTED下架',
  last_published_at BIGINT COMMENT '最近一次成功发布UTC毫秒，后台日期范围使用此字段',
  search_text VARCHAR(1600) NOT NULL DEFAULT '' COMMENT '草稿名称、地址与摘要的小写查询索引',
  first_published_at BIGINT COMMENT '首次上架时间，重发和回滚不改变最新排序',
  updated_at BIGINT NOT NULL COMMENT '最后写操作UTC毫秒'
) COMMENT='商品草稿及发布指针；历史快照不覆盖';

CREATE TABLE IF NOT EXISTS catalog_publication (
  id VARCHAR(36) PRIMARY KEY COMMENT '不可变发布版本UUID',
  product_id VARCHAR(36) NOT NULL COMMENT '所属catalog_product.id',
  snapshot_json LONGTEXT NOT NULL COMMENT '发布时的安全公开字段，不包含交付地址',
  private_json LONGTEXT NOT NULL COMMENT '发布时交付条款和地址，仅服务端使用',
  file_name VARCHAR(180) NOT NULL COMMENT '相对publications目录的HTML路径，不接受用户任意路径',
  sha256 VARCHAR(64) NOT NULL COMMENT '静态HTML完整性摘要；校验失败禁止回滚',
  actor_id VARCHAR(36) NOT NULL COMMENT '发布管理员或商品运营',
  created_at BIGINT NOT NULL COMMENT '生成时间UTC毫秒'
) COMMENT='不可变商品发布快照；回滚只切换商品指针';

CREATE TABLE IF NOT EXISTS home_section (
 created_at BIGINT NOT NULL DEFAULT 0 COMMENT '创建UTC毫秒，历史无依据为0',
  id VARCHAR(36) PRIMARY KEY COMMENT '稳定栏目ID，改名不改变更多链接',
  config_json LONGTEXT NOT NULL COMMENT '双语标题、说明、图标、启停、排序和选品规则',
  revision BIGINT NOT NULL COMMENT '乐观锁版本；包含停用栏目总数不超过6'
) COMMENT='首页栏目配置；仅删除栏目，不级联删除商品';

CREATE TABLE IF NOT EXISTS catalog_audit (
  id VARCHAR(36) PRIMARY KEY COMMENT '审计UUID',
  actor_id VARCHAR(36) NOT NULL COMMENT '操作用户ID',
  entity_id VARCHAR(80) NOT NULL COMMENT '商品或栏目ID',
  action_name VARCHAR(40) NOT NULL COMMENT 'CREATE/SAVE/PUBLISH/UNLIST/ROLLBACK/DELETE_SECTION',
  detail VARCHAR(200) NOT NULL COMMENT '版本等摘要；不得记录凭据或私有下载地址',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='商品与栏目操作审计；禁止通过更新历史记录修正业务';

CREATE TABLE IF NOT EXISTS catalog_metric (
  product_id VARCHAR(36) NOT NULL COMMENT '应用ID',
  metric_day VARCHAR(10) NOT NULL COMMENT '北京时间yyyy-MM-dd',
  visitor_hash VARCHAR(64) NOT NULL COMMENT '站点私有盐摘要，不保存浏览器原始标识',
  metric_type VARCHAR(10) NOT NULL COMMENT 'VIEW浏览或DOWNLOAD成功发放交付',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒',
  PRIMARY KEY(product_id,metric_day,visitor_hash,metric_type)
) COMMENT='同访客同应用每天每类最多一次；只写成功交付而不是下载按钮点击';

CREATE TABLE IF NOT EXISTS schema_migration (
  version VARCHAR(80) PRIMARY KEY COMMENT '已完成迁移的唯一版本',
  description VARCHAR(200) NOT NULL COMMENT '迁移说明',
  applied_at BIGINT NOT NULL COMMENT '成功UTC毫秒'
) COMMENT='启动迁移记录；禁止通过删记录强制重复破坏性迁移';

CREATE TABLE IF NOT EXISTS storage_profile (
  id VARCHAR(36) PRIMARY KEY COMMENT '上传时使用的连接快照UUID',
  fingerprint VARCHAR(64) NOT NULL UNIQUE COMMENT '配置摘要，用于复用相同连接，不返回浏览器',
  provider VARCHAR(12) NOT NULL COMMENT 'LOCAL或S3',
  config_enc LONGTEXT NOT NULL COMMENT 'AES-GCM加密的连接配置，含凭据，禁止输出或手填明文',
  created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
) COMMENT='历史存储连接快照；存储切换不移动或覆盖已有对象';

CREATE TABLE IF NOT EXISTS storage_asset (
  id VARCHAR(36) PRIMARY KEY COMMENT '资源UUID，不能推导为下载授权',
  profile_id VARCHAR(36) NOT NULL COMMENT '关联storage_profile.id',
  object_key VARCHAR(220) NOT NULL COMMENT '服务器生成的相对对象键，禁止客户端文件路径',
  original_name VARCHAR(200) NOT NULL COMMENT '用户文件名，已清除路径和控制字符',
  mime_type VARCHAR(100) NOT NULL COMMENT '图片为实际解码格式，下载文件强制octet-stream',
  size_bytes BIGINT NOT NULL COMMENT '实际存储字节数',
  sha256 VARCHAR(64) NOT NULL COMMENT '存储内容SHA-256，图片重新编码后计算',
  purpose VARCHAR(12) NOT NULL COMMENT 'IMAGE图片或DOWNLOAD私有交付文件',
  status VARCHAR(24) NOT NULL COMMENT 'UPLOADING/READY/DELETE_PENDING/DELETED',
  public_visible BOOLEAN NOT NULL COMMENT '图片被正式发布引用后为真；下载文件永不靠此字段公开',
  created_by VARCHAR(36) NOT NULL COMMENT '上传操作者ID',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒',
  updated_at BIGINT NOT NULL COMMENT '最近状态变更UTC毫秒',
  FOREIGN KEY(profile_id) REFERENCES storage_profile(id)
) COMMENT='资源元数据；物理删除失败保留DELETE_PENDING供重试';

CREATE TABLE IF NOT EXISTS storage_reference (
  asset_id VARCHAR(36) NOT NULL COMMENT '关联storage_asset.id',
  owner_type VARCHAR(20) NOT NULL COMMENT 'DRAFT草稿或PUBLICATION不可变发布快照',
  owner_id VARCHAR(36) NOT NULL COMMENT '商品ID或发布UUID',
  PRIMARY KEY(asset_id,owner_type,owner_id),
  FOREIGN KEY(asset_id) REFERENCES storage_asset(id)
) COMMENT='引用保护；历史发布仍引用的安装包和图片不能删除';

CREATE TABLE IF NOT EXISTS storage_download_ticket (
  entitlement_id VARCHAR(36) COMMENT '付费权益UUID，退款后拒绝继续使用凭据',
  token_hash VARCHAR(64) PRIMARY KEY COMMENT '短期随机下载凭据的摘要，不存明文',
  asset_id VARCHAR(36) NOT NULL COMMENT '已获准交付的资源ID',
  publication_id VARCHAR(36) NOT NULL COMMENT '授权时的商品发布版本',
  expires_at BIGINT NOT NULL COMMENT 'UTC毫秒过期时刻',
  created_at BIGINT NOT NULL COMMENT '发放UTC毫秒',
  FOREIGN KEY(asset_id) REFERENCES storage_asset(id)
) COMMENT='短期下载凭据；发放前必须在商品或权益服务校验授权';

CREATE TABLE IF NOT EXISTS storage_audit (
  id VARCHAR(36) PRIMARY KEY COMMENT '审计UUID',
  actor_id VARCHAR(36) NOT NULL COMMENT '操作账户ID',
  entity_id VARCHAR(80) NOT NULL COMMENT '资源、连接或配置标识',
  action_name VARCHAR(40) NOT NULL COMMENT 'UPLOAD/DELETE/DELETE_FAILED/REFRESH_CREDENTIALS',
  detail VARCHAR(200) NOT NULL COMMENT '仅记录结果摘要，禁止密钥和带签名URL',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='存储操作审计，物理删除失败也留痕';

CREATE TABLE IF NOT EXISTS catalog_product_tag (
  product_id VARCHAR(36) NOT NULL COMMENT '所属商品UUID',
  tag VARCHAR(80) NOT NULL COMMENT '当前草稿标签，用于后台筛选',
  PRIMARY KEY(product_id,tag),
  FOREIGN KEY(product_id) REFERENCES catalog_product(id)
) COMMENT='草稿标签查询索引；前台仍以发布快照标签为准';

CREATE TABLE IF NOT EXISTS catalog_tag (
  id VARCHAR(36) PRIMARY KEY COMMENT '稳定标签UUID',
  name VARCHAR(80) NOT NULL UNIQUE COMMENT '标签名称，与当前草稿标签匹配',
  description VARCHAR(500) NOT NULL COMMENT '后台运营备注，不拼入公开HTML',
  revision BIGINT NOT NULL COMMENT '乐观锁版本',
  created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
) COMMENT='独立标签目录；删除目录项不改写历史发布快照';

CREATE TABLE IF NOT EXISTS account_action_token (
 token_hash VARCHAR(64) PRIMARY KEY COMMENT '随机令牌摘要',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 purpose VARCHAR(12) NOT NULL COMMENT 'VERIFY或RESET',
 expires_at BIGINT NOT NULL COMMENT 'UTC毫秒过期边界',
 used_at BIGINT COMMENT '原子消费时间，NULL未使用',
 created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
) COMMENT='邮箱验证与密码重置一次性令牌，只保存摘要';

CREATE TABLE IF NOT EXISTS account_rate (
 rate_key VARCHAR(64) PRIMARY KEY COMMENT '账户及来源摘要',
 attempts INTEGER NOT NULL COMMENT '窗口内次数',
 until_at BIGINT NOT NULL COMMENT '窗口截止UTC毫秒'
) COMMENT='跨重启请求频控';

CREATE TABLE IF NOT EXISTS mail_outbox (
 id VARCHAR(36) PRIMARY KEY COMMENT '消息UUID',
 recipient VARCHAR(254) NOT NULL COMMENT '收件邮箱',
 subject VARCHAR(200) NOT NULL COMMENT '主题',
 body_enc LONGTEXT NOT NULL COMMENT 'AES-GCM加密正文，可能包含一次性链接',
 status VARCHAR(12) NOT NULL COMMENT 'PENDING/SENDING/SENT',
 attempts INTEGER NOT NULL COMMENT '发送尝试次数',
 next_at BIGINT NOT NULL COMMENT '下次发送或失效抢占时间',
 created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
) COMMENT='事务邮件队列，正文加密，失败重试不回显验证链接';

CREATE TABLE IF NOT EXISTS wallet_account (
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT 'SIMULATED，与真实模式隔离',
 balance_units BIGINT NOT NULL COMMENT '可用积分的百分之一整数单位',
 PRIMARY KEY(user_id,mode)
) COMMENT='按用户及账本模式加锁，模拟与未来真实账户不可混用';

CREATE TABLE IF NOT EXISTS points_package (
 created_at BIGINT NOT NULL DEFAULT 0 COMMENT '创建UTC毫秒，历史无依据为0',
 id VARCHAR(36) PRIMARY KEY COMMENT '优惠包UUID',
 name VARCHAR(100) NOT NULL COMMENT '展示名称',
 name_en VARCHAR(100) NOT NULL COMMENT '英文名称',
 price_fen BIGINT NOT NULL COMMENT '售价人民币分',
 points_units BIGINT NOT NULL COMMENT '积分百分之一单位',
 valid_days INTEGER NOT NULL COMMENT '到账起有效天数',
 enabled BOOLEAN NOT NULL COMMENT '是否前台展示',
 position INTEGER NOT NULL COMMENT '展示顺序',
 revision BIGINT NOT NULL COMMENT '乐观锁版本'
) COMMENT='优惠包当前配置，订单另存快照';

CREATE TABLE IF NOT EXISTS finance_guard (
 id INTEGER PRIMARY KEY COMMENT '固定1，不可删除',
 revision BIGINT NOT NULL COMMENT '变更版本'
) COMMENT='优惠包数量变更的数据库互斥锁';

CREATE TABLE IF NOT EXISTS recharge_order (
 id VARCHAR(36) PRIMARY KEY COMMENT '充值订单UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式SIMULATED',
 request_key VARCHAR(80) NOT NULL COMMENT '用户作用域幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '原请求摘要，拒绝同键不同参数',
 package_id VARCHAR(36) COMMENT '优惠包来源，可空',
 package_name VARCHAR(100) NOT NULL COMMENT '下单时套餐名',
 channel VARCHAR(16) NOT NULL COMMENT 'MOCK模拟渠道',
 price_fen BIGINT NOT NULL COMMENT '下单应付人民币分',
 points_units BIGINT NOT NULL COMMENT '应付全额对应积分百分之一',
 valid_days INTEGER NOT NULL COMMENT '普通永久0，优惠包有效天数',
 rate INTEGER NOT NULL COMMENT '下单时普通兑换倍率',
 threshold_fen BIGINT NOT NULL COMMENT '下单时管理员审核门槛分',
 state VARCHAR(16) NOT NULL COMMENT 'PENDING/DECLARED/CLOSED/REJECTED/POSTED',
 actual_fen BIGINT COMMENT '核实实际金额分',
 evidence_id VARCHAR(36) COMMENT '私有付款证据资源',
 declaration VARCHAR(500) NOT NULL COMMENT '用户付款说明',
 reviewer_id VARCHAR(36) COMMENT '最后审核人',
 review_note VARCHAR(1000) NOT NULL COMMENT '核销或拒绝理由，不含密钥',
 created_at BIGINT NOT NULL COMMENT 'UTC毫秒',
 expires_at BIGINT NOT NULL COMMENT '未声明自动关闭时刻',
 declared_at BIGINT COMMENT '声明付款UTC毫秒',
 posted_at BIGINT COMMENT '积分实际到账UTC毫秒',
 UNIQUE(user_id,mode,request_key)
) COMMENT='模拟充值及核销快照，重试不重复入账';

CREATE TABLE IF NOT EXISTS wallet_batch (
 id VARCHAR(36) PRIMARY KEY COMMENT '批次UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '模拟或真实账本隔离',
 source_type VARCHAR(16) NOT NULL COMMENT 'RECHARGE或REFUND',
 source_id VARCHAR(80) NOT NULL COMMENT '来源唯一业务标识',
 total_units BIGINT NOT NULL COMMENT '初始积分百分之一',
 remaining_units BIGINT NOT NULL COMMENT '剩余积分百分之一',
 cost_fen BIGINT NOT NULL COMMENT '初始实付成本分',
 remaining_cost_fen BIGINT NOT NULL COMMENT '剩余未分摊成本分',
 expires_at BIGINT COMMENT 'NULL永久；UTC毫秒边界不含该时刻',
 created_at BIGINT NOT NULL COMMENT '到账UTC毫秒',
 UNIQUE(user_id,mode,source_type,source_id)
) COMMENT='积分批次事实，最早到期先扣，禁止人工直接改余额';

CREATE TABLE IF NOT EXISTS wallet_ledger (
 id VARCHAR(36) PRIMARY KEY COMMENT '流水UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式',
 kind VARCHAR(16) NOT NULL COMMENT 'RECHARGE/PURCHASE/REFUND/EXPIRE',
 source_id VARCHAR(80) NOT NULL COMMENT '对应业务幂等来源',
 delta_units BIGINT NOT NULL COMMENT '变动积分百分之一，有符号',
 balance_after BIGINT NOT NULL COMMENT '本笔完成后可用积分百分之一',
 cost_fen BIGINT NOT NULL COMMENT '对应充值或成本分摊，有符号，不等于商品收入',
 created_at BIGINT NOT NULL COMMENT 'UTC毫秒',
 UNIQUE(user_id,mode,kind,source_id)
) COMMENT='只追加积分流水，余额变更必须与批次和业务同事务';

CREATE TABLE IF NOT EXISTS purchase_order (
 sort_title VARCHAR(100) NOT NULL DEFAULT '' COMMENT '购买时名称排序快照',
 id VARCHAR(36) PRIMARY KEY COMMENT '购买订单及权益UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '模拟或真实权益隔离',
 request_key VARCHAR(80) NOT NULL COMMENT '兑换请求幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '请求内容摘要',
 product_id VARCHAR(36) NOT NULL COMMENT '商品UUID',
 major_version VARCHAR(60) NOT NULL COMMENT '已购大版本，跨大版本可另购',
 publication_id VARCHAR(36) NOT NULL COMMENT '购买时不可变发布UUID',
 snapshot_json LONGTEXT NOT NULL COMMENT '购买时公开商品快照',
 price_units BIGINT NOT NULL COMMENT '实扣积分百分之一',
 cost_fen BIGINT NOT NULL COMMENT '各批次成本的实扣分摊',
 state VARCHAR(16) NOT NULL COMMENT 'ACTIVE或REFUNDED',
 created_at BIGINT NOT NULL COMMENT '兑换UTC毫秒',
 UNIQUE(user_id,mode,request_key)
) COMMENT='兑换时商品与交付发布版本快照，退款撤销访问而不删除订单';

CREATE TABLE IF NOT EXISTS wallet_allocation (
 id VARCHAR(36) PRIMARY KEY COMMENT '分摊UUID',
 purchase_id VARCHAR(36) NOT NULL COMMENT '对应兑换订单',
 batch_id VARCHAR(36) NOT NULL COMMENT '原积分批次',
 units BIGINT NOT NULL COMMENT '本次扣减百分之一积分',
 cost_fen BIGINT NOT NULL COMMENT '本次实际成本分摊',
 expires_at BIGINT COMMENT '扣减时的原到期时间快照'
) COMMENT='扣减分摊，退款严格关联原批次及成本';

CREATE TABLE IF NOT EXISTS refund_request (
 purchase_id VARCHAR(36) PRIMARY KEY COMMENT '原购买订单，唯一退款来源',
 user_id VARCHAR(36) NOT NULL COMMENT '申请账户',
 reason VARCHAR(1000) NOT NULL COMMENT '用户申请原因',
 state VARCHAR(16) NOT NULL COMMENT 'PENDING/REJECTED/APPROVED',
 reviewer_id VARCHAR(36) COMMENT '审核人',
 review_note VARCHAR(1000) NOT NULL COMMENT '逐单审核理由',
 revision BIGINT NOT NULL COMMENT '审核和重提乐观锁',
 created_at BIGINT NOT NULL COMMENT '最近申请UTC毫秒',
 reviewed_at BIGINT COMMENT '最近审核UTC毫秒'
) COMMENT='整单退积分申请；驳回可重提，历次动作留财务审计';

CREATE TABLE IF NOT EXISTS finance_audit (
 id VARCHAR(36) PRIMARY KEY COMMENT '审计UUID',
 actor_id VARCHAR(36) NOT NULL COMMENT '真实操作者',
 entity_id VARCHAR(80) NOT NULL COMMENT '订单或资源标识',
 action_name VARCHAR(40) NOT NULL COMMENT '核销、拒绝、退款等动作',
 detail VARCHAR(1000) NOT NULL COMMENT '审核理由及结果摘要',
 created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='财务操作追加审计，不记录口令或签名链接';

CREATE TABLE IF NOT EXISTS member_notification (
 id VARCHAR(36) PRIMARY KEY COMMENT '通知UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '收件用户',
 title VARCHAR(200) NOT NULL COMMENT '通知主题',
 body VARCHAR(1000) NOT NULL COMMENT '通知正文，不含敏感令牌',
 read_at BIGINT COMMENT '用户读取时间',
 created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='账户站内通知，业务事务内创建';

CREATE TABLE IF NOT EXISTS purchase_request (
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式',
 request_key VARCHAR(80) NOT NULL COMMENT '幂等请求键，含重复已拥有权益的请求',
 request_hash VARCHAR(64) NOT NULL COMMENT '原请求摘要',
 purchase_id VARCHAR(36) NOT NULL COMMENT '对应实际购买权益',
 PRIMARY KEY(user_id,mode,request_key)
) COMMENT='兑换请求别名，避免已拥有时遗漏请求键防重放';

-- P3在线授权
CREATE TABLE IF NOT EXISTS license_policy (
 product_id VARCHAR(36) PRIMARY KEY COMMENT '商品UUID，草稿授权策略',
 device_limit INTEGER NOT NULL COMMENT '发布时冻结的设备数，1到1000',
 revision BIGINT NOT NULL COMMENT '防覆盖版本'
) COMMENT='商品授权策略；修改须重新发布才影响新购';

CREATE TABLE IF NOT EXISTS publication_license_terms (
 publication_id VARCHAR(36) PRIMARY KEY COMMENT '不可变发布UUID',
 device_limit INTEGER NOT NULL COMMENT '发布时设备数快照',
 created_at BIGINT NOT NULL COMMENT '冻结UTC毫秒'
) COMMENT='发布授权条款快照；不得随当前配置覆盖';

CREATE TABLE IF NOT EXISTS software_license (
 purchase_id VARCHAR(36) PRIMARY KEY COMMENT '唯一订单权益，关联SIMULATED购买',
 key_hash VARCHAR(64) NOT NULL UNIQUE COMMENT '随机激活密钥SHA256，只作查找',
 key_enc LONGTEXT NOT NULL COMMENT 'AES-GCM密文，仅所属已验证用户可读取',
 device_limit INTEGER NOT NULL COMMENT '购买时冻结设备数',
 state VARCHAR(12) NOT NULL COMMENT 'ACTIVE或REVOKED，退款同事务撤权',
 created_at BIGINT NOT NULL COMMENT '签发UTC毫秒'
) COMMENT='在线软件授权，跟随已购大版本永久权益';

CREATE TABLE IF NOT EXISTS license_device (
 id VARCHAR(36) PRIMARY KEY COMMENT '设备绑定UUID',
 purchase_id VARCHAR(36) NOT NULL COMMENT '软件授权订单',
 device_hash VARCHAR(64) NOT NULL COMMENT '客户端稳定随机安装标识摘要，不收集硬件原值',
 device_name VARCHAR(80) NOT NULL COMMENT '用户可读设备名称',
 token_hash VARCHAR(64) NOT NULL COMMENT '随机设备在线凭据摘要',
 token_enc LONGTEXT NOT NULL COMMENT 'AES-GCM设备凭据，重复激活同设备返回相同凭据',
 state VARCHAR(12) NOT NULL COMMENT 'ACTIVE或REVOKED',
 created_at BIGINT NOT NULL COMMENT '首次绑定UTC毫秒',
 revoked_at BIGINT COMMENT '解绑或退款撤销UTC毫秒',
 UNIQUE(purchase_id,device_hash)
) COMMENT='在线设备绑定；锁定授权后数有效设备，防并发超额';

CREATE TABLE IF NOT EXISTS license_unbind_request (
 id VARCHAR(36) PRIMARY KEY COMMENT '申请UUID',
 purchase_id VARCHAR(36) NOT NULL COMMENT '授权订单',
 device_id VARCHAR(36) NOT NULL COMMENT '申请解除的绑定',
 user_id VARCHAR(36) NOT NULL COMMENT '所属用户',
 reason VARCHAR(500) NOT NULL COMMENT '用户换机原因',
 state VARCHAR(12) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
 revision BIGINT NOT NULL COMMENT '审核乐观锁版本',
 reviewer_id VARCHAR(36) COMMENT '客服或管理员，禁止自审',
 review_note VARCHAR(500) NOT NULL COMMENT '审核理由；不记录任何授权密钥',
 created_at BIGINT NOT NULL COMMENT '申请UTC毫秒',
 reviewed_at BIGINT COMMENT '审核UTC毫秒'
) COMMENT='换机人工审核；通过释放设备额度，退款取消待处理申请';

-- 昵称和多角色
CREATE TABLE IF NOT EXISTS access_role(id VARCHAR(36) PRIMARY KEY COMMENT '稳定角色代码',name VARCHAR(80) NOT NULL COMMENT '角色显示名',builtin BOOLEAN NOT NULL COMMENT '内置角色不可删除',revision BIGINT NOT NULL COMMENT '并发编辑版本',created_at BIGINT NOT NULL COMMENT '创建UTC毫秒') COMMENT='角色目录，只维护菜单权限';

CREATE TABLE IF NOT EXISTS access_role_menu(role_id VARCHAR(36) NOT NULL COMMENT '角色代码',menu_id VARCHAR(40) NOT NULL COMMENT '服务端菜单白名单键',PRIMARY KEY(role_id,menu_id)) COMMENT='角色菜单授权，服务端同样校验';

CREATE TABLE IF NOT EXISTS access_user_role(user_id VARCHAR(36) NOT NULL COMMENT '账户UUID',role_id VARCHAR(36) NOT NULL COMMENT '角色代码',PRIMARY KEY(user_id,role_id)) COMMENT='用户多角色，权限取并集';

-- 内容运营
-- 内容运营新增表；应用启动幂等创建，勿在运行库手动重复导入。

CREATE TABLE IF NOT EXISTS content_document (
 id VARCHAR(36) PRIMARY KEY COMMENT '内容UUID',
 slug VARCHAR(80) NOT NULL UNIQUE COMMENT '首次发布后固定的公开地址',
 kind VARCHAR(16) NOT NULL COMMENT 'ARTICLE文章/TUTORIAL教程/FAQ问答/CASE案例',
 title_zh VARCHAR(120) NOT NULL COMMENT '当前草稿中文标题，供后台检索',
 draft_json LONGTEXT NOT NULL COMMENT '经过服务端净化的双语草稿',
 state VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/UNLISTED',
 revision BIGINT NOT NULL COMMENT '乐观锁版本',
 active_publication VARCHAR(36) COMMENT '当前不可变发布UUID',
 created_at BIGINT NOT NULL COMMENT '创建UTC毫秒',
 updated_at BIGINT NOT NULL COMMENT '最后修改UTC毫秒'
) COMMENT='文章教程FAQ案例草稿；草稿更新不改变已发布正文';

CREATE TABLE IF NOT EXISTS content_publication (
 id VARCHAR(36) PRIMARY KEY COMMENT '不可变发布UUID',
 document_id VARCHAR(36) NOT NULL COMMENT '所属内容UUID',
 kind VARCHAR(16) NOT NULL COMMENT '发布时内容类型',
 title_zh VARCHAR(120) NOT NULL COMMENT '发布时中文标题',
 title_en VARCHAR(120) NOT NULL COMMENT '发布时英文标题',
 summary_zh VARCHAR(500) NOT NULL COMMENT '发布时中文摘要及SEO描述',
 summary_en VARCHAR(500) NOT NULL COMMENT '发布时英文摘要',
 snapshot_json LONGTEXT NOT NULL COMMENT '双语公开资料快照，不追随草稿变化',
 file_prefix VARCHAR(80) NOT NULL COMMENT '服务器生成的UUID文件名前缀',
 sha_zh VARCHAR(64) NOT NULL COMMENT '中文HTML SHA256',
 sha_en VARCHAR(64) NOT NULL COMMENT '英文或中文回退HTML SHA256',
 created_at BIGINT NOT NULL COMMENT '发布UTC毫秒'
) COMMENT='双语内容静态发布；历史文件及资源引用保留';

-- API、客服与统计
-- API、客服、统计及异常调整；应用增量迁移自动执行，禁止在运行库重复导入。

CREATE TABLE IF NOT EXISTS wallet_adjustment (
 id VARCHAR(36) PRIMARY KEY COMMENT '调整UUID，负调整关联同ID预留分摊',
 user_id VARCHAR(36) NOT NULL COMMENT '被调整账户，禁止操作者本人',
 actor_id VARCHAR(36) NOT NULL COMMENT '管理员操作者',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式，当前仅SIMULATED',
 delta_units BIGINT NOT NULL COMMENT '有符号积分百分之一',
 valid_days INTEGER NOT NULL COMMENT '赠分期限，0永久；扣减不改变原批次期限',
 reason VARCHAR(1000) NOT NULL COMMENT '必须填写的业务原因，不含密码或完整Key',
 request_key VARCHAR(80) NOT NULL COMMENT '操作者作用域幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '原参数摘要，拒绝同键换参',
 created_at BIGINT NOT NULL COMMENT '调整时间',
 UNIQUE(actor_id,request_key)
) COMMENT='异常人工积分调整，赠分成本0，不伪装标准退款或人民币收入';
CREATE TABLE IF NOT EXISTS wallet_hold (
 id VARCHAR(36) PRIMARY KEY COMMENT '预留UUID，关联调用或套餐',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式，当前SIMULATED',
 units BIGINT NOT NULL COMMENT '预留积分百分之一',
 cost_fen BIGINT NOT NULL COMMENT '预留实际充值成本分',
 state VARCHAR(12) NOT NULL COMMENT 'HELD/SETTLED/RELEASED/REFUNDED',
 created_at BIGINT NOT NULL COMMENT '预留时间'
) COMMENT='调用前预留，成功结算，失败释放；不将预留认作收入';
CREATE TABLE IF NOT EXISTS wallet_hold_part (
 id VARCHAR(36) PRIMARY KEY COMMENT '分摊UUID',
 hold_id VARCHAR(36) NOT NULL COMMENT '预留UUID',
 batch_id VARCHAR(36) NOT NULL COMMENT '原积分批次',
 units BIGINT NOT NULL COMMENT '积分百分之一',
 cost_fen BIGINT NOT NULL COMMENT '充值成本分',
 expires_at BIGINT COMMENT '原批次有效期，NULL永久'
) COMMENT='API预留的原始批次分摊，不覆盖软件兑换分摊';
CREATE TABLE IF NOT EXISTS api_definition (
 id VARCHAR(36) PRIMARY KEY COMMENT 'API UUID',
 slug VARCHAR(80) NOT NULL UNIQUE COMMENT '固定英文调用路径',
 name VARCHAR(120) NOT NULL COMMENT '中文名称',
 name_en VARCHAR(120) NOT NULL COMMENT '英文名称',
 description VARCHAR(1000) NOT NULL COMMENT '中文说明及输入约定',
 description_en VARCHAR(1000) NOT NULL COMMENT '英文说明',
 adapter VARCHAR(20) NOT NULL COMMENT 'TEXT_STATS/SHA256/HTTPS_JSON',
 endpoint VARCHAR(1000) NOT NULL COMMENT '管理员配置HTTPS上游，不接受调用者指定URL',
 secret_enc LONGTEXT COMMENT '上游Bearer凭据AES-GCM密文，不回显',
 success_pointer VARCHAR(200) NOT NULL COMMENT 'HTTPS_JSON成功字段JSON Pointer',
 success_value VARCHAR(200) NOT NULL COMMENT '成功值的JSON编码，不只按HTTP200判成功',
 price_units BIGINT NOT NULL COMMENT '成功按次单价百分之一积分',
 enabled BOOLEAN NOT NULL COMMENT '允许新调用及购买',
 revision BIGINT NOT NULL COMMENT '编辑版本',
 created_at BIGINT NOT NULL COMMENT '创建时间'
) COMMENT='可配置API及明确成功判定，不支持未定义成功边界的流式返回';
CREATE TABLE IF NOT EXISTS api_plan (
 id VARCHAR(36) PRIMARY KEY COMMENT '套餐UUID',
 api_id VARCHAR(36) NOT NULL COMMENT '所属API',
 name VARCHAR(120) NOT NULL COMMENT '套餐名称',
 price_units BIGINT NOT NULL COMMENT '购买积分百分之一',
 quota BIGINT NOT NULL COMMENT '固定周期成功调用额度',
 days INTEGER NOT NULL COMMENT '固定天数',
 enabled BOOLEAN NOT NULL COMMENT '允许新购续费',
 revision BIGINT NOT NULL COMMENT '编辑版本',
 created_at BIGINT NOT NULL COMMENT '创建时间'
) COMMENT='现价套餐，购买另存原条款';
CREATE TABLE IF NOT EXISTS api_key (
 id VARCHAR(36) PRIMARY KEY COMMENT 'Key UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 name VARCHAR(80) NOT NULL COMMENT '用户命名',
 prefix VARCHAR(20) NOT NULL COMMENT '展示前缀，不含完整凭据',
 token_hash VARCHAR(64) NOT NULL UNIQUE COMMENT '随机密钥SHA256，原文仅创建时返回',
 enabled BOOLEAN NOT NULL COMMENT '是否启用',
 expires_at BIGINT COMMENT '有效期，NULL长期',
 minute_limit INTEGER NOT NULL COMMENT '每分钟尝试上限，不超过账户上限',
 day_limit BIGINT NOT NULL COMMENT '每日成功及预留总额，0不另限制',
 revision BIGINT NOT NULL COMMENT '修改版本',
 created_at BIGINT NOT NULL COMMENT '创建时间'
) COMMENT='多Key，不存可恢复原文；停用只阻止新请求';
CREATE TABLE IF NOT EXISTS api_key_scope (
 key_id VARCHAR(36) NOT NULL COMMENT 'Key UUID',
 api_id VARCHAR(36) NOT NULL COMMENT '允许的API',
 PRIMARY KEY(key_id,api_id)
) COMMENT='每Key独立API权限，空集合不授权';
CREATE TABLE IF NOT EXISTS api_preference (
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 api_id VARCHAR(36) NOT NULL COMMENT 'API UUID',
 payg BOOLEAN NOT NULL COMMENT '用户主动允许无有效订阅时按次扣分',
 overage BOOLEAN NOT NULL COMMENT '用户主动允许周期额度耗尽后扣分',
 PRIMARY KEY(user_id,api_id)
) COMMENT='按次及超额均需用户明确启用，不自动透支';
CREATE TABLE IF NOT EXISTS api_subscription (
 id VARCHAR(36) PRIMARY KEY COMMENT '购买周期UUID，同时为预留UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 api_id VARCHAR(36) NOT NULL COMMENT 'API UUID',
 plan_id VARCHAR(36) NOT NULL COMMENT '购买时套餐',
 request_key VARCHAR(80) NOT NULL COMMENT '用户幂等标识',
 request_hash VARCHAR(64) NOT NULL COMMENT '购买参数摘要',
 plan_name VARCHAR(120) NOT NULL COMMENT '购买时名称',
 price_units BIGINT NOT NULL COMMENT '购买时积分百分之一',
 quota BIGINT NOT NULL COMMENT '购买时周期额度',
 days INTEGER NOT NULL COMMENT '购买时固定天数',
 used_count BIGINT NOT NULL COMMENT '已成功占用周期额度',
 held_count BIGINT NOT NULL COMMENT '进行中预留额度',
 success_count BIGINT NOT NULL COMMENT '本周期全部成功调用次数，含超额',
 start_at BIGINT NOT NULL COMMENT '生效时间，提前续费接旧到期',
 end_at BIGINT NOT NULL COMMENT '到期边界，不结转余量',
 state VARCHAR(12) NOT NULL COMMENT 'ACTIVE/REFUNDED',
 refund_state VARCHAR(12) NOT NULL COMMENT 'NONE/PENDING/REJECTED/APPROVED',
 refund_reason VARCHAR(1000) NOT NULL COMMENT '退款理由',
 review_note VARCHAR(1000) NOT NULL COMMENT '审核说明',
 reviewer_id VARCHAR(36) COMMENT '审核人，禁止自审',
 reviewed_at BIGINT COMMENT '退款审核时间',
 reminded BOOLEAN NOT NULL COMMENT '到期提醒已入队',
 revision BIGINT NOT NULL COMMENT '乐观锁版本',
 created_at BIGINT NOT NULL COMMENT '购买时间',
 UNIQUE(user_id,request_key)
) COMMENT='每次购买独立周期及原条款；未成功使用才可整单退积分';
CREATE TABLE IF NOT EXISTS api_call (
 id VARCHAR(36) PRIMARY KEY COMMENT '调用UUID及钱包预留UUID',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 key_id VARCHAR(36) NOT NULL COMMENT '实际Key',
 key_prefix VARCHAR(20) NOT NULL COMMENT '调用时前缀快照，轮换不改历史展示',
 api_id VARCHAR(36) NOT NULL COMMENT 'API UUID',
 api_name VARCHAR(120) NOT NULL COMMENT '调用时名称',
 request_key VARCHAR(80) NOT NULL COMMENT '用户API作用域幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '输入摘要，不存请求正文',
 subscription_id VARCHAR(36) COMMENT '预留时有效周期',
 billing VARCHAR(12) NOT NULL COMMENT 'QUOTA/POINTS/FREE',
 price_units BIGINT NOT NULL COMMENT '调用时按次价格快照',
 state VARCHAR(12) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/UNKNOWN',
 error_code VARCHAR(80) NOT NULL COMMENT '脱敏失败原因，不存上游正文',
 response_enc LONGTEXT COMMENT '短期AES加密幂等响应，不含Key',
 day_key VARCHAR(10) NOT NULL COMMENT '预留时北京时间日期',
 debug_call BOOLEAN NOT NULL COMMENT '本人Key在线调试，正常计费',
 deadline_at BIGINT NOT NULL COMMENT '上游超时加恢复宽限',
 created_at BIGINT NOT NULL COMMENT '预留时间',
 finished_at BIGINT COMMENT '结束时间',
 UNIQUE(user_id,api_id,request_key)
) COMMENT='先预留后结算；崩溃记UNKNOWN且不自动重放上游';
CREATE TABLE IF NOT EXISTS api_rate (
 rate_key VARCHAR(80) PRIMARY KEY COMMENT '账户或Key固定窗口键',
 attempts INTEGER NOT NULL COMMENT '当前分钟尝试数',
 window_id BIGINT NOT NULL COMMENT 'UTC分钟编号'
) COMMENT='账户行锁保护的持久限速，多Key不可绕过账户限速';
CREATE TABLE IF NOT EXISTS support_visitor (
 id VARCHAR(36) PRIMARY KEY COMMENT '游客UUID',
 token_hash VARCHAR(64) NOT NULL UNIQUE COMMENT 'HttpOnly游客凭据摘要',
 email VARCHAR(254) NOT NULL COMMENT '自愿留下的邮箱，用一次性邮件核验找回',
 email_optin BOOLEAN NOT NULL COMMENT '游客回复邮件主动订阅，默认关',
 created_at BIGINT NOT NULL COMMENT '创建时间'
) COMMENT='游客不依赖可枚举会话编号鉴权';
CREATE TABLE IF NOT EXISTS support_conversation (
 id VARCHAR(36) PRIMARY KEY COMMENT '会话UUID',
 user_id VARCHAR(36) COMMENT '登录后归属用户，原浏览器登录可关联',
 visitor_id VARCHAR(36) NOT NULL COMMENT '创建时游客凭据归属',
 subject VARCHAR(120) NOT NULL COMMENT '咨询主题',
 topic VARCHAR(16) NOT NULL COMMENT 'GENERAL/RECHARGE，充值必须登录',
 assigned_to VARCHAR(36) COMMENT '当前负责人，离线保留',
 state VARCHAR(12) NOT NULL COMMENT 'OPEN/CLOSED',
 last_seq BIGINT NOT NULL COMMENT '当前消息序号，同会话锁内递增',
 member_read BIGINT NOT NULL COMMENT '用户已读游标，只能前进',
 staff_read BIGINT NOT NULL COMMENT '当前负责人已读游标',
 first_reply_at BIGINT COMMENT '首次客服答复时间，统计响应',
 last_notice_at BIGINT NOT NULL COMMENT '离线邮件节流时刻',
 revision BIGINT NOT NULL COMMENT '抢单转派及状态版本',
 created_at BIGINT NOT NULL COMMENT '创建时间',
 updated_at BIGINT NOT NULL COMMENT '最后活动，作为保留期依据'
) COMMENT='会话原子抢单；私有读写按归属和负责人鉴权';
CREATE TABLE IF NOT EXISTS support_message (
 id VARCHAR(36) PRIMARY KEY COMMENT '消息UUID',
 conversation_id VARCHAR(36) NOT NULL COMMENT '会话UUID',
 seq BIGINT NOT NULL COMMENT '会话递增序号',
 client_key VARCHAR(80) NOT NULL COMMENT '发送幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '内容摘要防止同键换文',
 actor_type VARCHAR(12) NOT NULL COMMENT 'MEMBER/GUEST/STAFF',
 actor_id VARCHAR(36) NOT NULL COMMENT '账户或游客UUID',
 nickname VARCHAR(40) NOT NULL COMMENT '发送时昵称快照',
 kind VARCHAR(12) NOT NULL COMMENT 'TEXT/IMAGE',
 body VARCHAR(4000) NOT NULL COMMENT '纯文本，不执行HTML',
 asset_id VARCHAR(36) COMMENT '私有聊天图片UUID',
 created_at BIGINT NOT NULL COMMENT '发送时间',
 UNIQUE(conversation_id,seq),
 UNIQUE(conversation_id,client_key)
) COMMENT='游标增量读取，发送重试不重复消息和未读';
CREATE TABLE IF NOT EXISTS support_presence (
 user_id VARCHAR(36) PRIMARY KEY COMMENT '在线客服账户',
 seen_at BIGINT NOT NULL COMMENT '最近主动工作台心跳'
) COMMENT='心跳超时离线，不清除会话负责人';
CREATE TABLE IF NOT EXISTS support_attachment (
 asset_id VARCHAR(36) PRIMARY KEY COMMENT '统一存储私有CHAT图片',
 conversation_id VARCHAR(36) NOT NULL COMMENT '所属会话',
 uploader_id VARCHAR(36) NOT NULL COMMENT '上传者账户或游客',
 message_id VARCHAR(36) COMMENT '绑定消息，未发送为空',
 created_at BIGINT NOT NULL COMMENT '上传时间'
) COMMENT='授权查看会话才可取图；不能进入公开媒体或后台文件库';
CREATE TABLE IF NOT EXISTS support_recovery (
 token_hash VARCHAR(64) PRIMARY KEY COMMENT '一次性找回凭据摘要',
 visitor_id VARCHAR(36) NOT NULL COMMENT '已留下邮箱的游客',
 expires_at BIGINT NOT NULL COMMENT '到期时刻',
 used_at BIGINT COMMENT '核验消费时间'
) COMMENT='经邮箱核验换发游客Cookie；不凭邮箱文字直接返回历史';
CREATE TABLE IF NOT EXISTS support_feedback (
 id VARCHAR(36) PRIMARY KEY COMMENT '反馈UUID',
 user_id VARCHAR(36) COMMENT '登录账户',
 visitor_id VARCHAR(36) NOT NULL COMMENT '原浏览器游客',
 nickname VARCHAR(40) NOT NULL COMMENT '提交昵称',
 kind VARCHAR(12) NOT NULL COMMENT 'PUBLIC/PRIVATE',
 body VARCHAR(4000) NOT NULL COMMENT '纯文本内容',
 state VARCHAR(12) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/RESOLVED',
 reply VARCHAR(4000) NOT NULL COMMENT '回复；私密反馈不公开',
 reviewer_id VARCHAR(36) COMMENT '审核处理人',
 revision BIGINT NOT NULL COMMENT '乐观锁版本',
 created_at BIGINT NOT NULL COMMENT '提交时间',
 updated_at BIGINT NOT NULL COMMENT '处理时间'
) COMMENT='公开先审后发，私密内容绝不由公开列表读取';
CREATE TABLE IF NOT EXISTS analytics_visitor (
 visitor_hash VARCHAR(64) PRIMARY KEY COMMENT '匿名Cookie摘要，不保存IP',
 source_host VARCHAR(200) NOT NULL COMMENT '首次来源域名，不保存完整URL或查询串',
 first_at BIGINT NOT NULL COMMENT '首次访问时间'
) COMMENT='首次来源归因；客户端事件为运营估计，不作为财务依据';
CREATE TABLE IF NOT EXISTS analytics_event (
 id VARCHAR(36) PRIMARY KEY COMMENT '事件UUID',
 visitor_hash VARCHAR(64) NOT NULL COMMENT '匿名访客摘要',
 user_id VARCHAR(36) COMMENT '服务端会话账户，不接受前端指定',
 kind VARCHAR(12) NOT NULL COMMENT 'PAGE/SEARCH',
 path VARCHAR(200) NOT NULL COMMENT '页面路径，不含查询串',
 keyword VARCHAR(100) NOT NULL COMMENT '用户搜索关键词，不存任意请求正文',
 source_host VARCHAR(200) NOT NULL COMMENT '首次来源域名',
 day_key VARCHAR(10) NOT NULL COMMENT '北京时间日期',
 dedupe_hash VARCHAR(64) NOT NULL UNIQUE COMMENT '每日访客页面或搜索去重',
 created_at BIGINT NOT NULL COMMENT '事件时间'
) COMMENT='去重浏览、搜索和账户活跃，按配置期限清理';

-- 查询索引（应用通过元数据检查后幂等创建）
CREATE INDEX ix_hold_user ON wallet_hold(user_id,state,created_at);
CREATE INDEX ix_hold_parts ON wallet_hold_part(hold_id);
CREATE INDEX ix_key_owner ON api_key(user_id,created_at);
CREATE INDEX ix_plan_api ON api_plan(api_id,enabled);
CREATE INDEX ix_sub_period ON api_subscription(user_id,api_id,state,start_at,end_at);
CREATE INDEX ix_call_daily ON api_call(key_id,day_key,state);
CREATE INDEX ix_call_owner ON api_call(user_id,created_at);
CREATE INDEX ix_call_recovery ON api_call(state,deadline_at);
CREATE INDEX ix_call_sub ON api_call(subscription_id,state);
CREATE INDEX ix_conv_visitor ON support_conversation(visitor_id,updated_at);
CREATE INDEX ix_conv_user ON support_conversation(user_id,created_at);
CREATE INDEX ix_conv_agent ON support_conversation(assigned_to,state,created_at);
CREATE INDEX ix_chat_asset ON support_attachment(conversation_id,created_at);
CREATE INDEX ix_feedback_owner ON support_feedback(user_id,visitor_id,created_at);
CREATE INDEX ix_feedback_public ON support_feedback(kind,state,created_at);
CREATE INDEX ix_event_day ON analytics_event(day_key,kind);
CREATE INDEX ix_event_user ON analytics_event(user_id,day_key,kind);
CREATE INDEX ix_event_created ON analytics_event(created_at);
