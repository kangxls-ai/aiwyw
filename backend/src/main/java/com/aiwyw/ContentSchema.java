package com.aiwyw;

import org.springframework.jdbc.core.JdbcTemplate;

/** 内容草稿与不可变发布快照分开；只增表，不覆盖现有商品和账务数据。 */
final class ContentSchema {
  static void migrate(JdbcTemplate db) {
    db.execute(
        """
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
) COMMENT='文章教程FAQ案例草稿；草稿更新不改变已发布正文'
""");
    db.execute(
        """
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
) COMMENT='双语内容静态发布；历史文件及资源引用保留'
""");
  }
}
