/** 发布前暂存专栏选择；保存草稿和旧客户端发布保持已有目录关系。 */
export function useSeriesAssignment(
  scope: "catalog" | "content",
  contentKind?: Ref<string>,
) {
  const { request } = useApi();
  const seriesIds = ref<string[]>([]);
  let baseline: string[] = [];
  async function refresh(id?: string | null) {
    const ids = id
      ? await request<string[]>(
          "/api/admin/" + scope + "/series-picker/memberships",
          {
            query: { id, contentKind: contentKind?.value },
          },
        )
      : [];
    baseline = [...ids];
    seriesIds.value = ids;
  }
  function changes() {
    const ids = seriesIds.value;
    return ids.length === baseline.length &&
      ids.every((id) => baseline.includes(id))
      ? undefined
      : [...ids];
  }
  return { seriesIds, refresh, changes };
}
