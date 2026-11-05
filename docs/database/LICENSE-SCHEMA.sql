-- P3软件授权，只供结构审查；在线升级由安装迁移执行。
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
