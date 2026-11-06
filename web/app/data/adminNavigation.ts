/** 菜单ID同时是页签稳定标识；文案可以调整，ID与route不要随名称修改。服务端独立鉴权。 */
export interface AdminMenuItem {
  id: string;
  label: string;
  icon: string;
  route: string;
  description: string;
}
export interface AdminMenuGroup {
  id: string;
  label: string;
  icon: string;
  items: AdminMenuItem[];
}
function item(
  id: string,
  label: string,
  icon: string,
  description: string,
  route?: string,
): AdminMenuItem {
  return {
    id,
    label,
    icon,
    description,
    route: route || `/admin/workspace?view=${id}`,
  };
}
export const adminMenuGroups: AdminMenuGroup[] = [
  {
    id: "workspace",
    label: "工作空间",
    icon: "chart",
    items: [
      item(
        "dashboard",
        "工作台概览",
        "chart",
        "从工作台了解当前站点与待办事项。",
        "/admin",
      ),
      item(
        "statistics",
        "运营统计",
        "chart",
        "分析来源、搜索、留存、商品/API收入和客服运营。",
      ),
    ],
  },
  {
    id: "products",
    label: "商品管理",
    icon: "shapes",
    items: [
      item(
        "applications",
        "应用管理",
        "shapes",
        "统一维护应用资料、版本与文件、发布快照和静态详情。",
      ),
      item(
        "licenses",
        "软件授权与换机",
        "lock",
        "维护授权设备策略与审核换机。",
      ),
      item("tags", "标签管理", "palette", "维护标签，用于前台搜索与筛选。"),
    ],
  },
  {
    id: "transactions",
    label: "交易管理",
    icon: "wallet",
    items: [
      item(
        "packages",
        "捐赠档位",
        "wallet",
        "维护捐赠档位价格、鼓励值和有效期。",
      ),
      item("orders", "兑换订单", "clipboard", "查询兑换和权益状态。"),
      item(
        "recharge",
        "捐赠核销",
        "wallet",
        "审核捐赠与异常到账，保持账目可追溯。",
      ),
      item(
        "points",
        "鼓励值流水",
        "convert",
        "查询捐赠、兑换、退款和过期批次记录。",
      ),
      item("refunds", "退款管理", "back", "审核退款申请，处理权益及鼓励值退回。"),
    ],
  },
  {
    id: "api",
    label: "API 服务",
    icon: "convert",
    items: [
      item("apis", "API 管理", "convert", "维护接口说明与权限。"),
      item("plans", "套餐与订阅", "calendar", "管理周期额度与套餐价格。"),
      item("keys", "Key 管理", "lock", "管理调用密钥与权限。"),
      item("usage", "调用统计", "chart", "查看调用量、错误及计费记录。"),
    ],
  },
  {
    id: "support",
    label: "客服与反馈",
    icon: "note",
    items: [
      item("conversations", "在线会话", "note", "接待咨询，抢单和转派会话。"),
      item("messages", "公开留言", "clipboard", "审核和维护公开留言。"),
      item("feedback", "私密反馈", "lock", "处理私密建议与问题。"),
    ],
  },
  {
    id: "content",
    label: "内容运营",
    icon: "note",
    items: [
      item(
        "articles",
        "文章与教程",
        "note",
        "维护工具教程、使用案例与运营文章。",
      ),
      item("faq", "FAQ 与案例", "clipboard", "组织常见问题与应用案例。"),
      item(
        "seo",
        "SEO 与多语言",
        "browser",
        "维护页面元数据、语言及索引信息。",
      ),
    ],
  },
  {
    id: "users",
    label: "用户与权限",
    icon: "lock",
    items: [
      item("users", "用户管理", "note", "查看和维护用户账户。"),
      item("roles", "角色与权限", "lock", "配置后台角色及功能权限。"),
    ],
  },
  {
    id: "system",
    label: "系统管理",
    icon: "shapes",
    items: [
      item(
        "sections",
        "首页栏目",
        "star",
        "编辑首页栏目、选品规则、顺序和显示数量。",
      ),
      item(
        "settings",
        "配置管理",
        "shapes",
        "在页面维护全部运营参数，无需编辑配置文件。",
        "/admin/settings",
      ),
      item("storage", "存储管理", "folder", "维护文件及对象存储连接。"),
      item(
        "audit",
        "操作记录",
        "clipboard",
        "查看配置变更与操作审计。",
        "/admin/audit",
      ),
      item(
        "security",
        "账户安全",
        "lock",
        "管理双重验证和登录安全。",
        "/admin/security",
      ),
    ],
  },
];
export const adminMenuItems = adminMenuGroups.flatMap((g) => g.items);
export function adminMenuItem(id: string) {
  return adminMenuItems.find((i) => i.id === id) || adminMenuItems[0]!;
}
export function adminItemForRoute(path: string, view?: string) {
  if (path === "/admin") return adminMenuItem("dashboard");
  return (
    adminMenuItems.find((i) => i.route === path) ||
    adminMenuItem(view || "dashboard")
  );
}
