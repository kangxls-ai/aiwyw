package com.aiwyw;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 昵称和多角色只增迁移；旧role_name作为首次迁移来源，后续授权以关联表为准。 */
public final class Identity {
  private static final Map<String, String> LEAF_MENUS =
      Map.ofEntries(
          Map.entry("dashboard", "工作台概览"),
          Map.entry("statistics", "运营统计"),
          Map.entry("applications", "应用管理"),
          Map.entry("licenses", "软件授权与换机"),
          Map.entry("tags", "标签管理"),
          Map.entry("packages", "优惠积分包"),
          Map.entry("orders", "兑换订单"),
          Map.entry("recharge", "充值核销"),
          Map.entry("points", "积分流水"),
          Map.entry("refunds", "退款管理"),
          Map.entry("apis", "API 管理"),
          Map.entry("plans", "套餐与订阅"),
          Map.entry("keys", "Key 管理"),
          Map.entry("usage", "调用统计"),
          Map.entry("conversations", "在线会话"),
          Map.entry("messages", "公开留言"),
          Map.entry("feedback", "私密反馈"),
          Map.entry("articles", "文章与教程"),
          Map.entry("faq", "FAQ 与案例"),
          Map.entry("seo", "SEO 与多语言"),
          Map.entry("users", "用户管理"),
          Map.entry("roles", "角色与权限"),
          Map.entry("sections", "首页栏目"),
          Map.entry("settings", "配置管理"),
          Map.entry("storage", "存储管理"),
          Map.entry("audit", "操作记录"),
          Map.entry("security", "账户安全"));

  /** 分组只是导航节点，不能授予任何子菜单；保存子菜单时补齐其父级。 */
  public static final Map<String, String> PARENTS =
      Map.ofEntries(
          Map.entry("dashboard", "group:workspace"),
          Map.entry("statistics", "group:workspace"),
          Map.entry("applications", "group:products"),
          Map.entry("licenses", "group:products"),
          Map.entry("tags", "group:products"),
          Map.entry("packages", "group:transactions"),
          Map.entry("orders", "group:transactions"),
          Map.entry("recharge", "group:transactions"),
          Map.entry("points", "group:transactions"),
          Map.entry("refunds", "group:transactions"),
          Map.entry("apis", "group:api"),
          Map.entry("plans", "group:api"),
          Map.entry("keys", "group:api"),
          Map.entry("usage", "group:api"),
          Map.entry("conversations", "group:support"),
          Map.entry("messages", "group:support"),
          Map.entry("feedback", "group:support"),
          Map.entry("articles", "group:content"),
          Map.entry("faq", "group:content"),
          Map.entry("seo", "group:content"),
          Map.entry("users", "group:users"),
          Map.entry("roles", "group:users"),
          Map.entry("sections", "group:system"),
          Map.entry("settings", "group:system"),
          Map.entry("storage", "group:system"),
          Map.entry("audit", "group:system"),
          Map.entry("security", "group:system"));

  public static final Map<String, String> GROUPS =
      Map.ofEntries(
          Map.entry("group:workspace", "工作空间"),
          Map.entry("group:products", "商品管理"),
          Map.entry("group:transactions", "交易管理"),
          Map.entry("group:api", "API 服务"),
          Map.entry("group:support", "客服与反馈"),
          Map.entry("group:content", "内容运营"),
          Map.entry("group:users", "用户与权限"),
          Map.entry("group:system", "系统管理"));
  public static final Map<String, String> MENUS;

  static {
    var all = new LinkedHashMap<String, String>(LEAF_MENUS);
    all.putAll(GROUPS);
    MENUS = Map.copyOf(all);
  }

  public static List<String> normalizeMenus(Collection<String> keys) {
    var result = new LinkedHashSet<String>(keys);
    for (String key : keys) {
      String parent = PARENTS.get(key);
      if (parent != null) result.add(parent);
    }
    return List.copyOf(result);
  }

  public static String nickname() {
    return "u"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 14)
        + new java.security.SecureRandom().nextInt(10);
  }

  static void schema(DataSource source, JdbcTemplate db) {
    if (!CatalogQuerySchema.column(source, "app_user", "nickname"))
      db.execute(
          "ALTER TABLE app_user ADD COLUMN nickname VARCHAR(40) NOT NULL DEFAULT '' COMMENT"
              + " '可修改昵称，注册生成随机字母数字，不用于身份关联'");
    db.execute(
        "CREATE TABLE IF NOT EXISTS access_role(id VARCHAR(36) PRIMARY KEY COMMENT '稳定角色代码',name"
            + " VARCHAR(80) NOT NULL COMMENT '角色显示名',builtin BOOLEAN NOT NULL COMMENT"
            + " '内置角色不可删除',revision BIGINT NOT NULL COMMENT '并发编辑版本',created_at BIGINT NOT NULL"
            + " COMMENT '创建UTC毫秒') COMMENT='角色目录，只维护菜单权限'");
    db.execute(
        "CREATE TABLE IF NOT EXISTS access_role_menu(role_id VARCHAR(36) NOT NULL COMMENT"
            + " '角色代码',menu_id VARCHAR(40) NOT NULL COMMENT '服务端菜单白名单键',PRIMARY"
            + " KEY(role_id,menu_id)) COMMENT='角色菜单授权，服务端同样校验'");
    db.execute(
        "CREATE TABLE IF NOT EXISTS access_user_role(user_id VARCHAR(36) NOT NULL COMMENT"
            + " '账户UUID',role_id VARCHAR(36) NOT NULL COMMENT '角色代码',PRIMARY KEY(user_id,role_id))"
            + " COMMENT='用户多角色，权限取并集'");
    for (String role : List.of("ADMIN", "FINANCE", "OPERATOR", "SUPPORT", "USER")) {
      if (db.queryForObject("SELECT COUNT(*) FROM access_role WHERE id=?", Integer.class, role)
          == 0) {
        db.update(
            "INSERT INTO access_role VALUES(?,?,TRUE,1,?)",
            role,
            Map.of(
                    "ADMIN",
                    "管理员",
                    "FINANCE",
                    "财务",
                    "OPERATOR",
                    "商品运营",
                    "SUPPORT",
                    "客服",
                    "USER",
                    "普通用户")
                .get(role),
            System.currentTimeMillis());
        for (String menu : defaults(role))
          db.update("INSERT INTO access_role_menu VALUES(?,?)", role, menu);
      }
    }
    db.update("DELETE FROM access_role_menu WHERE menu_id='email'");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version='V009_MERGED_APPLICATIONS'",
            Integer.class)
        == 0) {
      for (String role :
          db.queryForList(
              "SELECT DISTINCT role_id FROM access_role_menu WHERE menu_id IN"
                  + " ('versions','publishing')",
              String.class))
        if (db.queryForObject(
                "SELECT COUNT(*) FROM access_role_menu WHERE role_id=? AND menu_id='applications'",
                Integer.class,
                role)
            == 0) db.update("INSERT INTO access_role_menu VALUES(?,'applications')", role);
      db.update("DELETE FROM access_role_menu WHERE menu_id IN ('versions','publishing')");
      db.update(
          "INSERT INTO schema_migration"
              + " VALUES('V009_MERGED_APPLICATIONS','应用资料版本发布统一入口，保留原角色授权',?)",
          System.currentTimeMillis());
    }
    // 迁移标记防止启动时把已撤销的角色重新赋给用户。
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version='V006_IDENTITY'", Integer.class)
        == 0) {
      for (var row : db.queryForList("SELECT id,role_name FROM app_user"))
        db.update("INSERT INTO access_user_role VALUES(?,?)", row.get("id"), row.get("role_name"));
      db.update(
          "INSERT INTO schema_migration VALUES('V006_IDENTITY','昵称及用户多角色',?)",
          System.currentTimeMillis());
    }
    for (String id : db.queryForList("SELECT id FROM app_user WHERE nickname=''", String.class))
      db.update("UPDATE app_user SET nickname=? WHERE id=? AND nickname=''", nickname(), id);
    // 新增统计菜单只在迁移首次授予内置管理员/财务；后续手动撤销不会被每次启动覆盖。
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version='V007_OPERATIONS_MENU'",
            Integer.class)
        == 0) {
      for (String role : List.of("ADMIN", "FINANCE"))
        if (db.queryForObject(
                "SELECT COUNT(*) FROM access_role_menu WHERE role_id=? AND menu_id='statistics'",
                Integer.class,
                role)
            == 0) db.update("INSERT INTO access_role_menu VALUES(?,'statistics')", role);
      db.update(
          "INSERT INTO schema_migration VALUES('V007_OPERATIONS_MENU','API客服统计菜单',?)",
          System.currentTimeMillis());
    }
  }

  public static Set<String> defaults(String role) {
    return switch (role) {
      case "ADMIN" -> MENUS.keySet();
      case "OPERATOR" ->
          Set.of(
              "dashboard",
              "applications",
              "tags",
              "sections",
              "storage",
              "licenses",
              "articles",
              "faq",
              "seo",
              "security");
      case "FINANCE" ->
          Set.of(
              "dashboard",
              "statistics",
              "packages",
              "orders",
              "recharge",
              "points",
              "refunds",
              "security");
      case "SUPPORT" ->
          Set.of(
              "dashboard",
              "orders",
              "recharge",
              "points",
              "refunds",
              "licenses",
              "conversations",
              "messages",
              "feedback",
              "security");
      default -> Set.of();
    };
  }
}
