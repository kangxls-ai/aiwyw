package com.aiwyw;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 财务读服务端事实；行为只存路径、来源域及摘要标识，不存IP/完整URL/Key/正文。 */
@Service
public class OperationsStats {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  final JdbcTemplate db;
  final SecureStore crypto;
  final Settings settings;
  final Accounts accounts;
  final Auth auth;

  OperationsStats(
      JdbcTemplate db, SecureStore crypto, Settings settings, Accounts accounts, Auth auth) {
    this.db = db;
    this.crypto = crypto;
    this.settings = settings;
    this.accounts = accounts;
    this.auth = auth;
  }

  public record Window(LocalDate from, LocalDate until, long start, long end) {}

  static Window window(String from, String until) {
    try {
      LocalDate
          f =
              from == null || from.isBlank()
                  ? LocalDate.now(ZONE).minusDays(29)
                  : LocalDate.parse(from),
          u = until == null || until.isBlank() ? LocalDate.now(ZONE) : LocalDate.parse(until);
      if (u.isBefore(f) || java.time.temporal.ChronoUnit.DAYS.between(f, u) > 365)
        throw new IllegalArgumentException();
      return new Window(
          f,
          u,
          f.atStartOfDay(ZONE).toInstant().toEpochMilli(),
          u.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
    } catch (Exception e) {
      throw new Api.Failure(400, "请选择不超过366天的有效北京时间日期范围");
    }
  }

  public record Event(String kind, String path, String keyword, String source) {}

  /** 静态正文仍直接读文件；只在独立统计边界记录去重事件，统计故障不能阻断内容交付。 */
  void pageEvent(String path, HttpServletRequest r, HttpServletResponse response) {
    try {
      String source = "";
      try {
        String ref = r.getHeader("Referer");
        if (ref != null) source = java.net.URI.create(ref).getHost();
      } catch (Exception ignored) {
      }
      event(new Event("PAGE", path, "", source), r, response);
    } catch (Exception ignored) {
      /* 行为事件允许少计，不能影响正文或财务事实。 */
    }
  }

  public void event(Event b, HttpServletRequest r, HttpServletResponse response) {
    if (!Boolean.parseBoolean(settings.value("analytics.enabled"))) return;
    String kind = MeteredApi.text(b.kind(), 12, true),
        path = MeteredApi.text(b.path(), 200, true),
        keyword = MeteredApi.text(b.keyword(), 100, false),
        source = MeteredApi.text(b.source(), 200, false).toLowerCase(Locale.ROOT);
    if (!Set.of("PAGE", "SEARCH").contains(kind)
        || !path.matches(
            "/(?:apps(?:/[a-z0-9-]+)?|articles(?:/[a-z0-9-]+)?|api-services(?:/[a-z0-9-]+)?|sections/[a-z0-9-]+|series(?:/[a-z0-9-]+)?|support)?")
        || (kind.equals("SEARCH") && keyword.isBlank())) throw new Api.Failure(400, "事件格式不正确");
    if (kind.equals("PAGE")) keyword = "";
    if (source.isBlank() || source.equals(settings.value("site.domain"))) source = "DIRECT";
    else if (!source.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || source.length() > 200)
      throw new Api.Failure(400, "来源仅保留域名");
    Auth.User user = null;
    try {
      user = auth.user(r, false);
    } catch (Api.Failure ignored) {
    }
    if (user != null
        && !user.menus().isEmpty()
        && Boolean.parseBoolean(settings.value("analytics.exclude_staff"))) return;
    accounts.limit("analytics", r.getRemoteAddr(), settings.integer("analytics.hour_limit"));
    String token = null;
    if (r.getCookies() != null)
      for (Cookie c : r.getCookies())
        if (c.getName().equals("AIWYW_ANALYTICS")) token = c.getValue();
    if (token == null || !token.matches("[a-f0-9]{32}")) {
      token = UUID.randomUUID().toString().replace("-", "");
      var c = new Cookie("AIWYW_ANALYTICS", token);
      c.setHttpOnly(true);
      c.setPath("/");
      c.setSecure(AuthController.secure(r));
      c.setAttribute("SameSite", "Lax");
      c.setMaxAge(365 * 86400);
      response.addCookie(c);
    }
    String visitor = crypto.digest(token), day = LocalDate.now(ZONE).toString();
    long now = System.currentTimeMillis();
    try {
      db.update("INSERT INTO analytics_visitor VALUES(?,?,?)", visitor, source, now);
    } catch (org.springframework.dao.DuplicateKeyException exists) {
    }
    String
        first =
            db.queryForObject(
                "SELECT source_host FROM analytics_visitor WHERE visitor_hash=?",
                String.class,
                visitor),
        dedupe = crypto.digest(visitor + ":" + day + ":" + kind + ":" + path + ":" + keyword);
    try {
      db.update(
          "INSERT INTO analytics_event VALUES(?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          visitor,
          user == null ? null : user.id(),
          kind,
          path,
          keyword,
          first,
          day,
          dedupe,
          now);
    } catch (org.springframework.dao.DuplicateKeyException exists) {
      if (user != null)
        db.update(
            "UPDATE analytics_event SET user_id=? WHERE dedupe_hash=? AND user_id IS NULL",
            user.id(),
            dedupe);
    }
  }

  /** 各模块原始收入与退款独立记在实际发生日，跨期退款允许净额为负；模拟与真实不混合。 */
  static final String INCOME =
      """
SELECT product_id AS entity_id,sort_title AS title,'PRODUCT' AS category,mode,price_units AS units,cost_fen,created_at AS happened_at,1 AS charges,0 AS refunds FROM purchase_order
UNION ALL SELECT o.product_id,o.sort_title,'PRODUCT',o.mode,-o.price_units,-o.cost_fen,r.reviewed_at,0,1 FROM refund_request r JOIN purchase_order o ON o.id=r.purchase_id WHERE r.state='APPROVED'
UNION ALL SELECT s.api_id,s.plan_name,'API_PLAN',h.mode,s.price_units,h.cost_fen,s.created_at,1,0 FROM api_subscription s JOIN wallet_hold h ON h.id=s.id
UNION ALL SELECT s.api_id,s.plan_name,'API_PLAN',h.mode,-s.price_units,-h.cost_fen,s.reviewed_at,0,1 FROM api_subscription s JOIN wallet_hold h ON h.id=s.id WHERE s.refund_state='APPROVED'
UNION ALL SELECT c.api_id,c.api_name,'API_CALL',h.mode,c.price_units,h.cost_fen,c.finished_at,1,0 FROM api_call c JOIN wallet_hold h ON h.id=c.id WHERE c.state='SUCCESS'
""";

  /** 与活跃账户指标使用相同PAGE事实和左闭右开窗口；已删除账户的历史ID仍保留，钻取数量不缩水。 */
  public Map<String, Object> activeUsers(
      String from, String until, int page, int size, String sort, String direction) {
    Window w = window(from, until);
    if (page < 1 || size < 1 || size > 100) throw new Api.Failure(400, "分页参数不正确");
    String grouped =
        "SELECT user_id,COUNT(*) AS pages,COUNT(DISTINCT day_key) AS active_days,MIN(created_at) AS"
            + " first_seen_at,MAX(created_at) AS last_seen_at FROM analytics_event WHERE"
            + " kind='PAGE' AND user_id IS NOT NULL AND created_at>=? AND created_at<? GROUP BY"
            + " user_id";
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + grouped + ") a", Long.class, w.start(), w.end());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    String ordering =
        ListOrder.sql(
            sort,
            direction == null ? "descending" : direction,
            Map.of(
                "last_seen_at",
                "a.last_seen_at",
                "first_seen_at",
                "a.first_seen_at",
                "pages",
                "a.pages",
                "active_days",
                "a.active_days",
                "created_at",
                "u.created_at",
                "nickname",
                "u.nickname"),
            "created_at",
            "a.user_id");
    var items =
        Commerce.safeRows(
            db.queryForList(
                "SELECT a.user_id AS id,COALESCE(u.nickname,'已删除账户') AS"
                    + " nickname,u.created_at,u.email_verified_at,u.disabled,a.pages,a.active_days,a.first_seen_at,a.last_seen_at"
                    + " FROM ("
                    + grouped
                    + ") a LEFT JOIN app_user u ON u.id=a.user_id ORDER BY "
                    + ordering
                    + " LIMIT ? OFFSET ?",
                w.start(),
                w.end(),
                size,
                (actual - 1) * size));
    return Map.of(
        "items",
        items,
        "total",
        total,
        "page",
        actual,
        "from",
        w.from().toString(),
        "until",
        w.until().toString());
  }

  public Map<String, Object> report(String from, String until, String mode) {
    if (!Set.of("REAL", "SIMULATED").contains(mode)) throw new Api.Failure(400, "请选择真实或模拟账本");
    Window w = window(from, until);
    var traffic =
        db.queryForMap(
            "SELECT COUNT(*) AS events,COUNT(DISTINCT visitor_hash) AS visitors,COUNT(DISTINCT"
                + " user_id) AS active_users FROM analytics_event WHERE kind='PAGE' AND"
                + " created_at>=? AND created_at<?",
            w.start(),
            w.end());
    var days =
        Commerce.safeRows(
            db.queryForList(
                "SELECT day_key,COUNT(*) AS views,COUNT(DISTINCT visitor_hash) AS"
                    + " visitors,COUNT(DISTINCT user_id) AS active_users FROM analytics_event WHERE"
                    + " kind='PAGE' AND created_at>=? AND created_at<? GROUP BY day_key ORDER BY"
                    + " day_key",
                w.start(),
                w.end()));
    var sources =
        Commerce.safeRows(
            db.queryForList(
                "SELECT source_host,COUNT(DISTINCT visitor_hash) AS visitors FROM analytics_event"
                    + " WHERE kind='PAGE' AND created_at>=? AND created_at<? GROUP BY source_host"
                    + " ORDER BY visitors DESC,source_host LIMIT 50",
                w.start(),
                w.end()));
    var keywords =
        Commerce.safeRows(
            db.queryForList(
                "SELECT keyword,COUNT(*) AS searches,COUNT(DISTINCT visitor_hash) AS visitors FROM"
                    + " analytics_event WHERE kind='SEARCH' AND created_at>=? AND created_at<?"
                    + " GROUP BY keyword ORDER BY searches DESC,keyword LIMIT 50",
                w.start(),
                w.end()));
    var income =
        Commerce.safeRows(
            db.queryForList(
                "SELECT entity_id,title,category,SUM(units) AS net_units,SUM(cost_fen) AS"
                    + " net_cost_fen,SUM(charges) AS charges,SUM(refunds) AS refunds FROM ("
                    + INCOME
                    + ") facts WHERE mode=? AND happened_at>=? AND happened_at<? GROUP BY"
                    + " entity_id,title,category ORDER BY net_cost_fen DESC,entity_id,title",
                mode,
                w.start(),
                w.end()));
    var cash =
        Commerce.safeRows(
                List.of(
                    db.queryForMap(
                        "SELECT COUNT(*) AS posted_orders,COALESCE(SUM(actual_fen),0) AS"
                            + " recharge_fen FROM recharge_order WHERE mode=? AND state='POSTED'"
                            + " AND posted_at>=? AND posted_at<?",
                        mode,
                        w.start(),
                        w.end())))
            .get(0);
    var api =
        Commerce.safeRows(
            db.queryForList(
                "SELECT api_id,api_name,state,COUNT(*) AS calls,AVG(CASE WHEN finished_at IS NOT"
                    + " NULL THEN finished_at-created_at ELSE NULL END) AS mean_ms FROM api_call"
                    + " WHERE created_at>=? AND created_at<? GROUP BY api_id,api_name,state ORDER"
                    + " BY calls DESC,api_id,state",
                w.start(),
                w.end()));
    var support =
        Commerce.safeRows(
                List.of(
                    db.queryForMap(
                        "SELECT COUNT(*) AS conversations,COALESCE(SUM(CASE WHEN state='OPEN' THEN"
                            + " 1 ELSE 0 END),0) AS open_count,COALESCE(SUM(CASE WHEN assigned_to"
                            + " IS NULL AND state='OPEN' THEN 1 ELSE 0 END),0) AS waiting,AVG(CASE"
                            + " WHEN first_reply_at IS NOT NULL THEN first_reply_at-created_at ELSE"
                            + " NULL END) AS mean_first_reply_ms FROM support_conversation WHERE"
                            + " created_at>=? AND created_at<?",
                        w.start(),
                        w.end())))
            .get(0);
    var feedback =
        Commerce.safeRows(
            db.queryForList(
                "SELECT kind,state,COUNT(*) AS records FROM support_feedback WHERE created_at>=?"
                    + " AND created_at<? GROUP BY kind,state",
                w.start(),
                w.end()));
    List<Map<String, Object>> retention = new ArrayList<>();
    LocalDate first =
        w.until().minusDays(30).isAfter(w.from()) ? w.until().minusDays(30) : w.from();
    // 留存以注册日为 cohort；只计注册账户在目标北京时间自然日实际登录访问，不推断游客身份。
    for (LocalDate d = first; !d.isAfter(w.until()); d = d.plusDays(1)) {
      long a = d.atStartOfDay(ZONE).toInstant().toEpochMilli(),
          z = d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
      String users =
          "SELECT u.id FROM app_user u WHERE u.created_at>=? AND u.created_at<?"
              + (Boolean.parseBoolean(settings.value("analytics.exclude_staff"))
                  ? " AND NOT EXISTS (SELECT 1 FROM access_user_role ur JOIN access_role_menu rm ON"
                      + " rm.role_id=ur.role_id WHERE ur.user_id=u.id AND rm.menu_id NOT LIKE"
                      + " 'group:%')"
                  : "");
      long cohort =
          db.queryForObject("SELECT COUNT(*) FROM (" + users + ") cohort_users", Long.class, a, z);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("day", d.toString());
      row.put("registered", cohort);
      for (int offset : new int[] {1, 7, 30}) {
        LocalDate target = d.plusDays(offset);
        Object count = null;
        if (!target.isAfter(LocalDate.now(ZONE)))
          count =
              cohort == 0
                  ? 0L
                  : db.queryForObject(
                      "SELECT COUNT(DISTINCT user_id) FROM analytics_event WHERE kind='PAGE' AND"
                          + " day_key=? AND user_id IN ("
                          + users
                          + ")",
                      Long.class,
                      target.toString(),
                      a,
                      z);
        row.put("d" + offset, count);
      }
      retention.add(row);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timezone", "Asia/Shanghai");
    result.put("mode", mode);
    result.put("from", w.from().toString());
    result.put("until", w.until().toString());
    result.put("traffic", Commerce.safeRows(List.of(traffic)).get(0));
    result.put("days", days);
    result.put("sources", sources);
    result.put("keywords", keywords);
    result.put("income", income);
    result.put("cash", cash);
    result.put("api", api);
    result.put("support", support);
    result.put("feedback", feedback);
    result.put("retention", retention);
    return result;
  }

  public byte[] csv(String from, String until, String mode) {
    var report = report(from, until, mode);
    StringBuilder csv =
        new StringBuilder("\uFEFF类别,应用或套餐,净消费鼓励值,实际捐赠成本分摊元,购买或成功调用数,退款数,账本模式,时区\r\n");
    for (var row : (List<Map<String, Object>>) report.get("income")) {
      csv.append(cell(row.get("category")))
          .append(',')
          .append(cell(row.get("title")))
          .append(',')
          .append(
              Commerce.decimal(
                  new java.math.BigDecimal(String.valueOf(row.get("net_units"))).longValueExact()))
          .append(',')
          .append(
              Commerce.decimal(
                  new java.math.BigDecimal(String.valueOf(row.get("net_cost_fen")))
                      .longValueExact()))
          .append(',')
          .append(row.get("charges"))
          .append(',')
          .append(row.get("refunds"))
          .append(',')
          .append(mode)
          .append(",Asia/Shanghai\r\n");
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String cell(Object input) {
    String v = String.valueOf(input);
    String stripped = v.stripLeading();
    if (!stripped.isEmpty() && "=+@-".indexOf(stripped.charAt(0)) >= 0) v = "'" + v;
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }

  @Scheduled(fixedDelay = 3600000)
  public void purge() {
    long cutoff =
        System.currentTimeMillis() - settings.integer("analytics.retention_days") * 86400000L;
    db.update("DELETE FROM analytics_event WHERE created_at<?", cutoff);
    db.update(
        "DELETE FROM analytics_visitor WHERE first_at<? AND visitor_hash NOT IN (SELECT"
            + " visitor_hash FROM analytics_event)",
        cutoff);
  }
}
