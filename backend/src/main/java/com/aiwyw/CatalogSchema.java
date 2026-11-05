package com.aiwyw;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 商品与首页栏目首次迁移。DDL只增不删，可在原P1数据库上原地启动。 每个字段同时有数据库COMMENT和运维数据字典；不要通过删除表来重新初始化。
 * MySQL/H2使用相同字段类型，时间统一UTC毫秒，展示层换算北京时间。
 */
public final class CatalogSchema {
  private CatalogSchema() {}

  public static void migrate(JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_guard (
          id INTEGER PRIMARY KEY COMMENT '固定为1；栏目数量和发布操作的数据库互斥锁',
          revision BIGINT NOT NULL COMMENT '每次目录变更递增，供排查并发更新'
        ) COMMENT='目录写入锁；不可删除唯一锁行'
        """);
    // 启动可以重入；主键保护多实例同时初始化，不吞掉其他类型数据库错误。
    try {
      db.update("INSERT INTO catalog_guard(id,revision) VALUES(1,0)");
    } catch (org.springframework.dao.DuplicateKeyException alreadyInitialized) {
      /* Existing installation. */
    }
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_product (
          id VARCHAR(36) PRIMARY KEY COMMENT '内部UUID，不随名称变化',
          slug VARCHAR(80) NOT NULL UNIQUE COMMENT '公开稳定地址，首次发布后禁止修改',
          draft_json LONGTEXT NOT NULL COMMENT '待发布商品资料；包含私有交付地址，不能直接返回公开接口',
          revision BIGINT NOT NULL COMMENT '乐观锁版本，防止覆盖他人修改',
          active_publication VARCHAR(36) COMMENT '当前发布快照ID，空表示从未发布',
          status VARCHAR(20) NOT NULL COMMENT 'DRAFT草稿、PUBLISHED上架、UNLISTED下架',
          first_published_at BIGINT COMMENT '首次上架时间，重发和回滚不改变最新排序',
          updated_at BIGINT NOT NULL COMMENT '最后写操作UTC毫秒'
        ) COMMENT='商品草稿及发布指针；历史快照不覆盖'
        """);
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_publication (
          id VARCHAR(36) PRIMARY KEY COMMENT '不可变发布版本UUID',
          product_id VARCHAR(36) NOT NULL COMMENT '所属catalog_product.id',
          snapshot_json LONGTEXT NOT NULL COMMENT '发布时的安全公开字段，不包含交付地址',
          private_json LONGTEXT NOT NULL COMMENT '发布时交付条款和地址，仅服务端使用',
          file_name VARCHAR(180) NOT NULL COMMENT '相对publications目录的HTML路径，不接受用户任意路径',
          sha256 VARCHAR(64) NOT NULL COMMENT '静态HTML完整性摘要；校验失败禁止回滚',
          actor_id VARCHAR(36) NOT NULL COMMENT '发布管理员或商品运营',
          created_at BIGINT NOT NULL COMMENT '生成时间UTC毫秒'
        ) COMMENT='不可变商品发布快照；回滚只切换商品指针'
        """);
    db.execute(
        """
CREATE TABLE IF NOT EXISTS home_section (
  id VARCHAR(36) PRIMARY KEY COMMENT '稳定栏目ID，改名不改变更多链接',
  config_json LONGTEXT NOT NULL COMMENT '双语标题说明图标启停顺序；MANUAL/TAG/AUTO为六个应用区，ARTICLES为独立文章导航',
  revision BIGINT NOT NULL COMMENT '乐观锁版本；包含停用栏目总数不超过6'
) COMMENT='首页栏目配置；仅删除栏目，不级联删除商品'
""");
    db.execute(
        """
CREATE TABLE IF NOT EXISTS catalog_audit (
  id VARCHAR(36) PRIMARY KEY COMMENT '审计UUID',
  actor_id VARCHAR(36) NOT NULL COMMENT '操作用户ID',
  entity_id VARCHAR(80) NOT NULL COMMENT '商品或栏目ID',
  action_name VARCHAR(40) NOT NULL COMMENT 'CREATE/SAVE/PUBLISH/UNLIST/ROLLBACK/DELETE_SECTION',
  detail VARCHAR(200) NOT NULL COMMENT '版本等摘要；不得记录凭据或私有下载地址',
  created_at BIGINT NOT NULL COMMENT 'UTC毫秒'
) COMMENT='商品与栏目操作审计；禁止通过更新历史记录修正业务'
""");
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_metric (
          product_id VARCHAR(36) NOT NULL COMMENT '应用ID',
          metric_day VARCHAR(10) NOT NULL COMMENT '北京时间yyyy-MM-dd',
          visitor_hash VARCHAR(64) NOT NULL COMMENT '站点私有盐摘要，不保存浏览器原始标识',
          metric_type VARCHAR(10) NOT NULL COMMENT 'VIEW浏览或DOWNLOAD成功发放交付',
          created_at BIGINT NOT NULL COMMENT 'UTC毫秒',
          PRIMARY KEY(product_id,metric_day,visitor_hash,metric_type)
        ) COMMENT='同访客同应用每天每类最多一次；只写成功交付而不是下载按钮点击'
        """);
  }
}
