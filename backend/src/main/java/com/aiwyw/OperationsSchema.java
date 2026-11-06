package com.aiwyw;

import org.springframework.jdbc.core.JdbcTemplate;

/** API、客服和统计增量结构。只增表，不改历史交易；所有时间为UTC毫秒，展示和分日采用北京时间。 */
final class OperationsSchema {
  static final String SQL =
      """
CREATE TABLE IF NOT EXISTS wallet_adjustment (
 id VARCHAR(36) PRIMARY KEY COMMENT '调整UUID，负调整关联同ID预留分摊',
 user_id VARCHAR(36) NOT NULL COMMENT '被调整账户，禁止操作者本人',
 actor_id VARCHAR(36) NOT NULL COMMENT '管理员操作者',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式，当前仅SIMULATED',
 delta_units BIGINT NOT NULL COMMENT '有符号鼓励值百分之一',
 valid_days INTEGER NOT NULL COMMENT '赠分期限，0永久；扣减不改变原批次期限',
 reason VARCHAR(1000) NOT NULL COMMENT '必须填写的业务原因，不含密码或完整Key',
 request_key VARCHAR(80) NOT NULL COMMENT '操作者作用域幂等键',
 request_hash VARCHAR(64) NOT NULL COMMENT '原参数摘要，拒绝同键换参',
 created_at BIGINT NOT NULL COMMENT '调整时间',
 UNIQUE(actor_id,request_key)
) COMMENT='异常人工鼓励值调整，赠分成本0，不伪装标准退款或人民币收入';
CREATE TABLE IF NOT EXISTS wallet_hold (
 id VARCHAR(36) PRIMARY KEY COMMENT '预留UUID，关联调用或套餐',
 user_id VARCHAR(36) NOT NULL COMMENT '所属账户',
 mode VARCHAR(12) NOT NULL COMMENT '账本模式，当前SIMULATED',
 units BIGINT NOT NULL COMMENT '预留鼓励值百分之一',
 cost_fen BIGINT NOT NULL COMMENT '预留实际捐赠成本分',
 state VARCHAR(12) NOT NULL COMMENT 'HELD/SETTLED/RELEASED/REFUNDED',
 created_at BIGINT NOT NULL COMMENT '预留时间'
) COMMENT='调用前预留，成功结算，失败释放；不将预留认作收入';
CREATE TABLE IF NOT EXISTS wallet_hold_part (
 id VARCHAR(36) PRIMARY KEY COMMENT '分摊UUID',
 hold_id VARCHAR(36) NOT NULL COMMENT '预留UUID',
 batch_id VARCHAR(36) NOT NULL COMMENT '原鼓励值批次',
 units BIGINT NOT NULL COMMENT '鼓励值百分之一',
 cost_fen BIGINT NOT NULL COMMENT '捐赠成本分',
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
 price_units BIGINT NOT NULL COMMENT '成功按次单价百分之一鼓励值',
 enabled BOOLEAN NOT NULL COMMENT '允许新调用及购买',
 revision BIGINT NOT NULL COMMENT '编辑版本',
 created_at BIGINT NOT NULL COMMENT '创建时间'
) COMMENT='可配置API及明确成功判定，不支持未定义成功边界的流式返回';
CREATE TABLE IF NOT EXISTS api_plan (
 id VARCHAR(36) PRIMARY KEY COMMENT '套餐UUID',
 api_id VARCHAR(36) NOT NULL COMMENT '所属API',
 name VARCHAR(120) NOT NULL COMMENT '套餐名称',
 price_units BIGINT NOT NULL COMMENT '购买鼓励值百分之一',
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
 price_units BIGINT NOT NULL COMMENT '购买时鼓励值百分之一',
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
) COMMENT='每次购买独立周期及原条款；未成功使用才可整单退鼓励值';
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
 topic VARCHAR(16) NOT NULL COMMENT 'GENERAL/RECHARGE，捐赠必须登录',
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
""";

  static void migrate(JdbcTemplate db) {
    for (String statement : SQL.split(";")) if (!statement.isBlank()) db.execute(statement);
    if (!CatalogQuerySchema.column(db.getDataSource(), "api_call", "key_prefix"))
      db.execute(
          "ALTER TABLE api_call ADD COLUMN key_prefix VARCHAR(20) NOT NULL DEFAULT '' COMMENT"
              + " '调用时Key前缀，旧记录未知时留空'");
    // 查询路径索引独立幂等补齐，升级不重建表；名称及字段来自源码白名单。
    for (String[] i :
        new String[][] {
          {"wallet_hold", "ix_hold_user", "user_id,state,created_at"},
          {"wallet_hold_part", "ix_hold_parts", "hold_id"},
          {"api_key", "ix_key_owner", "user_id,created_at"},
          {"api_plan", "ix_plan_api", "api_id,enabled"},
          {"api_subscription", "ix_sub_period", "user_id,api_id,state,start_at,end_at"},
          {"api_call", "ix_call_daily", "key_id,day_key,state"},
          {"api_call", "ix_call_owner", "user_id,created_at"},
          {"api_call", "ix_call_recovery", "state,deadline_at"},
          {"api_call", "ix_call_sub", "subscription_id,state"},
          {"support_conversation", "ix_conv_visitor", "visitor_id,updated_at"},
          {"support_conversation", "ix_conv_user", "user_id,created_at"},
          {"support_conversation", "ix_conv_agent", "assigned_to,state,created_at"},
          {"support_attachment", "ix_chat_asset", "conversation_id,created_at"},
          {"support_feedback", "ix_feedback_owner", "user_id,visitor_id,created_at"},
          {"support_feedback", "ix_feedback_public", "kind,state,created_at"},
          {"analytics_event", "ix_event_day", "day_key,kind"},
          {"analytics_event", "ix_event_user", "user_id,day_key,kind"},
          {"analytics_event", "ix_event_created", "created_at"}
        })
      if (!hasIndex(db, i[0], i[1]))
        try {
          db.execute("CREATE INDEX " + i[1] + " ON " + i[0] + "(" + i[2] + ")");
        } catch (org.springframework.dao.DataAccessException race) {
          if (!hasIndex(db, i[0], i[1])) throw race;
        }
  }

  private static boolean hasIndex(JdbcTemplate db, String table, String name) {
    try (var c = db.getDataSource().getConnection()) {
      String physical =
          c.getMetaData().storesUpperCaseIdentifiers()
              ? table.toUpperCase(java.util.Locale.ROOT)
              : table;
      try (var rows = c.getMetaData().getIndexInfo(c.getCatalog(), null, physical, false, false)) {
        while (rows.next()) if (name.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
      }
      return false;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Cannot inspect operation indexes", e);
    }
  }
}
