package com.aiwyw;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅增加展示/转发字段，不重写旧订单、不改变历史鼓励值条款；旧捐赠未生成备注码时保持空值。 */
final class ExperienceSchema {
  static void migrate(DataSource source, JdbcTemplate db) {
    String[][] fields = {
      {
        "mail_outbox",
        "last_error_code",
        "VARCHAR(30) NULL COMMENT '最近失败脱敏分类AUTH/TLS/DNS/TIMEOUT/CONNECT/DELIVERY；成功清除，不保存异常原文'"
      },
      {"recharge_order", "payment_memo", "VARCHAR(4) NULL COMMENT '创建时随机四位付款备注，可重复，非支付凭证；旧订单为空'"},
      {
        "api_definition",
        "request_method",
        "VARCHAR(8) NOT NULL DEFAULT 'POST' COMMENT '真实上游请求方式GET或POST；平台统一POST入口不变'"
      },
      {
        "api_definition",
        "headers_enc",
        "LONGTEXT NULL COMMENT '自定义上游请求头JSON的AES-GCM密文；不返回原文，不转发用户Cookie或平台Key'"
      }
    };
    for (String[] f : fields)
      if (!CatalogQuerySchema.column(source, f[0], f[1]))
        db.execute("ALTER TABLE " + f[0] + " ADD COLUMN " + f[1] + " " + f[2]);
  }
}
