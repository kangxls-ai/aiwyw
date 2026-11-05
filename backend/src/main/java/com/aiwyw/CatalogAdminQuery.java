package com.aiwyw;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 后台在SQL层分页和组合筛选，禁止先取全部富文本再在浏览器假分页。 */
@Service
public class CatalogAdminQuery {
  public record Row(
      String id,
      String slug,
      String nameZh,
      String nameEn,
      String iconUrl,
      String version,
      String type,
      BigDecimal pricePoints,
      List<String> tags,
      String status,
      long revision,
      Long firstPublishedAt,
      Long lastPublishedAt,
      long updatedAt,
      long createdAt) {}

  public record Page(List<Row> items, long total, int page, int pageSize) {}

  private final Catalog catalog;
  private final JdbcTemplate db;

  public CatalogAdminQuery(Catalog catalog, JdbcTemplate db) {
    this.catalog = catalog;
    this.db = db;
  }

  public Page page(
      String q,
      String state,
      String from,
      String until,
      String sectionId,
      List<String> tags,
      int page,
      int size) {
    return page(q, state, from, until, sectionId, tags, page, size, "createdAt", "descending");
  }

  public Page page(
      String q,
      String state,
      String from,
      String until,
      String sectionId,
      List<String> tags,
      int page,
      int size,
      String sort,
      String direction) {
    String ordering =
        ListOrder.sql(
            sort,
            direction,
            Map.of(
                "createdAt",
                "p.created_at",
                "nameZh",
                "p.sort_name",
                "version",
                "p.sort_version",
                "pricePoints",
                "p.sort_price",
                "tags",
                "p.sort_tags",
                "status",
                "p.status",
                "lastPublishedAt",
                "p.last_published_at"),
            "createdAt",
            "p.id");
    catalog.initialize();
    if (q == null
        || q.length() > 100
        || page < 1
        || page > 1000000
        || size < 1
        || size > 100
        || tags.size() > 20) throw new Api.Failure(400, "查询或分页参数不正确");
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    if (!q.isBlank()) {
      where.append(" AND (p.search_text LIKE ? ESCAPE '!' OR LOWER(p.slug) LIKE ? ESCAPE '!')");
      String term =
          "%"
              + q.trim()
                  .toLowerCase(Locale.ROOT)
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
              + "%";
      args.add(term);
      args.add(term);
    }
    if (!state.isBlank()) {
      if (!Set.of("DRAFT", "PUBLISHED", "UNLISTED").contains(state))
        throw new Api.Failure(400, "上下架状态不正确");
      where.append(" AND p.status=?");
      args.add(state);
    }
    try {
      LocalDate start = from.isBlank() ? null : LocalDate.parse(from),
          end = until.isBlank() ? null : LocalDate.parse(until);
      if (start != null && end != null && start.isAfter(end))
        throw new Api.Failure(400, "开始日期不能晚于结束日期");
      if (start != null) {
        where.append(" AND p.last_published_at>=?");
        args.add(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
      }
      if (end != null) {
        where.append(" AND p.last_published_at<?");
        args.add(
            end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
      }
    } catch (java.time.DateTimeException invalid) {
      throw new Api.Failure(400, "发布时间格式不正确");
    }
    for (String tag : new LinkedHashSet<>(tags)) {
      if (tag.isBlank() || tag.length() > 80) throw new Api.Failure(400, "标签不正确");
      where.append(
          " AND EXISTS(SELECT 1 FROM catalog_product_tag t WHERE t.product_id=p.id AND t.tag=?)");
      args.add(tag);
    }
    if (!sectionId.isBlank()) {
      var section =
          catalog.sections().stream()
              .filter(s -> s.id().equals(sectionId))
              .findFirst()
              .orElseThrow(() -> new Api.Failure(400, "栏目不存在"));
      var c = section.config();
      if (c.mode().equals("MANUAL")) in(where, args, "p.id", c.productIds());
      else if (c.mode().equals("TAG")) {
        where.append(" AND EXISTS(SELECT 1 FROM catalog_product_tag st WHERE st.product_id=p.id");
        in(where, args, "st.tag", c.tags());
        where.append(")");
      }
    }
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_product p" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    var items =
        db.query(
            "SELECT p.* FROM catalog_product p"
                + where
                + " ORDER BY "
                + ordering
                + " LIMIT ? OFFSET ?",
            (r, n) -> {
              var p = catalog.mapDraft(r, n);
              return new Row(
                  p.id(),
                  p.slug(),
                  p.content().nameZh(),
                  p.content().nameEn(),
                  p.content().iconUrl(),
                  p.content().version(),
                  p.content().type(),
                  p.content().pricePoints(),
                  p.content().tags(),
                  p.status(),
                  p.revision(),
                  (Long) r.getObject("first_published_at"),
                  (Long) r.getObject("last_published_at"),
                  r.getLong("updated_at"),
                  r.getLong("created_at"));
            },
            args.toArray());
    return new Page(items, total, actual, size);
  }

  private void in(StringBuilder sql, List<Object> args, String column, List<String> values) {
    if (values.isEmpty()) {
      sql.append(" AND 1=0");
      return;
    }
    sql.append(" AND ")
        .append(column)
        .append(" IN (")
        .append(String.join(",", Collections.nCopies(values.size(), "?")))
        .append(")");
    args.addAll(values);
  }

  public Map<String, Object> facets() {
    catalog.initialize();
    return Map.of(
        "tags",
        db.queryForList("SELECT name FROM catalog_tag ORDER BY name", String.class),
        "sections",
        catalog.sections());
  }
}
