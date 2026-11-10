package com.aiwyw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 维护专栏原有有序引用。两种写入口在同一事务内串行化，避免并发覆盖排序。 */
final class SeriesLinks {
  private SeriesLinks() {}

  static void lock(JdbcTemplate db) {
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
  }

  private static String referenceTerm(String ref) {
    if (ref == null || !ref.matches("(?:content:)?" + Ids.REGEX))
      throw new Api.Failure(400, "专栏关联对象格式不正确");
    // 校验后只含字母数字、横线和冒号；引号用于匹配 JSON 中的完整引用。
    return "%" + '"' + ref + '"' + "%";
  }

  static List<String> current(JdbcTemplate db, String ref) {
    return db.queryForList("SELECT id FROM editorial_series WHERE refs_json LIKE ? ORDER BY id",
        String.class, referenceTerm(ref));
  }

  static void replace(JdbcTemplate db, ObjectMapper json, String ref, List<String> desired, String actor) {
    // 旧调用省略字段时保留当前关系；空数组表示主动清空。
    if (desired == null) return;
    var selected = new HashSet<>(desired);
    if (desired.size() > 300 || selected.size() != desired.size()
        || selected.stream().anyMatch(id -> id == null || !id.matches(Ids.REGEX)))
      throw new Api.Failure(400, "专栏最多选择300项且不能重复");
    lock(db);
    var args = new ArrayList<Object>();
    args.add(referenceTerm(ref));
    args.addAll(desired);
    var rows = db.queryForList("SELECT id,refs_json FROM editorial_series WHERE refs_json LIKE ?"
        + (desired.isEmpty() ? "" : " OR id IN (" + SectionSelection.marks(desired.size()) + ")")
        + " ORDER BY id FOR UPDATE", args.toArray());
    var found = new HashSet<String>();
    for (var row : rows) found.add((String) row.get("id"));
    if (!found.containsAll(selected)) throw new Api.Failure(409, "部分专栏已删除，请重新选择");
    for (var row : rows) {
      String id = (String) row.get("id");
      try {
        var refs = json.readValue((String) row.get("refs_json"), new TypeReference<List<String>>() {});
        if (selected.contains(id)) {
          if (refs.contains(ref)) continue;
          if (refs.size() >= 300) throw new Api.Failure(409, "所选专栏已有300项，请调整选品");
          refs.add(ref);
        } else if (!refs.remove(ref)) continue;
        db.update("UPDATE editorial_series SET refs_json=?,revision=revision+1 WHERE id=?",
            json.writeValueAsString(refs), id);
        AuditLog.write(db, "catalog_audit", actor, id, "PUBLISH_SERIES_LINK", "更新发布内容的专栏关联");
      } catch (JsonProcessingException invalid) {
        throw new IllegalStateException("Invalid series references", invalid);
      }
    }
  }
}
