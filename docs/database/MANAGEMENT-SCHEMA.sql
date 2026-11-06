-- AI万有网：对象创建来源、操作IP。2026-11-05。
-- 结构参考，不要对在线库盲目执行本文件。运行时 ManagementSchema 检查缺失字段后幂等迁移。
-- MySQL DDL隐式提交；先成套备份数据库、根密钥、引导连接、存储及静态文件。

CREATE TABLE IF NOT EXISTS object_origin (
  object_type VARCHAR(60) NOT NULL COMMENT '源码白名单业务表名，不接受客户端标识符',
  object_id VARCHAR(100) NOT NULL COMMENT '原始业务主键，不重新编号',
  created_at BIGINT NOT NULL COMMENT '有依据的创建UTC毫秒，历史未知为0',
  created_by VARCHAR(100) COMMENT '实际创建账户ID；SYSTEM为系统，GUEST前缀为游客，历史未知为空',
  PRIMARY KEY(object_type,object_id)
) COMMENT='对象创建来源；与创建事务一起写入，后续修改不覆盖';

-- 下面四列仅在字段不存在时由程序添加；历史行保留NULL，不伪造客户端IP。
ALTER TABLE config_audit ADD COLUMN source_ip VARCHAR(45) NULL
  COMMENT '操作来源IP；仅信任已配置代理，历史未知为空';
ALTER TABLE catalog_audit ADD COLUMN source_ip VARCHAR(45) NULL
  COMMENT '操作来源IP；仅信任已配置代理，历史未知为空';
ALTER TABLE storage_audit ADD COLUMN source_ip VARCHAR(45) NULL
  COMMENT '操作来源IP；仅信任已配置代理，历史未知为空';
ALTER TABLE finance_audit ADD COLUMN source_ip VARCHAR(45) NULL
  COMMENT '操作来源IP；仅信任已配置代理，历史未知为空';

-- V010_OBJECT_ORIGIN只回填存在可靠依据的来源：原用户ID、原上传者、游客ID或商品CREATE审计。
-- 不得把第一次修改者回填成创建人。无依据的旧记录在界面显示“历史未记录”。
-- created_by不是强外键：未激活账户合法删除后，历史来源仍保留原稳定ID。
-- 读取只允许已完成业务权限验证的对象列表按白名单补充元信息，不提供任意跨用户查询接口。
