-- 身份权限增量实体，线上由自动迁移执行，不要手工覆盖数据。
CREATE TABLE IF NOT EXISTS access_role(id VARCHAR(36) PRIMARY KEY COMMENT '稳定角色代码',name VARCHAR(80) NOT NULL COMMENT '角色显示名',builtin BOOLEAN NOT NULL COMMENT '内置角色不可删除',revision BIGINT NOT NULL COMMENT '并发编辑版本',created_at BIGINT NOT NULL COMMENT '创建UTC毫秒') COMMENT='角色目录，只维护菜单权限';

CREATE TABLE IF NOT EXISTS access_role_menu(role_id VARCHAR(36) NOT NULL COMMENT '角色代码',menu_id VARCHAR(40) NOT NULL COMMENT '服务端菜单白名单键',PRIMARY KEY(role_id,menu_id)) COMMENT='角色菜单授权，服务端同样校验';

CREATE TABLE IF NOT EXISTS access_user_role(user_id VARCHAR(36) NOT NULL COMMENT '账户UUID',role_id VARCHAR(36) NOT NULL COMMENT '角色代码',PRIMARY KEY(user_id,role_id)) COMMENT='用户多角色，权限取并集';
