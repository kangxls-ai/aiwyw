/** 各列表共享新增页签机制；只用内存保留草稿，保存后通知已打开列表刷新，不覆盖其他编辑页签。 */
export function useEntityEditing(
  props: { editor?: boolean; tabId?: string },
  type: string,
  reload: () => unknown,
) {
  const state = useManagementTabs();
  const versions = useState<Record<string, number>>(
    "management-data-updated",
    () => ({}),
  );
  const completed = useState<Record<string, boolean>>(
    "management-editor-completed",
    () => ({}),
  );
  const key = computed(() => props.tabId || type);
  watch(
    () => versions.value[type],
    () => {
      if (!props.editor) reload();
    },
  );
  function changed() {
    versions.value[type] = (versions.value[type] || 0) + 1;
  }
  async function saved(id: string, name: string, returnToList = false) {
    state.dirty.value[key.value] = false;
    versions.value[type] = (versions.value[type] || 0) + 1;
    if (props.editor) {
      completed.value[key.value] = true;
      await state.updateEntityEditor(key.value, id, name);
      if (returnToList) await state.open(type);
    }
  }
  return { ...state, key, saved, changed };
}
