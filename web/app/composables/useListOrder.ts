/** 表头第一次降序、第二次升序；换列/方向后回第一页，由服务端先排序再分页。 */
export function useListOrder(
  reload: () => unknown,
  page: Ref<number>,
  created = "created_at",
) {
  const sort = ref(created),
    direction = ref("descending");
  function change(value: { prop: string | null; order: string | null }) {
    sort.value = value.prop || created;
    direction.value = value.order || "descending";
    page.value = 1;
    reload();
  }
  return {
    sort,
    direction,
    change,
    defaultSort: { prop: created, order: "descending" as const },
  };
}
