-- 第二阶段无损增量结构，启动由CommerceSchema执行，升级前备份；禁止覆盖既有库。
-- app_user新增email_verified_at、disabled、business_email；storage_download_ticket新增entitlement_id。
-- 金额单位人民币分，积分单位百分之一；模拟模式不能直接改写成真实模式。

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