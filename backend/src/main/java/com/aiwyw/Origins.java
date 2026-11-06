package com.aiwyw;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只在实际创建时调用；禁止把第一次编辑者误填成历史创建人。沿用模块已校验主键，来源操作者不接受前端覆盖。 */
final class Origins {
  static final Set<String> TABLES =
      Set.of(
          "catalog_product",
          "home_section",
          "catalog_tag",
          "storage_asset",
          "points_package",
          "app_user",
          "access_role",
          "content_document",
          "api_definition",
          "api_plan",
          "api_key",
          "api_subscription",
          "api_call",
          "support_conversation",
          "support_feedback",
          "support_message",
          "recharge_order",
          "purchase_order",
          "refund_request",
          "wallet_ledger",
          "wallet_batch",
          "wallet_adjustment",
          "software_license",
          "license_unbind_request",
          "member_notification",
          "catalog_publication",
          "content_publication");

  static void created(JdbcTemplate db, String type, String id, String actor) {
    created(db, type, id, actor, System.currentTimeMillis());
  }

  static void created(JdbcTemplate db, String type, String id, String actor, long at) {
    if (!TABLES.contains(type)) throw new IllegalArgumentException("origin type");
    db.update(
        "INSERT INTO object_origin(object_type,object_id,created_at,created_by) VALUES(?,?,?,?)",
        type,
        id,
        at,
        actor);
  }
}
