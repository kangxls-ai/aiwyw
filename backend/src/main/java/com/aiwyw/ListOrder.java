package com.aiwyw;

import java.util.Map;

/** 分页前排序：客户端只能传公开键，SQL表达式来自服务端白名单；相同值用稳定主键打破平局。 */
final class ListOrder {
  private ListOrder() {}

  static String sql(
      String key, String direction, Map<String, String> fields, String fallback, String id) {
    String actual = key == null || key.isBlank() ? fallback : key;
    String expression = fields.get(actual);
    if (expression == null || !java.util.Set.of("descending", "ascending").contains(direction))
      throw new Api.Failure(400, "排序字段或方向不正确");
    return expression + (direction.equals("ascending") ? " ASC" : " DESC") + "," + id + " DESC";
  }
}
