package com.aiwyw;

import org.springframework.jdbc.core.JdbcTemplate;

/** 存储迁移只新增表；文件与连接快照分离，切换存储不会重新解释旧文件的物理位置。 */
public final class StorageSchema {
  private StorageSchema() {}

  public static void migrate(JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS storage_profile (
          id VARCHAR(36) PRIMARY KEY COMMENT '上传时使用的连接快照UUID',
          fingerprint VARCHAR(64) NOT NULL UNIQUE COMMENT '配置摘要，用于复用相同连接，不返回浏览器',
          provider VARCHAR(12) NOT NULL COMMENT 'LOCAL或S3',
          config_enc LONGTEXT NOT NULL COMMENT 'AES-GCM加密的连接配置，含凭据，禁止输出或手填明文',
          created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
        ) COMMENT='历史存储连接快照；存储切换不移动或覆盖已有对象'
        """);
    db.execute(
        """
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
        ) COMMENT='资源元数据；物理删除失败保留DELETE_PENDING供重试'
        """);
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS storage_reference (
          asset_id VARCHAR(36) NOT NULL COMMENT '关联storage_asset.id',
          owner_type VARCHAR(20) NOT NULL COMMENT 'DRAFT草稿或PUBLICATION不可变发布快照',
          owner_id VARCHAR(36) NOT NULL COMMENT '商品ID或发布UUID',
          PRIMARY KEY(asset_id,owner_type,owner_id),
          FOREIGN KEY(asset_id) REFERENCES storage_asset(id)
        ) COMMENT='引用保护；历史发布仍引用的安装包和图片不能删除'
        """);
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS storage_download_ticket (
          token_hash VARCHAR(64) PRIMARY KEY COMMENT '短期随机下载凭据的摘要，不存明文',
          asset_id VARCHAR(36) NOT NULL COMMENT '已获准交付的资源ID',
          publication_id VARCHAR(36) NOT NULL COMMENT '授权时的商品发布版本',
          expires_at BIGINT NOT NULL COMMENT 'UTC毫秒过期时刻',
          created_at BIGINT NOT NULL COMMENT '发放UTC毫秒',
          FOREIGN KEY(asset_id) REFERENCES storage_asset(id)
        ) COMMENT='短期下载凭据；发放前必须在商品或权益服务校验授权'
        """);
    db.execute(
        """
CREATE TABLE IF NOT EXISTS storage_audit (
  id VARCHAR(36) PRIMARY KEY COMMENT '审计UUID',
  actor_id VARCHAR(36) NOT NULL COMMENT '操作账户ID',
  entity_id VARCHAR(80) NOT NULL COMMENT '资源、连接或配置标识',
  action_name VARCHAR(40) NOT NULL COMMENT 'UPLOAD/DELETE/DELETE_FAILED/REFRESH_CREDENTIALS',
  detail VARCHAR(200) NOT NULL COMMENT '仅记录结果摘要，禁止密钥和带签名URL',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='存储操作审计，物理删除失败也留痕'
""");
  }
}
