package com.aiwyw;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 独立维护有序引用；同一内容可加入多个目录，公开接口只解析当前发布快照。 */
@RestController
class Series {
  record Input(
      String slug,
      String titleZh,
      String titleEn,
      String summaryZh,
      String summaryEn,
      boolean published,
      List<String> refs,
      long revision) {}

  record Row(
      String id,
      String slug,
      String titleZh,
      String titleEn,
      String summaryZh,
      String summaryEn,
      boolean published,
      List<String> refs,
      long revision,
      long createdAt) {}

  record Card(String slug, String titleZh, String titleEn, String summaryZh, String summaryEn) {}

  record Detail(Card series, List<Map<String, Object>> items) {}

  private static final List<String> EMBA =
      List.of(
          "emba-writing-01-argument-analysis", "emba-writing-02-persuasive-essay",
          "emba-writing-03-english-practical-writing", "emba-writing-04-english-chart-essay");
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final SectionSelection selection;
  private final Auth auth;

  Series(JdbcTemplate db, ObjectMapper json, SectionSelection selection, Auth auth) {
    this.db = db;
    this.json = json;
    this.selection = selection;
    this.auth = auth;
  }

  static void schema(JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS editorial_series (
          id VARCHAR(36) PRIMARY KEY COMMENT '专栏唯一标识',
          slug VARCHAR(80) NOT NULL UNIQUE COMMENT '专栏固定公开地址',
          title_zh VARCHAR(120) NOT NULL COMMENT '中文标题',
          title_en VARCHAR(120) NOT NULL COMMENT '英文标题，空时使用中文',
          summary_zh VARCHAR(500) NOT NULL COMMENT '中文简介',
          summary_en VARCHAR(500) NOT NULL COMMENT '英文简介',
          published BOOLEAN NOT NULL COMMENT '是否公开',
          refs_json LONGTEXT NOT NULL COMMENT '有序引用，应用UUID及content前缀的内容UUID',
          revision BIGINT NOT NULL COMMENT '并发编辑版本',
          created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
        ) COMMENT='独立系列目录，可复用已发布文章与应用'
        """);
    String marker = "V015_EMBA_SERIES";
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version=?", Integer.class, marker)
        > 0) return;
    var ids = new ArrayList<String>();
    for (String slug : EMBA) {
      var rows =
          db.queryForList(
              "SELECT id FROM content_document WHERE slug=? AND state='PUBLISHED'",
              String.class,
              slug);
      if (rows.isEmpty()) return;
      ids.add("content:" + rows.get(0));
    }
    long now = System.currentTimeMillis();
    if (db.queryForObject(
            "SELECT COUNT(*) FROM editorial_series WHERE slug='emba-prep'", Integer.class)
        == 0)
      db.update(
          "INSERT INTO editorial_series VALUES(?,?,?,?,?,?,TRUE,?,1,?)",
          UUID.randomUUID().toString(),
          "emba-prep",
          "EMBA 备考",
          "EMBA Exam Preparation",
          "从管综论证有效性分析到英语二图表作文，按顺序练习四种写作题型。",
          "Four writing lessons for the management aptitude and English II exams.",
          "[\"" + String.join("\",\"", ids) + "\"]",
          now);
    db.update("INSERT INTO schema_migration VALUES(?,?,?)", marker, "关联现有 EMBA 备考文章", now);
  }

  private Row row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
    try {
      return new Row(
          rs.getString("id"),
          rs.getString("slug"),
          rs.getString("title_zh"),
          rs.getString("title_en"),
          rs.getString("summary_zh"),
          rs.getString("summary_en"),
          rs.getBoolean("published"),
          json.readValue(rs.getString("refs_json"), new TypeReference<List<String>>() {}),
          rs.getLong("revision"),
          rs.getLong("created_at"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new java.sql.SQLException("Invalid series references", e);
    }
  }

  @GetMapping("/api/public/series")
  List<Card> cards() {
    return db.query(
        "SELECT slug,title_zh,title_en,summary_zh,summary_en FROM editorial_series WHERE"
            + " published=TRUE ORDER BY created_at DESC,id",
        (r, i) ->
            new Card(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
  }

  @GetMapping("/api/public/series/{slug}")
  Detail detail(@PathVariable String slug) {
    var r =
        db
            .query(
                "SELECT * FROM editorial_series WHERE slug=? AND published=TRUE", this::row, slug)
            .stream()
            .findFirst()
            .orElseThrow(() -> new Api.Failure(404, "专栏不存在"));
    return new Detail(
        new Card(r.slug(), r.titleZh(), r.titleEn(), r.summaryZh(), r.summaryEn()),
        selection.publicItems(r.refs()));
  }

  @GetMapping("/api/admin/series")
  List<Row> list(HttpServletRequest r) {
    auth.permission(r, "series");
    return db.query("SELECT * FROM editorial_series ORDER BY created_at DESC,id", this::row);
  }

  @GetMapping("/api/admin/series/{id}")
  Row get(@PathVariable String id, HttpServletRequest r) {
    auth.permission(r, "series");
    return db.query("SELECT * FROM editorial_series WHERE id=?", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new Api.Failure(404, "专栏不存在"));
  }

  @PostMapping("/api/admin/series")
  @Transactional
  Map<String, String> create(@RequestBody Input input, HttpServletRequest r) {
    return Map.of("id", save(null, input, auth.permission(r, "series").id()));
  }

  @PutMapping("/api/admin/series/{id}")
  @Transactional
  Map<String, String> update(
      @PathVariable String id, @RequestBody Input input, HttpServletRequest r) {
    return Map.of("id", save(id, input, auth.permission(r, "series").id()));
  }

  private String save(String id, Input input, String actor) {
    String slug = MeteredApi.text(input.slug(), 80, true);
    if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new Api.Failure(400, "专栏地址仅允许小写字母、数字和短横线");
    String zh = MeteredApi.text(input.titleZh(), 120, true),
        en = MeteredApi.text(input.titleEn(), 120, false);
    String summary = MeteredApi.text(input.summaryZh(), 500, false),
        summaryEn = MeteredApi.text(input.summaryEn(), 500, false);
    SectionSelection.valid(input.refs());
    if (input.refs().stream().anyMatch(s -> s.startsWith("api:")))
      throw new Api.Failure(400, "专栏可添加文章与应用");
    SectionSelection.validate(db, input.refs());
    String key = id == null ? UUID.randomUUID().toString() : id;
    String refs;
    try {
      refs = json.writeValueAsString(input.refs());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    try {
      if (id == null)
        db.update(
            "INSERT INTO editorial_series VALUES(?,?,?,?,?,?,?,?,1,?)",
            key,
            slug,
            zh,
            en,
            summary,
            summaryEn,
            input.published(),
            refs,
            System.currentTimeMillis());
      else if (db.update(
              """
              UPDATE editorial_series SET slug=?,title_zh=?,title_en=?,summary_zh=?,summary_en=?,
                published=?,refs_json=?,revision=revision+1 WHERE id=? AND revision=?
              """,
              slug,
              zh,
              en,
              summary,
              summaryEn,
              input.published(),
              refs,
              key,
              input.revision())
          != 1) throw new Api.Failure(409, "专栏已被修改，请刷新后重试");
      AuditLog.write(db, "catalog_audit", actor, key, "SAVE_SERIES", "维护专栏");
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new Api.Failure(409, "该专栏地址已被使用");
    }
    return key;
  }

  @DeleteMapping("/api/admin/series/{id}")
  @Transactional
  Map<String, Boolean> remove(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest r) {
    var actor = auth.permission(r, "series");
    if (db.update("DELETE FROM editorial_series WHERE id=? AND revision=?", id, revision) != 1)
      throw new Api.Failure(409, "专栏已被修改，请刷新后重试");
    AuditLog.write(db, "catalog_audit", actor.id(), id, "DELETE_SERIES", "删除专栏目录");
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/series/selection")
  Map<String, Object> select(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "ALL") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    auth.permission(r, "series");
    return selection.page(q, kind, page, pageSize, false);
  }

  @PostMapping("/api/admin/series/selection/lookup")
  List<Map<String, Object>> lookup(
      @RequestBody SectionSelectionController.Lookup input, HttpServletRequest r) {
    auth.permission(r, "series");
    return selection.lookup(input.refs());
  }
}
