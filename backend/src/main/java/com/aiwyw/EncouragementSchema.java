package com.aiwyw;

import org.springframework.jdbc.core.JdbcTemplate;

/** 日奖励以用户、账本和北京时间业务日唯一；不重算余额、旧捐赠和历史权益快照。 */
final class EncouragementSchema {
  static void migrate(JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS daily_login_reward (
          user_id VARCHAR(36) NOT NULL COMMENT '已认证登录用户；不接受客户端指定',
          mode VARCHAR(12) NOT NULL COMMENT '账本隔离，当前SIMULATED',
          day_key VARCHAR(10) NOT NULL COMMENT '北京时间yyyy-MM-dd；每日唯一发放',
          batch_id VARCHAR(36) NOT NULL COMMENT '永久、成本0的奖励批次UUID',
          amount_units BIGINT NOT NULL COMMENT '鼓励值百分之一，固定100即1鼓励值',
          created_at BIGINT NOT NULL COMMENT '实际发放UTC毫秒',
          PRIMARY KEY(user_id,mode,day_key)
        ) COMMENT='每日首次登录奖励；与批次、余额和流水同事务提交，不允许删除后补领'
        """);
    // 仅调整未来订单的配置；旧订单仍按自身rate/points_units快照核销。
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version='V013_ENCOURAGEMENT'",
            Integer.class)
        == 0) {
      db.update(
          "UPDATE system_config SET config_value='10',revision=revision+1,updated_at=? WHERE"
              + " config_key='wallet.points_per_yuan' AND config_value<>'10'",
          System.currentTimeMillis());
      db.update(
          "INSERT INTO schema_migration VALUES('V013_ENCOURAGEMENT','鼓励值及每日登录奖励；历史账本数值不变',?)",
          System.currentTimeMillis());
    }
  }
}
