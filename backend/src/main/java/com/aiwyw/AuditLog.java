package com.aiwyw;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 业务事务内统一写审计；不记录密码、令牌或上游凭据。后台任务没有HTTP来源时IP为空。 */
final class AuditLog {
  static void write(
      JdbcTemplate db, String table, String actor, String entity, String action, String detail) {
    if (!Set.of("catalog_audit", "finance_audit", "storage_audit").contains(table))
      throw new IllegalArgumentException("audit table");
    db.update(
        "INSERT INTO "
            + table
            + "(id,actor_id,entity_id,action_name,detail,created_at,source_ip)"
            + " VALUES(?,?,?,?,?,?,?)",
        Ids.next(),
        actor,
        entity,
        action,
        detail,
        System.currentTimeMillis(),
        RequestIp.current());
  }
}
