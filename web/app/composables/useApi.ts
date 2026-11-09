/** 统一同源请求头与Cookie策略；错误消息只使用后端已脱敏的message，不显示原始请求/堆栈。 */
export function useApi() {
  const { staffApi } = useStaffPaths();
  async function request<T = any>(url: string, options: any = {}): Promise<T> {
    return (await $fetch<T>(staffApi(url), {
      ...options,
      credentials: "same-origin",
      headers: { "X-AIWYW-Request": "1", ...options.headers },
    })) as T;
  }
  function message(error: any): string {
    return error?.data?.message || "连接未完成，请稍后重试";
  }
  return { request, message };
}
