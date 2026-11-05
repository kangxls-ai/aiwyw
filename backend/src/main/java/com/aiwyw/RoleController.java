package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 菜单级角色管理，保存使用版本冲突保护；内置管理员保留恢复管理所需入口。 */
@RestController
public class RoleController {
  final JdbcTemplate db;
  final Auth auth;

  RoleController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/api/admin/role-options")
  List<Map<String, Object>> options(HttpServletRequest r) {
    auth.admin(r);
    return Commerce.safeRows(
        db.queryForList("SELECT * FROM access_role ORDER BY created_at DESC,id"));
  }

  @GetMapping("/api/admin/roles")
  Map<String, Object> list(HttpServletRequest r) {
    auth.admin(r);
    var rows =
        Commerce.safeRows(db.queryForList("SELECT * FROM access_role ORDER BY created_at DESC,id"));
    for (var row : rows)
      row.put(
          "menus",
          Identity.normalizeMenus(
              db.queryForList(
                  "SELECT menu_id FROM access_role_menu WHERE role_id=?",
                  String.class,
                  row.get("id"))));
    return Map.of("items", rows, "menus", Identity.MENUS);
  }

  record Input(String name, List<String> menus, long revision) {}

  @Transactional
  @PutMapping("/api/admin/roles/{id}")
  Map<String, String> save(@PathVariable String id, @RequestBody Input b, HttpServletRequest r) {
    var actor = auth.admin(r);
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
    if (b.name() == null
        || b.name().isBlank()
        || b.name().length() > 80
        || b.menus() == null
        || !Identity.MENUS.keySet().containsAll(b.menus()))
      throw new Api.Failure(400, "角色名称或菜单不正确");
    if (id.equals("ADMIN")
        && !b.menus().containsAll(List.of("settings", "users", "roles", "security", "audit")))
      throw new Api.Failure(400, "管理员须保留配置、用户、角色、安全和审计入口");
    if (b.revision() == 0) {
      if (!id.matches("[a-f0-9-]{36}")) throw new Api.Failure(400, "新角色标识不正确");
      db.update(
          "INSERT INTO access_role VALUES(?,?,FALSE,1,?)",
          id,
          b.name().trim(),
          System.currentTimeMillis());
    } else if (db.update(
            "UPDATE access_role SET name=?,revision=revision+1 WHERE id=? AND revision=?",
            b.name().trim(),
            id,
            b.revision())
        != 1) throw new Api.Failure(409, "角色已变化，请刷新");
    db.update("DELETE FROM access_role_menu WHERE role_id=?", id);
    for (String menu : Identity.normalizeMenus(b.menus()))
      db.update("INSERT INTO access_role_menu VALUES(?,?)", id, menu);
    audit(actor.id(), id, "SAVE_ROLE");
    return Map.of("id", id);
  }

  @Transactional
  @DeleteMapping("/api/admin/roles/{id}")
  Map<String, Boolean> delete(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest r) {
    var actor = auth.admin(r);
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM access_user_role WHERE role_id=?", Integer.class, id)
        > 0) throw new Api.Failure(409, "请先解除用户的角色授权");
    if (db.update(
            "DELETE FROM access_role WHERE id=? AND revision=? AND builtin=FALSE", id, revision)
        != 1) throw new Api.Failure(409, "内置角色不可删除，或角色版本已变化");
    db.update("DELETE FROM access_role_menu WHERE role_id=?", id);
    audit(actor.id(), id, "DELETE_ROLE");
    return Map.of("success", true);
  }

  record Nickname(String nickname) {}

  @PutMapping("/api/member/nickname")
  Map<String, Boolean> nickname(@RequestBody Nickname b, HttpServletRequest r) {
    var user = auth.user(r, false);
    String name = b.nickname() == null ? "" : b.nickname().trim();
    if (name.isBlank() || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl))
      throw new Api.Failure(400, "昵称须为1至40字，不能含控制字符");
    db.update("UPDATE app_user SET nickname=? WHERE id=?", name, user.id());
    return Map.of("success", true);
  }

  private void audit(String actor, String id, String action) {
    db.update(
        "INSERT INTO finance_audit(id,actor_id,entity_id,action_name,detail,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor,
        id,
        action,
        "角色菜单变更，不含凭据",
        System.currentTimeMillis());
  }
}
