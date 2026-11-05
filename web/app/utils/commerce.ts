/** 服务端金额/积分最小单位为字符串；展示用BigInt，避免大额账目浮点误差。 */
export function decimalUnits(value: unknown) {
  try {
    const n = BigInt(String(value ?? 0)),
      sign = n < 0n ? "-" : "",
      abs = n < 0n ? -n : n;
    return `${sign}${abs / 100n}.${String(abs % 100n).padStart(2, "0")}`;
  } catch {
    return "—";
  }
}
export function businessTime(value: unknown) {
  return value
    ? new Date(Number(value)).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
      })
    : "—";
}
export const commerceStates: Record<string, string> = {
  PENDING: "待处理",
  DECLARED: "待核查",
  CLOSED: "已关闭",
  REJECTED: "未通过",
  POSTED: "已入账",
  ACTIVE: "有效",
  REFUNDED: "已退款",
  APPROVED: "已退款",
  RECHARGE: "充值",
  PURCHASE: "兑换",
  REFUND: "退款",
  EXPIRE: "到期失效",
};

/** 后台快捷区间依据北京时间的日历日期构造，避免海外浏览器把今天选成北京时间昨天。 */
export function adminDateShortcuts() {
  return [7, 30, 90].map((days) => ({
    text: `最近${days}天`,
    value: () => {
      const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).formatToParts(new Date());
      const get = (type: string) =>
        Number(parts.find((p) => p.type === type)?.value);
      const end = new Date(get("year"), get("month") - 1, get("day")),
        start = new Date(end);
      start.setDate(end.getDate() - days + 1);
      return [start, end];
    },
  }));
}
