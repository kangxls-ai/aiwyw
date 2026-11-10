import { test, expect, type Page, type Locator } from "@playwright/test";
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { createHmac } from "node:crypto";
import QRCode from "qrcode";

/** Element Plus水合时表单组与输入框可能暂时共用标签；只定位真实控件，仍要求唯一且可用，不用first掩盖歧义。 */
function field(
  root: Page | Locator,
  name: string,
  options: { exact?: boolean } = {},
) {
  return root
    .getByLabel(name, options)
    .and(root.locator("input,textarea,select"));
}

// 本文件使用单个隔离安装按序运行；本用例在账户/钱包场景之后验证授权及布局。
function otp(secret: string) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0,
    value = 0;
  const decoded: number[] = [];
  for (const c of secret) {
    value = (value << 5) | alphabet.indexOf(c);
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      decoded.push((value >>> bits) & 255);
    }
  }
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hash = createHmac("sha1", Buffer.from(decoded))
    .update(counter)
    .digest();
  const offset = hash[19]! & 15;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(
    6,
    "0",
  );
}

const directory = () => process.env.AIWYW_E2E_DIR!;
const workspace = (page: Page) => page.locator("[data-workspace]:visible");
async function menu(page: Page, name: string) {
  await page
    .locator(".management-sidebar")
    .getByRole("button", { name, exact: true })
    .click();
}
async function upload(
  page: Page,
  name: string,
  file: { name: string; mimeType: string; buffer: Buffer },
) {
  const chooser = page.waitForEvent("filechooser");
  await page
    .locator("button")
    .filter({ hasText: new RegExp("^" + name + "$") })
    .click();
  await (await chooser).setFiles(file);
}
async function select(page: Page, name: string, label: string) {
  await page
    .locator(".el-select")
    .filter({ has: page.getByRole("combobox", { name, exact: true }) })
    .locator(".el-select__wrapper")
    .click();
  await page.getByRole("option", { name: label, exact: true }).click();
}
async function pick(page: Page, title: string, labels: string[]) {
  await page.getByRole("button", { name: title, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: title, exact: true });
  for (const label of labels) {
    await field(dialog, "筛选关键词", { exact: true }).fill(label);
    await dialog.getByRole("button", { name: "搜索", exact: true }).click();
    const row = dialog.getByRole("row").filter({
      has: page.getByRole("cell", { name: label, exact: true }),
    });
    await row.locator(".el-radio, .el-checkbox").click();
  }
  await dialog.getByRole("button", { name: "确认选择", exact: true }).click();
}
function staff(path: string) {
  return (
    "/" + readFileSync(join(directory(), "staff-entry"), "utf8").trim() + path
  );
}

async function configuration(page: Page, values: Record<string, string>) {
  const entries = await (
    await page.request.get(staff("/api/admin/settings"))
  ).json();
  const changes = Object.entries(values).map(([key, value]) => ({
    key,
    value,
    revision: entries.find((e: any) => e.definition.key === key).revision,
    clear: false,
  }));
  const result = await page.request.put(staff("/api/admin/settings"), {
    headers: { "X-AIWYW-Request": "1" },
    data: { changes },
  });
  expect(result.status()).toBe(200);
}
const content = (slug: string, name: string, price = 0) => ({
  slug,
  nameZh: name,
  nameEn: name,
  summaryZh: "完整端到端验证",
  summaryEn: "Browser verification",
  bodyZh: "<h2>工具使用说明</h2><p>已发布正文。</p>",
  bodyEn: "",
  tags: ["办公效率"],
  iconUrl: "",
  screenshots: [],
  type: "SOFTWARE",
  pricePoints: price,
  version: "1.0.0",
  deliveryMode: "DOWNLOAD",
  downloadUrl: "https://example.com/package.zip",
  revision: 0,
});
async function inboxLink(email: string, action: string) {
  let url = "";
  await expect
    .poll(
      () => {
        const letters = readdirSync(join(directory(), "inbox"))
          .map((file) =>
            JSON.parse(readFileSync(join(directory(), "inbox", file), "utf8")),
          )
          .filter(
            (mail) =>
              mail.to.includes(email) && mail.body.includes("action=" + action),
          )
          .sort((a, b) => b.receivedAt - a.receivedAt);
        url = letters[0]?.body.match(/https?:\/\/[^\s]+/)?.[0] || "";
        return !!url;
      },
      { timeout: 45000, intervals: [500, 1000] },
    )
    .toBe(true);
  return url;
}

test("install, MFA, Element Plus forms, tags, storage, tabs and pagination", async ({
  page,
}) => {
  test.setTimeout(180000);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/install");
  await field(page, "一次性安装码").fill(
    readFileSync(join(directory(), "install.token"), "utf8").trim(),
  );
  await field(page, "邮箱", { exact: true }).fill("browser-admin@example.com");
  await field(page, "密码", { exact: true }).fill(
    "browser-test-admin-password-9182",
  );
  await page
    .getByRole("button", { name: "测试数据库连接", exact: true })
    .click();
  await expect(page.getByText("连接测试成功")).toBeVisible();
  await page.getByRole("button", { name: /完成安装/ }).click();
  await expect(page.getByRole("heading", { name: "安装已完成" })).toBeVisible();
  expect((await page.request.get("/login")).status()).toBe(404);
  expect((await page.request.get("/admin/settings")).status()).toBe(404);
  expect(
    (
      await page.request.get("/api/admin/settings", {
        headers: { "X-AIWYW-Staff-Entry": staff("").slice(1) },
      })
    ).status(),
  ).toBe(404);
  expect(
    (
      await page.request.get("/00000000000040008000000000000000/login")
    ).status(),
  ).toBe(404);
  expect(
    (
      await page.request.get("http://127.0.0.1:18080/api/admin/settings")
    ).status(),
  ).toBe(404);
  expect(
    (
      await page.request.get("/AAAAAAAAAAAA4AAA8AAAAAAAAAAAAAAA/login")
    ).status(),
  ).toBe(404);
  const directLogin = await page.request.post("/api/auth/login", {
    headers: {
      "X-AIWYW-Request": "1",
      "X-AIWYW-Staff-Entry": staff("").slice(1),
    },
    data: {
      email: "browser-admin@example.com",
      password: "browser-test-admin-password-9182",
      code: "",
    },
  });
  expect(directLogin.status()).toBe(401);
  await page.getByRole("link", { name: /前往登录/ }).click();
  await field(page, "邮箱", { exact: true }).fill("browser-admin@example.com");
  await field(page, "密码", { exact: true }).fill(
    "browser-test-admin-password-9182",
  );
  await page.getByRole("button", { name: /登录后台/ }).click();
  await expect(page).toHaveURL(/admin\/security$/);
  expect((await page.request.get(staff("/api/admin/settings"))).status()).toBe(
    403,
  );
  const blockedPublish = await page.request.post(
    staff("/api/admin/content/blocked/publish"),
    {
      headers: { "X-AIWYW-Request": "1" },
      data: { revision: 1 },
    },
  );
  expect(blockedPublish.status()).toBe(403);
  expect((await page.request.get("/api/auth/session")).status()).toBe(401);
  await page.getByRole("button", { name: "开始绑定" }).click();
  const secret = (await page.locator(".secret-code").innerText()).trim();
  await field(page, "6位动态码").fill(otp(secret));
  await page.getByRole("button", { name: "确认绑定" }).click();
  await expect(
    page.getByRole("heading", { name: "双重验证已启用" }),
  ).toBeVisible();
  await page.getByRole("link", { name: /进入配置管理/ }).click();
  await page
    .context()
    .storageState({ path: join(directory(), "admin-state.json") });
  const publicHeaders = {
    "Accept-Language": "zh",
    Cookie: "aiwyw_language=zh",
  };
  const guest = await page.context().browser()!.newPage();
  await guest
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  await guest.goto("/account-access?action=register");
  await expect(
    guest.getByRole("heading", { name: "登录账户", exact: true }),
  ).toBeVisible();
  await expect(
    guest.getByRole("button", { name: "注册", exact: true }),
  ).toHaveCount(0);
  const denied = await guest.request.post("/api/auth/register", {
    headers: { "X-AIWYW-Request": "1" },
    data: {
      email: "closed-registration@example.com",
      password: "closed-registration-password",
    },
  });
  expect(denied.status()).toBe(403);
  expect(
    (
      await guest.request.post(staff("/api/admin/site/publish"), {
        headers: { "X-AIWYW-Request": "1" },
      })
    ).status(),
  ).toBe(401);
  const initial = await guest.request.get("/", { headers: publicHeaders });
  expect(initial.headers()["x-aiwyw-static"]).toMatch(/published|hit/);
  expect(await initial.text()).toContain("以技术为舟，向未来远航");
  await guest.close();
  await field(page, "网站名称", { exact: true }).fill("微云湾 浏览器验证");
  await page.getByRole("button", { name: /保存修改/ }).click();
  await expect(page.locator(".alert[role=status]:visible")).toHaveText(
    "配置已保存，首页与用户登录页已发布",
  );
  for (const path of ["/", "/account-access"]) {
    const snapshot = await page.request.get(path, { headers: publicHeaders });
    expect(snapshot.headers()["x-aiwyw-static"]).toBe("hit");
    expect(await snapshot.text()).toContain("微云湾 浏览器验证");
  }
  const tokenPage = await page.request.get(
    "/account-access?action=verify&token=browser-only",
  );
  expect(tokenPage.headers()["x-aiwyw-static"]).toBeUndefined();
  const iconUpload = await page.request.post(
    staff("/api/admin/assets/filing"),
    {
      headers: { "X-AIWYW-Request": "1" },
      multipart: {
        file: {
          name: "filing-icon.png",
          mimeType: "image/png",
          buffer: await QRCode.toBuffer("filing icon fixture"),
        },
      },
    },
  );
  expect(iconUpload.status()).toBe(200);
  const icon = (await iconUpload.json()).url;
  expect((await page.request.get(icon)).status()).toBe(404);
  await configuration(page, {
    "account.registration_open": "true",
    "site.filing": "示例ICP备12345678号-1",
    "site.security_filing": "示例公网安备12345678901234号",
    "site.security_filing_icon": icon,
  });
  const publishedLogin = await page.request.get(staff("/login"), {
    headers: publicHeaders,
  });
  expect(publishedLogin.headers()["cache-control"]).toContain("no-store");
  expect(publishedLogin.headers()["referrer-policy"]).toBe("no-referrer");
  expect(await publishedLogin.text()).toContain(
    "/account-access?action=register",
  );
  const publishedHome = await page.request.get("/", { headers: publicHeaders });
  const publishedHomeHtml = await publishedHome.text();
  expect(publishedHomeHtml).toContain("https://beian.miit.gov.cn/");
  expect(publishedHomeHtml).toContain(
    "https://beian.mps.gov.cn/#/query/webSearch?code=12345678901234",
  );
  expect(publishedHomeHtml).toContain(icon);
  expect((await page.request.get(icon)).status()).toBe(200);
  expect(publishedHomeHtml).toContain("示例公网安备12345678901234号");
  await configuration(page, {
    "site.filing": "",
    "site.security_filing": "",
    "site.security_filing_icon": "",
  });
  expect((await page.request.get(icon)).status()).toBe(404);
  await field(page, "网站名称", { exact: true }).fill("尚未保存");
  await page
    .getByRole("button", { name: "关闭配置管理页签", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await expect(field(page, "网站名称", { exact: true })).toHaveValue(
    "尚未保存",
  );
  await field(page, "网站名称", { exact: true }).fill("微云湾 浏览器验证");
  await page
    .locator(".settings-groups")
    .getByRole("button", { name: "邮件服务", exact: true })
    .click();
  await field(page, "SMTP密码或授权码", { exact: true }).fill(
    "isolated-mail-password",
  );
  await page.getByRole("button", { name: /保存修改/ }).click();
  await expect(field(page, "SMTP密码或授权码", { exact: true })).toHaveValue(
    "",
  );
  expect(
    await (await page.request.get(staff("/api/admin/settings"))).text(),
  ).not.toContain("isolated-mail-password");
  const port = JSON.parse(
    readFileSync(join(directory(), "inbox.json"), "utf8"),
  ).port;
  await configuration(page, {
    "mail.enabled": "true",
    "mail.host": "127.0.0.1",
    "mail.port": String(port),
    "mail.username": "isolated-inbox",
    "mail.from": "sender@example.com",
    "mail.security": "TLS",
    "account.link_origin": "http://127.0.0.1:3000",
  });
  const smtpCheck = await page.request.post(staff("/api/admin/mail/check"), {
    headers: { "X-AIWYW-Request": "1" },
    data: { protocol: "SMTP" },
  });
  expect(smtpCheck.status()).toBe(200);
  expect((await smtpCheck.json()).success).toBe(true);
  await page.getByRole("button", { name: "生产上线检查", exact: true }).click();
  const readinessDialog = page.getByRole("dialog", {
    name: "生产上线检查",
    exact: true,
  });
  await expect(
    readinessDialog.getByText("运行模式", { exact: true }),
  ).toBeVisible();
  await expect(
    readinessDialog.getByText("管理及财务双因素策略", { exact: true }),
  ).toBeVisible();
  await readinessDialog
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  const png = await QRCode.toBuffer("AIWYW ISOLATED FILE FIXTURE");
  await page
    .locator(".settings-groups")
    .getByRole("button", { name: "捐赠收款", exact: true })
    .click();
  await upload(page, "选择支付宝付款码", {
    name: "alipay-test.png",
    mimeType: "image/png",
    buffer: png,
  });
  await page.getByRole("button", { name: /保存修改/ }).click();
  await expect(page.getByAltText("支付宝付款码")).toBeVisible();
  await menu(page, "应用管理");
  await page.getByRole("button", { name: "新增应用", exact: true }).click();
  const editor = page.locator(".application-editor:visible");
  await field(editor, "应用名称", { exact: true }).fill("浏览器工具");
  await field(editor, "英文名称", { exact: true }).fill("Browser utility");
  await field(editor, "公开地址", { exact: true }).fill("browser-utility");
  await field(editor, "版本", { exact: true }).fill("1.0.0");
  await field(editor, "简介", { exact: true }).fill("真实目录端到端验证");
  const rich = page.getByRole("textbox", { name: "图文介绍", exact: true });
  await expect(rich).toBeVisible();
  await rich.evaluate((el) => {
    el.innerHTML = "<h2>工具使用说明</h2><p>这里是已发布正文。</p>";
    el.dispatchEvent(new Event("input", { bubbles: true }));
    const range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(false);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    (window as any)
      .jQuery(el.closest(".note-editor")!.previousElementSibling)
      .summernote("editor.setLastRange");
  });
  await editor.getByRole("button", { name: "应用标签", exact: true }).click();
  const tagPicker = page.getByRole("dialog", { name: "应用标签", exact: true });
  for (const tag of ["办公效率", "文本处理"]) {
    await field(tagPicker, "新标签名称", { exact: true }).fill(tag);
    await tagPicker
      .getByRole("button", { name: "加入新标签", exact: true })
      .click();
  }
  await tagPicker
    .getByRole("button", { name: "确认选择", exact: true })
    .click();
  await upload(page, "上传应用图标", {
    name: "utility-icon.png",
    mimeType: "image/png",
    buffer: png,
  });
  await expect(page.getByAltText("应用图标", { exact: true })).toBeVisible();
  await expect(editor.getByText("添加截图", { exact: true })).toHaveCount(0);
  await upload(page, "上传下载内容", {
    name: "utility.zip",
    mimeType: "application/zip",
    buffer: Buffer.from("isolated-browser-download"),
  });
  await expect(editor.getByText("utility.zip", { exact: true })).toBeVisible();
  await page
    .locator(".summernote-field")
    .first()
    .getByRole("button", { name: "从文件库插图", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "选择 utility-icon.png", exact: true })
    .click();
  await expect(rich.locator("img")).toHaveCount(1);
  await page.getByRole("button", { name: "保存并发布", exact: true }).click();
  await expect(page.locator(".alert[role=status]:visible")).toHaveText(
    "发布成功，静态详情已生成并生效",
  );
  await menu(page, "首页栏目");
  await page
    .getByRole("row")
    .filter({ hasText: "站长推荐" })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await field(page, "栏目名称", { exact: true }).fill("精选工具");
  await page.getByRole("button", { name: "选择内容", exact: true }).click();
  const picker = page.getByRole("dialog", { name: "选择栏目内容" });
  await field(picker, "选品搜索", { exact: true }).fill("浏览器工具");
  await picker.getByRole("button", { name: "搜索", exact: true }).click();
  await picker
    .getByRole("row")
    .filter({ hasText: "浏览器工具" })
    .locator(".el-checkbox")
    .click();
  await picker.getByRole("button", { name: /^确认选择/ }).click();
  await page.getByRole("button", { name: "保存修改", exact: true }).click();
  await expect(page.locator(".alert[role=status]:visible")).toHaveText(
    "栏目已保存并生效",
  );
  await menu(page, "标签管理");
  await expect(
    page.getByRole("heading", { name: "标签管理", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: "办公效率" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "新增标签", exact: true }).click();
  await field(page.locator("[data-workspace]:visible"), "标签名称", {
    exact: true,
  }).fill("未使用标签");
  await page
    .locator("[data-workspace]:visible")
    .getByRole("button", { name: "保存标签", exact: true })
    .click();
  const unused = page.getByRole("row").filter({ hasText: "未使用标签" });
  await expect(unused).toBeVisible();
  await unused.getByRole("button", { name: "删除", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "删除", exact: true })
    .click();
  await expect(unused).toHaveCount(0);
  await page.screenshot({
    path: "test-results/element-tags.png",
    fullPage: true,
  });
  await menu(page, "应用管理");
  await page.getByPlaceholder("开始日期", { exact: true }).click();
  await page.getByRole("button", { name: "最近7天", exact: true }).click();
  await select(page, "上下架状态", "上架");
  const dateFiltered = page.waitForResponse(
    (r) =>
      r.url().includes("/api/admin/catalog/products/page?") &&
      r.url().includes("from=") &&
      r.status() === 200,
  );
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await dateFiltered;
  await expect(
    page.getByRole("row").filter({ hasText: "浏览器工具" }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/element-applications.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "重置", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: "浏览器工具" })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await expect(
    page.getByRole("tab", { name: "编辑：浏览器工具", exact: true }),
  ).toHaveCount(1);
  await page.getByRole("tab", { name: "应用管理", exact: true }).click();
  await page.getByRole("button", { name: "新增应用", exact: true }).click();
  await field(page.locator(".application-editor:visible"), "应用名称", {
    exact: true,
  }).fill("并行草稿");
  await field(page.locator(".application-editor:visible"), "公开地址", {
    exact: true,
  }).fill("parallel-draft");
  await page
    .getByRole("tab", { name: "编辑：浏览器工具", exact: true })
    .click();
  await expect(
    field(page.locator(".application-editor:visible"), "应用名称", {
      exact: true,
    }),
  ).toHaveValue("浏览器工具");
  await page
    .getByRole("tab", { name: "新增应用", exact: true })
    .click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "关闭其他页签", exact: true })
    .click();
  await page
    .getByRole("tab", { name: "新增应用", exact: true })
    .click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "关闭全部页签", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await expect(
    field(page.locator(".application-editor:visible"), "应用名称", {
      exact: true,
    }),
  ).toHaveValue("并行草稿");
  await page
    .getByRole("tab", { name: "新增应用", exact: true })
    .click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "关闭全部页签", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存并关闭", exact: true })
    .click();
  await expect(page.getByRole("tab")).toHaveCount(1);
  await menu(page, "存储管理");
  await expect(
    page
      .getByRole("row")
      .filter({ hasText: "utility.zip" })
      .getByRole("button", { name: "删除", exact: true }),
  ).toBeDisabled();
  await page
    .locator(".el-radio-button")
    .filter({ hasText: "存储配置" })
    .click();
  await page
    .getByRole("button", { name: "检查已保存连接", exact: true })
    .click();
  await expect(page.locator(".alert[role=status]:visible")).toHaveText(
    "存储连接检查通过",
  );
  await menu(page, "应用管理");
  for (let i = 0; i < 11; i++) {
    expect(
      (
        await page.request.post(staff("/api/admin/catalog/products"), {
          headers: { "X-AIWYW-Request": "1" },
          data: content("paging-" + i, "分页应用" + i),
        })
      ).status(),
    ).toBe(200);
  }
  await field(page, "名称或关键字", { exact: true }).fill("分页应用");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await page.locator(".el-pagination:visible .el-select__wrapper").click();
  await page.getByRole("option", { name: "10条/页", exact: true }).click();
  await expect(
    page.locator(".el-table:visible .el-table__body tbody tr"),
  ).toHaveCount(10);
  await page.locator(".el-pagination:visible .btn-next").click();
  await expect(
    page.locator(".el-table:visible .el-table__body tbody tr"),
  ).toHaveCount(1);
  await page
    .getByRole("tab", { name: "应用管理", exact: true })
    .click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "关闭左侧页签", exact: true })
    .click();
  await expect(
    page.getByRole("tab", { name: "存储管理", exact: true }),
  ).toHaveCount(0);
  await menu(page, "存储管理");
  await page
    .getByRole("tab", { name: "应用管理", exact: true })
    .click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "关闭右侧页签", exact: true })
    .click();
  await expect(
    page.getByRole("tab", { name: "存储管理", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "新增应用", exact: true }).click();
  await field(page.locator(".application-editor:visible"), "应用名称", {
    exact: true,
  }).fill("保存失败仍保留");
  await page
    .getByRole("button", { name: "关闭新增应用页签", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存并关闭", exact: true })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "尚未保存",
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "放弃修改并关闭", exact: true })
    .click();
  await page
    .context()
    .storageState({ path: join(directory(), "admin-state.json") });
  expect(errors).toEqual([]);
});

test("static catalog, real download, language and mobile", async ({
  page,
  browser,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByRole("button", { name: "中文", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "精选工具", exact: true }),
  ).toBeVisible();
  const checkColumns = async (width: number, columns: number) => {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page
          .locator(".directory-card-grid")
          .first()
          .evaluate(
            (grid) =>
              getComputedStyle(grid).gridTemplateColumns.split(" ").length,
          ),
      )
      .toBe(columns);
  };
  await checkColumns(1079, 2);
  await checkColumns(1080, 3);
  await page.getByRole("link", { name: "更多", exact: true }).first().click();
  await expect(page.locator(".directory-pager")).toContainText("100");
  await page.locator(".directory-pager .el-select__wrapper").click();
  await page.getByRole("option", { name: "40条/页", exact: true }).click();
  await expect(page).toHaveURL(/pageSize=40/);
  await pick(page, "标签筛选", ["办公效率"]);
  await page.reload();
  await expect(page.locator(".directory-entry-card")).toHaveCount(1);
  await checkColumns(1080, 3);
  await expect(page.locator(".directory-entry-card")).toHaveCSS(
    "height",
    "220px",
  );
  await checkColumns(768, 2);
  await checkColumns(767, 1);
  await checkColumns(390, 1);
  const mobileCard = page.locator(".directory-entry-card");
  const stats = await mobileCard.locator(".entry-stats").boundingBox();
  const actions = await mobileCard.locator(".entry-actions").boundingBox();
  expect(actions!.y).toBeGreaterThanOrEqual(stats!.y + stats!.height);
  const [downloadButton, detailButton] = await Promise.all(
    [
      mobileCard.locator(".entry-actions button"),
      mobileCard.locator(".entry-actions a"),
    ].map((action) => action.boundingBox()),
  );
  expect(Math.abs(downloadButton!.width - detailButton!.width)).toBeLessThan(1);
  const popup = page.waitForEvent("popup");
  await page.getByRole("link", { name: "查看详情", exact: true }).click();
  const detail = await popup;
  await expect(
    detail.getByRole("heading", { name: "工具使用说明", exact: true }),
  ).toBeVisible();
  await expect(detail.locator(".body .zh img")).toHaveCount(1);
  await expect(detail.locator(".body .zh img")).toHaveAttribute(
    "src",
    /^\/api\/public\/media\//,
  );
  expect(await detail.locator("body").innerHTML()).not.toContain(
    staff("").slice(1),
  );
  await expect(detail.locator("section.screenshots")).toHaveCount(0);
  await expect(detail.locator(".public-site-header")).toBeVisible();
  await expect(detail.locator(".public-breadcrumb")).toContainText("首页");
  const download = detail.waitForEvent("download");
  await detail.locator("#download").click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("utility.zip");
  expect(readFileSync((await file.path())!, "utf8")).toBe(
    "isolated-browser-download",
  );
  await detail.close();
  const noJs = await browser.newContext({ javaScriptEnabled: false });
  const reader = await noJs.newPage();
  await reader.goto("http://127.0.0.1:3000/apps/browser-utility");
  await expect(
    reader.getByRole("heading", { name: "工具使用说明", exact: true }),
  ).toBeVisible();
  await noJs.close();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
});

test("TLS verification mail, simulated recharge, redemption, delivery and full refund", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const adminContext = await browser.newContext({
      storageState: join(directory(), "admin-state.json"),
    }),
    admin = await adminContext.newPage();
  const context = await browser.newContext();
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const member = await context.newPage();
  const errors: string[] = [];
  member.on("pageerror", (e) => errors.push(e.message));
  admin.on("pageerror", (e) => errors.push(e.message));
  await member.goto("/account-access?action=register");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill("member-password-123456");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member.getByText(/验证邮件将发送/)).toBeVisible();
  const verify = await inboxLink("member@example.com", "verify");
  await member.goto(verify);
  await member
    .getByRole("button", { name: "确认验证邮箱", exact: true })
    .click();
  await expect(member).toHaveURL(/account-access\?action=login/);
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill("member-password-123456");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member.getByRole("button", { name: "新增捐赠", exact: true }).click();
  await member
    .getByRole("button", { name: "创建捐赠订单", exact: true })
    .click();
  await expect(
    member.getByRole("dialog", { name: "模拟捐赠订单" }),
  ).toBeVisible();
  const paymentMemo = (await member
    .locator(".payment-memo strong")
    .textContent())!;
  expect(paymentMemo).toMatch(/^\d{4}$/);
  await member.getByRole("button", { name: "我已付款", exact: true }).click();
  await expect(member.getByText(/付款声明已提交/)).toBeVisible();
  const memberNickname = (
    await (await member.request.get("/api/member/profile")).json()
  ).user.nickname;
  await admin.goto(staff("/admin/workspace?view=recharge"));
  await expect(
    admin
      .locator(".el-table:visible")
      .getByRole("row")
      .filter({ hasText: memberNickname }),
  ).toBeVisible();
  await admin
    .getByRole("row")
    .filter({ hasText: memberNickname })
    .getByRole("button", { name: "审核", exact: true })
    .click();
  await expect(admin.locator(".payment-memo-code")).toHaveText(paymentMemo);
  await field(admin, "逐单处理原因", { exact: true }).fill("模拟捐赠核实通过");
  await admin.getByRole("button", { name: "确认处理", exact: true }).click();
  await expect(
    admin
      .locator(".el-table:visible")
      .getByRole("row")
      .filter({ hasText: memberNickname }),
  ).toContainText("已入账");
  const body = {
    ...content("paid-utility", "付费浏览器工具", 25),
    downloadUrl: (
      await (
        await admin.request.get(staff("/api/admin/catalog/products"))
      ).json()
    ).find((p: any) => p.slug === "browser-utility").content.downloadUrl,
  };
  const created = await admin.request.post(
    staff("/api/admin/catalog/products"),
    {
      headers: { "X-AIWYW-Request": "1" },
      data: body,
    },
  );
  expect(created.status()).toBe(200);
  const id = (await created.json()).id;
  const draft = await (
    await admin.request.get(staff("/api/admin/catalog/products/") + id)
  ).json();
  expect(
    (
      await admin.request.post(
        staff("/api/admin/catalog/products/") + id + "/publish",
        {
          headers: { "X-AIWYW-Request": "1" },
          data: { revision: draft.revision },
        },
      )
    ).status(),
  ).toBe(200);
  await member.goto("/apps/paid-utility");
  await member.locator("#download").click();
  await expect(member).toHaveURL(/checkout\?slug=paid-utility/);
  await member.getByRole("button", { name: "确认兑换", exact: true }).click();
  await expect(member).toHaveURL(/account\?resource=/);
  await member.getByRole("tab", { name: "我的资源", exact: true }).click();
  const row = member.getByRole("row").filter({ hasText: "付费浏览器工具" });
  await expect(row).toBeVisible();
  const download = member.waitForEvent("download");
  await row.getByRole("button", { name: "下载", exact: true }).click();
  expect(readFileSync((await (await download).path())!, "utf8")).toBe(
    "isolated-browser-download",
  );
  await row.getByRole("button", { name: "申请退款", exact: true }).click();
  await member
    .getByRole("dialog")
    .getByRole("textbox")
    .fill("软件无法使用，请整单退鼓励值");
  await member
    .getByRole("dialog")
    .getByRole("button", { name: "提交", exact: true })
    .click();
  await admin.goto(staff("/admin/workspace?view=refunds"));
  await expect(
    admin
      .locator(".el-table:visible")
      .getByRole("row")
      .filter({ hasText: memberNickname }),
  ).toBeVisible();
  await admin
    .getByRole("row")
    .filter({ hasText: memberNickname })
    .getByRole("button", { name: "审核", exact: true })
    .click();
  await field(admin, "逐单处理原因", { exact: true }).fill(
    "交付问题核实，整单退鼓励值并撤销权益",
  );
  await admin.getByRole("button", { name: "确认处理", exact: true }).click();
  await expect(
    admin
      .locator(".el-table:visible")
      .getByRole("row")
      .filter({ hasText: memberNickname }),
  ).toContainText("已退款");
  await member.reload();
  await member.getByRole("tab", { name: "我的资源", exact: true }).click();
  await expect(
    member.getByRole("row").filter({ hasText: "付费浏览器工具" }),
  ).toContainText("已退款");
  expect(
    (await (await member.request.get("/api/member/wallet")).json()).balance,
  ).toBe("101.00");
  await admin.screenshot({
    path: "test-results/element-refunds.png",
    fullPage: true,
  });
  await member.screenshot({
    path: "test-results/member-wallet.png",
    fullPage: true,
  });
  await member.getByRole("button", { name: "退出登录", exact: true }).click();
  expect((await member.request.get("/api/member/wallet")).status()).toBe(401);
  await member.goto("/account-access?action=forgot");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  const reset = await inboxLink("member@example.com", "reset");
  await member.goto(reset);
  await field(member, "密码", { exact: true }).fill(
    "updated-member-password-123",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "updated-member-password-123",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  expect(errors).toEqual([]);
  await context.close();
  await adminContext.close();
});

test("content draft isolation, static bilingual publishing and SEO discovery", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const admin = await context.newPage();
  await admin.goto(staff("/admin/workspace?view=articles"));
  await admin.getByRole("button", { name: "新增内容", exact: true }).click();
  await field(admin, "内容中文标题", { exact: true }).fill("markdo 使用教程");
  await field(admin, "内容英文标题", { exact: true }).fill("markdo tutorial");
  await field(admin, "内容公开地址", { exact: true }).fill("markdo-tutorial");
  await field(admin, "内容中文摘要", { exact: true }).fill(
    "了解 markdo 的常用功能与操作步骤。",
  );
  await admin
    .getByRole("textbox", { name: "内容中文正文", exact: true })
    .fill("最初发布的教程正文");
  await admin
    .getByRole("textbox", { name: "内容英文正文", exact: true })
    .fill("Published English instructions");
  await admin.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(admin.getByText("草稿已保存", { exact: true })).toBeVisible();
  const draft = (
    await (
      await admin.request.get(
        staff("/api/admin/content?scope=articles&q=markdo"),
      )
    ).json()
  ).items.find((r: any) => r.slug === "markdo-tutorial");
  const publicContext = await browser.newContext({ javaScriptEnabled: false });
  const visitor = await publicContext.newPage();
  expect(
    (
      await visitor.request.get(
        "http://127.0.0.1:3000/articles/markdo-tutorial",
      )
    ).status(),
  ).toBe(404);
  await admin.getByRole("button", { name: "保存并发布", exact: true }).click();
  await expect(
    admin.getByText("发布成功，双语静态页面已生成", { exact: true }),
  ).toBeVisible();
  const browse = await context.newPage();
  await browse.goto("/articles");
  const contentPopup = browse.waitForEvent("popup");
  await browse.getByRole("link", { name: /markdo 使用教程/ }).click();
  const contentDetail = await contentPopup;
  await expect(contentDetail.locator("article")).toContainText(
    "最初发布的教程正文",
  );
  await contentDetail.close();
  await browse.close();
  await visitor.goto("http://127.0.0.1:3000/articles/markdo-tutorial");
  await expect(
    visitor.getByRole("heading", { name: "markdo 使用教程", exact: true }),
  ).toBeVisible();
  await expect(visitor.locator("article")).toContainText("最初发布的教程正文");
  expect(
    await visitor.locator('link[rel="canonical"]').getAttribute("href"),
  ).toContain("/articles/markdo-tutorial");
  expect(
    await visitor.locator('script[type="application/ld+json"]').textContent(),
  ).toContain('"Article"');
  await visitor.screenshot({
    path: "test-results/content-static.png",
    fullPage: true,
  });
  await visitor.goto("http://127.0.0.1:3000/articles/markdo-tutorial?lang=en");
  await expect(visitor.locator("article")).toContainText(
    "Published English instructions",
  );
  expect(
    await (
      await visitor.request.get("http://127.0.0.1:3000/sitemap.xml")
    ).text(),
  ).toContain("/articles/markdo-tutorial");
  await admin
    .getByRole("textbox", { name: "内容中文正文", exact: true })
    .fill("第二版尚未发布正文");
  await admin.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(admin.getByText("草稿已保存", { exact: true })).toBeVisible();
  await visitor.goto("http://127.0.0.1:3000/articles/markdo-tutorial");
  await expect(visitor.locator("article")).toContainText("最初发布的教程正文");
  await expect(visitor.locator("article")).not.toContainText(
    "第二版尚未发布正文",
  );
  await admin.screenshot({
    path: "test-results/content-editor.png",
    fullPage: true,
  });
  await admin.getByRole("button", { name: "保存并发布", exact: true }).click();
  await expect(
    admin.getByText("发布成功，双语静态页面已生成", { exact: true }),
  ).toBeVisible();
  await visitor.reload();
  await expect(visitor.locator("article")).toContainText("第二版尚未发布正文");
  await configuration(admin, { "seo.allow_indexing": "false" });
  const noindex = await visitor.request.get(
    "http://127.0.0.1:3000/articles/markdo-tutorial",
  );
  expect(noindex.headers()["x-robots-tag"]).toBe("noindex, nofollow");
  expect(
    await (
      await visitor.request.get("http://127.0.0.1:3000/robots.txt")
    ).text(),
  ).toContain("Disallow: /");
  await configuration(admin, { "seo.allow_indexing": "true" });
  await menu(admin, "文章与教程");
  await admin
    .getByRole("row")
    .filter({ hasText: "markdo 使用教程" })
    .getByRole("button", { name: "下架", exact: true })
    .click();
  await expect(
    admin.getByText("已下架", { exact: true }).first(),
  ).toBeVisible();
  expect(
    (
      await visitor.request.get(
        "http://127.0.0.1:3000/articles/markdo-tutorial",
      )
    ).status(),
  ).toBe(404);
  expect(
    await (
      await visitor.request.get("http://127.0.0.1:3000/sitemap.xml")
    ).text(),
  ).not.toContain("/articles/markdo-tutorial");
  expect(
    (
      await (
        await admin.request.get(staff("/api/admin/content/") + draft.id)
      ).json()
    ).state,
  ).toBe("UNLISTED");
  await publicContext.close();
  await context.close();
});

test("compact horizontal forms, server sorting, license replacement and refund revocation", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const adminContext = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  const admin = await adminContext.newPage(),
    member = await browser.newPage();
  await member
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  const headers = { "X-AIWYW-Request": "1" };
  const runId = Date.now().toString(36),
    titlePrefix = "排序应用-" + runId + "-",
    licenseName = "授权浏览器工具-" + runId,
    licenseSlug = "licensed-browser-" + runId;
  for (let i = 0; i < 23; i++) {
    const result = await admin.request.post(
      staff("/api/admin/catalog/products"),
      {
        headers,
        data: content(
          "sort-" + runId + "-" + String(i).padStart(2, "0"),
          titlePrefix + String(i).padStart(2, "0"),
        ),
      },
    );
    expect(result.status()).toBe(200);
  }
  await admin.goto(staff("/admin/workspace?view=applications"));
  await field(admin, "名称或关键字", { exact: true }).fill(titlePrefix);
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await expect(admin.locator(".el-pagination")).toContainText("23");
  const box = await field(admin, "名称或关键字", { exact: true }).boundingBox();
  const label = await admin
    .locator(".el-form-item")
    .filter({ has: field(admin, "名称或关键字", { exact: true }) })
    .locator(".el-form-item__label")
    .boundingBox();
  expect(box!.width).toBeLessThanOrEqual(320);
  expect(label!.x + label!.width).toBeLessThanOrEqual(box!.x + 2);
  expect(Math.abs(label!.y - box!.y)).toBeLessThan(8);
  for (const [direction, first] of [
    ["descending", titlePrefix + "22"],
    ["ascending", titlePrefix + "00"],
  ]) {
    const sorted = admin.waitForResponse(
      (r) =>
        r.url().includes("/products/page?") &&
        r.url().includes("sort=nameZh") &&
        r.url().includes("direction=" + direction),
    );
    await admin
      .getByRole("button", { name: "按 应用 排序", exact: true })
      .click();
    const result = await (await sorted).json();
    expect(result.items[0].nameZh).toBe(first);
    await expect(
      admin.locator(".el-table__body-wrapper tbody tr").first(),
    ).toContainText(first);
  }
  await admin.screenshot({
    path: "test-results/compact-sorted-applications.png",
    fullPage: true,
  });
  // 未保存草稿的介绍填充可撤销，不更改现有商品价格/文件，不自动发布。
  await field(admin, "名称或关键字", { exact: true }).fill("markdo");
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin
    .locator(".el-table__body-wrapper")
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await admin
    .getByRole("button", { name: "填入MarkDo介绍", exact: true })
    .click();
  await admin.getByRole("button", { name: "填入介绍", exact: true }).click();
  await expect(field(admin, "应用名称", { exact: true })).toHaveValue(
    "MarkDo · 本地待办与局域网同步",
  );
  expect(
    (await field(admin, "版本", { exact: true }).boundingBox())!.width,
  ).toBeLessThanOrEqual(180);
  await admin.screenshot({
    path: "test-results/compact-markdo-editor.png",
    fullPage: true,
  });
  // 单独新页面进入授权管理，避免关闭未保存介绍时的确认流程影响本次授权场景。
  admin.once("dialog", (dialog) => dialog.accept());
  await admin.reload();
  const created = await admin.request.post(
    staff("/api/admin/catalog/products"),
    {
      headers,
      data: {
        ...content(licenseSlug, licenseName, 10),
        deliveryMode: "LICENSE",
      },
    },
  );
  expect(created.status()).toBe(200);
  const id = (await created.json()).id;
  await admin.goto(staff("/admin/workspace?view=licenses"));
  await pick(admin, "授权策略应用", [licenseName]);
  await admin
    .getByRole("button", { name: "保存授权策略", exact: true })
    .click();
  await expect(
    admin.getByText("策略已保存；请重新发布应用后生效", { exact: true }),
  ).toBeVisible();
  const draft = await (
    await admin.request.get(staff("/api/admin/catalog/products/") + id)
  ).json();
  expect(
    (
      await admin.request.post(
        staff("/api/admin/catalog/products/") + id + "/publish",
        { headers, data: { revision: draft.revision } },
      )
    ).status(),
  ).toBe(200);
  await member.goto("/account-access");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "updated-member-password-123",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member.goto("/checkout?slug=" + licenseSlug);
  await member.getByRole("button", { name: "确认兑换", exact: true }).click();
  await expect(member).toHaveURL(/account\?resource=/);
  const order = new URL(member.url()).searchParams.get("resource")!;
  const row = member.getByRole("row").filter({ hasText: licenseName });
  await row.getByRole("button", { name: "激活授权", exact: true }).click();
  const dialog = member.getByRole("dialog");
  await expect(field(dialog, "激活密钥", { exact: true })).toBeVisible();
  const key = await field(dialog, "激活密钥", { exact: true }).inputValue();
  const device = "browser-device-1111111111111111111111111111";
  const activeResult = await member.request.post("/api/license/activate", {
    headers,
    data: {
      key,
      deviceId: device,
      deviceName: "旧电脑",
      productId: id,
      version: "1.0.0",
    },
  });
  expect(activeResult.status()).toBe(200);
  const active = await activeResult.json();
  await dialog
    .getByRole("button", { name: /Close this dialog|关闭此对话框/ })
    .click();
  await row.getByRole("button", { name: "激活授权", exact: true }).click();
  await dialog.getByRole("button", { name: "申请解绑", exact: true }).click();
  await field(dialog, "换机原因", { exact: true }).fill("旧电脑故障需要换机");
  await dialog.getByRole("button", { name: "提交申请", exact: true }).click();
  await expect(dialog.getByText("待审核", { exact: false })).toBeVisible();
  await dialog
    .getByRole("button", { name: /Close this dialog|关闭此对话框/ })
    .click();
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin
    .getByRole("row")
    .filter({ hasText: "旧电脑" })
    .getByRole("button", { name: "通过解绑", exact: true })
    .click();
  await admin
    .locator(".el-message-box")
    .getByRole("textbox")
    .fill("已核实设备故障");
  await admin
    .locator(".el-message-box")
    .getByRole("button", { name: "通过解绑", exact: true })
    .click();
  await expect(
    admin.getByRole("row").filter({ hasText: "旧电脑" }),
  ).toContainText("已通过");
  const verification = {
    ...active,
    deviceId: device,
    productId: id,
    version: "1.0.0",
  };
  expect(
    (
      await member.request.post("/api/license/verify", {
        headers,
        data: verification,
      })
    ).status(),
  ).toBe(403);
  const replacement = await member.request.post("/api/license/activate", {
    headers,
    data: {
      key,
      deviceId: "browser-device-2222222222222222222222222222",
      deviceName: "新电脑",
      productId: id,
      version: "1.0.1",
    },
  });
  expect(replacement.status()).toBe(200);
  const next = await replacement.json();
  await member.request.post("/api/member/resources/" + order + "/refund", {
    headers,
    data: { reason: "软件无法使用申请整单退鼓励值" },
  });
  expect(
    (
      await admin.request.post(
        staff("/api/admin/commerce/refunds/") + order + "/review",
        {
          headers,
          data: {
            approve: true,
            reason: "核实整单退鼓励值并撤销激活",
            revision: 1,
          },
        },
      )
    ).status(),
  ).toBe(200);
  expect(
    (
      await member.request.post("/api/license/verify", {
        headers,
        data: {
          ...next,
          deviceId: "browser-device-2222222222222222222222222222",
          productId: id,
          version: "1.0.1",
        },
      })
    ).status(),
  ).toBe(403);
  await admin.screenshot({
    path: "test-results/license-unbind-review.png",
    fullPage: true,
  });
  await member.close();
  await adminContext.close();
});

test("nickname, role menu union and administrator MFA emergency policy", async ({
  browser,
}) => {
  test.setTimeout(120000);
  const context = await browser.newContext({
      storageState: join(directory(), "admin-state.json"),
    }),
    admin = await context.newPage();
  await admin.goto(staff("/admin/workspace?view=roles"));
  await admin.getByRole("button", { name: "新增角色", exact: true }).click();
  const editor = workspace(admin);
  await field(editor, "角色名称", { exact: true }).fill("目录查看员");
  await editor
    .locator(".el-tree-node__content")
    .filter({ hasText: /^应用管理$/ })
    .locator(".el-checkbox")
    .click();
  await editor.getByRole("button", { name: "保存角色", exact: true }).click();
  await expect(
    admin.getByRole("row").filter({ hasText: "目录查看员" }),
  ).toBeVisible();
  const role = (
    await (await admin.request.get(staff("/api/admin/roles"))).json()
  ).items.find((r: any) => r.name === "目录查看员");
  const create = await admin.request.post(staff("/api/admin/accounts"), {
    headers: { "X-AIWYW-Request": "1" },
    data: {
      email: "multi-role@example.com",
      password: "multi-role-password-123",
      roles: [role.id, "FINANCE"],
      disabled: false,
    },
  });
  expect(create.status()).toBe(200);
  const member = await browser.newPage();
  await member
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  await member.goto(staff("/login"));
  await field(member, "邮箱", { exact: true }).fill("multi-role@example.com");
  await field(member, "密码", { exact: true }).fill("multi-role-password-123");
  await member.getByRole("button", { name: /登录后台/ }).click();
  await expect(member).toHaveURL(/admin\/security/);
  expect(
    (await member.request.get(staff("/api/admin/catalog/products"))).status(),
  ).toBe(403);
  await configuration(admin, { "security.mfa_required": "false" });
  await member.reload();
  expect(
    (await member.request.get(staff("/api/admin/catalog/products"))).status(),
  ).toBe(200);
  expect(
    (await member.request.get(staff("/api/admin/settings"))).status(),
  ).toBe(403);
  const session = await (
    await member.request.get(staff("/api/auth/session"))
  ).json();
  expect(session.roles).toContain("FINANCE");
  expect(session.nickname).toMatch(/^(?=.*[a-zA-Z])(?=.*[0-9])[a-zA-Z0-9]+$/);
  expect(
    (
      await member.request.put(staff("/api/member/nickname"), {
        headers: { "X-AIWYW-Request": "1" },
        data: { nickname: "工具收藏家" },
      })
    ).status(),
  ).toBe(200);
  await member.goto(staff("/admin/workspace?view=applications"));
  await expect(member.locator(".management-banner")).toContainText(
    "工具收藏家",
  );
  await configuration(admin, { "security.mfa_required": "true" });
  expect(
    (await member.request.get(staff("/api/admin/catalog/products"))).status(),
  ).toBe(403);
  await member.close();
  await context.close();
});

test("profile nickname and password, asymmetric menu tree and light authentication pages", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const adminContext = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  const admin = await adminContext.newPage();
  await admin.goto(staff("/admin/workspace?view=roles"));
  await admin.getByRole("button", { name: "新增角色", exact: true }).click();
  const editor = workspace(admin),
    parent = editor
      .locator(".el-tree-node__content")
      .filter({ hasText: /^商品管理$/ }),
    child = editor
      .locator(".el-tree-node__content")
      .filter({ hasText: /^应用管理$/ });
  await field(editor, "角色名称", { exact: true }).fill("树形授权验收");
  await parent.locator(".el-checkbox").click();
  await expect(child.getByRole("checkbox")).not.toBeChecked();
  await parent.locator(".el-checkbox").click();
  await child.locator(".el-checkbox").click();
  await expect(parent.getByRole("checkbox")).toBeChecked();
  await expect(
    editor
      .locator(".el-tree-node__content")
      .filter({ hasText: /^标签管理$/ })
      .getByRole("checkbox"),
  ).not.toBeChecked();
  await editor.getByRole("button", { name: "保存角色", exact: true }).click();
  await expect(
    admin.getByRole("row").filter({ hasText: "树形授权验收" }),
  ).toBeVisible();
  const saved = (
    await (await admin.request.get(staff("/api/admin/roles"))).json()
  ).items.find((r: any) => r.name === "树形授权验收");
  expect(saved.menus.sort()).toEqual(["applications", "group:products"].sort());
  const member = await browser.newPage();
  await member
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  for (const [action, file] of [
    ["login", "auth-login"],
    ["register", "auth-register"],
    ["forgot", "auth-forgot"],
  ]) {
    await member.goto("/account-access?action=" + action);
    await expect(member.locator(".directory-brand-logo")).toBeVisible();
    await member.screenshot({
      path: "test-results/" + file + ".png",
      fullPage: true,
    });
  }
  await member.goto(staff("/login"));
  await expect(member.locator(".directory-brand-logo")).toBeVisible();
  expect(
    await member
      .locator(".login-story")
      .evaluate((el) => getComputedStyle(el).backgroundImage),
  ).toContain("linear-gradient");
  await member.screenshot({
    path: "test-results/auth-admin.png",
    fullPage: true,
  });
  await member.goto("/account-access");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "updated-member-password-123",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member
    .getByRole("tab", { name: "个人资料与安全", exact: true })
    .click();
  await member
    .getByRole("button", { name: "修改资料与密码", exact: true })
    .click();
  await field(member, "个人昵称", { exact: true }).fill("我的新昵称");
  await member.getByRole("button", { name: "保存昵称", exact: true }).click();
  await expect(member.getByText("昵称已更新", { exact: true })).toBeVisible();
  expect(
    (await (await member.request.get("/api/member/profile")).json()).user
      .nickname,
  ).toBe("我的新昵称");
  const other = await browser.newContext();
  expect(
    (
      await other.request.post("http://127.0.0.1:3000/api/auth/login", {
        headers: { "X-AIWYW-Request": "1" },
        data: {
          email: "member@example.com",
          password: "updated-member-password-123",
          code: "",
        },
      })
    ).status(),
  ).toBe(200);
  await field(member, "当前密码", { exact: true }).fill("wrong-password-123");
  await field(member, "新密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await field(member, "确认新密码", { exact: true }).fill(
    "different-new-password-123",
  );
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(
    member.getByText("两次输入的新密码不一致", { exact: true }),
  ).toBeVisible();
  await field(member, "确认新密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(
    member.getByText("当前密码不正确", { exact: true }),
  ).toBeVisible();
  await field(member, "当前密码", { exact: true }).fill(
    "updated-member-password-123",
  );
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(member).toHaveURL(/account-access\?changed=1/);
  expect(
    (
      await other.request.get("http://127.0.0.1:3000/api/member/profile")
    ).status(),
  ).toBe(401);
  await other.close();
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member
    .getByRole("tab", { name: "个人资料与安全", exact: true })
    .click();
  await member
    .getByRole("button", { name: "修改资料与密码", exact: true })
    .click();
  await expect(field(member, "个人昵称", { exact: true })).toHaveValue(
    "我的新昵称",
  );
  await member.close();
  await adminContext.close();
});

test("API configuration, one-time keys, billable debugger and subscription refund", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const admin = await context.newPage(),
    errors: string[] = [];
  admin.on("pageerror", (e) => errors.push(e.message));
  await admin.goto(staff("/admin/workspace?view=apis"));
  const apiList = admin.locator('[data-workspace="apis"]');
  await apiList.getByRole("button", { name: "新增API", exact: true }).click();
  const apiPanel = admin.locator('[data-workspace^="api-editor-"]:visible');
  await expect(apiPanel.getByRole("table")).toHaveCount(0);
  await field(apiPanel, "API配置名称", { exact: true }).fill("浏览器文本API");
  await field(apiPanel, "API调用地址", { exact: true }).fill(
    "browser-text-api",
  );
  await field(apiPanel, "API中文说明", { exact: true }).fill(
    "输入text字符串，返回字符、词和行数。",
  );
  await select(admin, "API适配器", "文本统计（真实本地计算）");
  await field(apiPanel, "API鼓励值价格", { exact: true }).fill("1.25");
  await apiPanel.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    apiPanel.getByText("已保存，已有购买和调用条款不被覆盖", { exact: true }),
  ).toBeVisible();
  const api = (
    await (await admin.request.get(staff("/api/admin/apis"))).json()
  ).find((a: any) => a.slug === "browser-text-api");
  await menu(admin, "套餐与订阅");
  const planList = admin.locator('[data-workspace="plans"]');
  await planList.getByRole("button", { name: "新增套餐", exact: true }).click();
  const planPanel = workspace(admin);
  await expect(planPanel.getByRole("table")).toHaveCount(0);
  await field(planPanel, "API配置名称", { exact: true }).fill("浏览器月度套餐");
  await field(planPanel, "API鼓励值价格", { exact: true }).fill("2.00");
  await pick(admin, "套餐所属API", ["浏览器文本API"]);
  await planPanel.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    planPanel.getByText("已保存，已有购买和调用条款不被覆盖", { exact: true }),
  ).toBeVisible();
  const plans = await (
      await admin.request.get(staff("/api/admin/api-plans"))
    ).json(),
    plan = plans.find((p: any) => p.name === "浏览器月度套餐");
  const member = await browser.newPage();
  member.on("pageerror", (e) => errors.push(e.message));
  await member
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  await member.goto("/account-access");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  const before = BigInt(
    (
      await (await member.request.get("/api/member/wallet")).json()
    ).balance.replace(".", ""),
  );
  await member.goto("/api-services?manage=1");
  const card = member.getByRole("row").filter({ hasText: "浏览器文本API" });
  await card.getByRole("button", { name: "计费设置", exact: true }).click();
  const billing = member.getByRole("dialog", { name: "计费设置", exact: true });
  await billing
    .locator(".el-checkbox")
    .filter({ hasText: "无有效套餐时允许按次扣鼓励值" })
    .click();
  await billing
    .getByRole("button", { name: "保存计费选择", exact: true })
    .click();
  await expect(
    member.getByText("计费选择已保存", { exact: true }),
  ).toBeVisible();
  await member.getByRole("tab", { name: "我的Key", exact: true }).click();
  await member.getByRole("button", { name: "新增Key", exact: true }).click();
  const dialog = member.getByRole("dialog");
  await field(dialog, "Key名称", { exact: true }).fill("浏览器调用Key");
  await pick(member, "Key API权限", ["浏览器文本API"]);
  await dialog.getByRole("button", { name: "保存Key", exact: true }).click();
  const token = await field(dialog, "新建API密钥", {
    exact: true,
  }).inputValue();
  expect(token).toMatch(/^aiwyw_/);
  await dialog
    .getByRole("button", { name: "已保存，关闭", exact: true })
    .click();
  expect(
    JSON.stringify(await (await member.request.get("/api/member/api")).json()),
  ).not.toContain(token);
  await member.getByRole("tab", { name: "在线调试", exact: true }).click();
  await member
    .getByRole("row")
    .filter({ hasText: "浏览器文本API" })
    .getByRole("button", { name: "开始调试", exact: true })
    .click();
  await pick(member, "调试Key", ["浏览器调用Key"]);
  await field(member, "调试JSON输入", { exact: true }).fill(
    '{"text":"Hello world"}',
  );
  await member
    .getByRole("button", { name: "执行（正常计费）", exact: true })
    .click();
  await expect(member.locator(".debug-output")).toContainText(
    '"chargedPoints": "1.25"',
  );
  await expect(member.locator(".debug-output")).toContainText(
    '"characters": 11',
  );
  await member
    .getByRole("button", { name: "执行（正常计费）", exact: true })
    .click();
  await expect(
    member.getByRole("button", { name: "执行（正常计费）", exact: true }),
  ).toBeEnabled();
  const after = BigInt(
    (
      await (await member.request.get("/api/member/wallet")).json()
    ).balance.replace(".", ""),
  );
  expect(before - after).toBe(125n);
  await member.screenshot({
    path: "test-results/api-debugger.png",
    fullPage: true,
  });
  const call = await member.request.post("/api/v1/invoke/browser-text-api", {
    headers: {
      Authorization: "Bearer " + token,
      "Idempotency-Key": "browser-programmatic-123",
    },
    data: { text: "programmatic" },
  });
  expect(call.status()).toBe(200);
  expect((await call.json()).status).toBe("SUCCESS");
  await member
    .getByRole("dialog", { name: "在线调试", exact: true })
    .locator(".el-dialog__headerbtn")
    .click();
  await member.getByRole("tab", { name: "接口与套餐", exact: true }).click();
  await card.getByRole("button", { name: "套餐 / 续费", exact: true }).click();
  await member
    .getByRole("dialog", { name: "接口套餐", exact: true })
    .getByRole("row")
    .filter({ hasText: "浏览器月度套餐" })
    .getByRole("button", { name: "购买 / 续费", exact: true })
    .click();
  await member
    .getByRole("dialog", { name: "确认购买或续费", exact: true })
    .getByRole("button", { name: /确定|确认/ })
    .click();
  await expect(
    member.getByText("套餐已购买，请查看周期时间", { exact: true }),
  ).toBeVisible();
  let sub = (
    await (await member.request.get("/api/member/api")).json()
  ).subscriptions.find((s: any) => s.plan_id === plan.id);
  expect(Number(sub.used_count)).toBe(0);
  await member
    .getByRole("row")
    .filter({ hasText: "浏览器月度套餐" })
    .getByRole("button", { name: "申请退鼓励值", exact: true })
    .click();
  await member
    .getByRole("dialog")
    .locator("input")
    .fill("尚未使用，申请退鼓励值");
  await member
    .getByRole("dialog")
    .getByRole("button", { name: /确定|确认/ })
    .click();
  await expect(
    member.getByText("退款申请已提交", { exact: true }),
  ).toBeVisible();
  await admin.goto(staff("/admin/workspace?view=refunds"));
  const refundPanel = admin.locator('[data-workspace="refunds"]');
  await expect(
    refundPanel.getByRole("heading", { name: "API套餐退款", exact: true }),
  ).toBeVisible();
  await refundPanel
    .getByRole("row")
    .filter({ hasText: "浏览器月度套餐" })
    .getByRole("button", { name: "批准", exact: true })
    .click();
  await admin
    .getByRole("dialog")
    .locator("input,textarea")
    .first()
    .fill("核实无成功调用，整单退鼓励值");
  await admin
    .getByRole("dialog")
    .getByRole("button", { name: /确定|确认/ })
    .click();
  await expect(
    refundPanel.getByText("退款审核完成", { exact: true }),
  ).toBeVisible();
  sub = (
    await (await member.request.get("/api/member/api")).json()
  ).subscriptions.find((s: any) => s.id === sub.id);
  expect(sub.state).toBe("REFUNDED");
  const key = (
    await (await member.request.get("/api/member/api")).json()
  ).keys.find((k: any) => k.name === "浏览器调用Key");
  const revoke = await admin.request.post(
    staff("/api/admin/api-keys/") + key.id + "/revoke",
    { headers: { "X-AIWYW-Request": "1" }, data: { revision: key.revision } },
  );
  expect(revoke.status()).toBe(200);
  expect(
    (
      await member.request.post("/api/v1/invoke/browser-text-api", {
        headers: {
          Authorization: "Bearer " + token,
          "Idempotency-Key": "revoked-request-123",
        },
        data: { text: "x" },
      })
    ).status(),
  ).toBe(401);
  await member.getByRole("button", { name: "English", exact: true }).click();
  await member.getByRole("tab", { name: "My keys", exact: true }).click();
  await member
    .getByRole("row")
    .filter({ hasText: "浏览器调用Key" })
    .getByRole("button", { name: "Delete", exact: true })
    .click();
  await expect(
    member.getByRole("dialog", { name: "Delete key", exact: true }),
  ).toContainText("Unused keys may be deleted");
  await member
    .getByRole("dialog")
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
  expect(errors).toEqual([]);
  await member.close();
  await context.close();
});

test("guest support, private image, claim and transfer, moderated feedback and operational reports", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const adminContext = await browser.newContext({
      storageState: join(directory(), "admin-state.json"),
    }),
    guestContext = await browser.newContext();
  for (const c of [adminContext, guestContext])
    await c.addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  const guest = await guestContext.newPage(),
    admin = await adminContext.newPage(),
    errors: string[] = [];
  for (const p of [guest, admin])
    p.on("pageerror", (e) => errors.push(e.message));
  await guest.goto("/support");
  await guest.getByRole("button", { name: "新建咨询", exact: true }).click();
  await field(guest.getByRole("dialog"), "咨询主题", { exact: true }).fill(
    "浏览器游客咨询",
  );
  await guest
    .getByRole("dialog")
    .getByRole("button", { name: "开始咨询", exact: true })
    .click();
  await expect(
    guest.getByRole("textbox", { name: "客服消息输入", exact: true }),
  ).toBeVisible();
  await guest
    .getByRole("textbox", { name: "客服消息输入", exact: true })
    .fill("请问如何使用这个工具？");
  const chooser = guest.waitForEvent("filechooser");
  await guest
    .getByRole("button", { name: "上传图片 / 截图", exact: true })
    .click();
  const imageUploaded = guest.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      /\/api\/support\/conversations\/[^/]+\/image$/.test(
        new URL(r.url()).pathname,
      ) &&
      r.status() === 200,
  );
  await (
    await chooser
  ).setFiles({
    name: "shot.webp",
    mimeType: "image/webp",
    buffer: readFileSync("backend/src/test/resources/tiny.webp"),
  });
  await imageUploaded;
  await expect(guest.locator(".pending-image img")).toBeVisible({
    timeout: 15000,
  });
  await guest.getByRole("button", { name: "发送", exact: true }).click();
  await expect(guest.locator(".chat-feed")).toContainText(
    "请问如何使用这个工具？",
  );
  const history = await (
      await guest.request.get("/api/support/conversations")
    ).json(),
    c = history.items.find((x: any) => x.subject === "浏览器游客咨询"),
    msgs = await (
      await guest.request.get(
        "/api/support/conversations/" + c.id + "/messages",
      )
    ).json(),
    image = msgs.items.find((m: any) => m.asset_id).asset_id;
  const stranger = await browser.newContext();
  expect(
    (
      await stranger.request.get(
        "http://127.0.0.1:3000/api/support/conversations/" + c.id + "/messages",
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await stranger.request.get(
        "http://127.0.0.1:3000/api/support/assets/" + image,
      )
    ).status(),
  ).toBe(404);
  await admin.goto(staff("/admin/workspace?view=conversations"));
  await admin
    .getByRole("row")
    .filter({ hasText: "浏览器游客咨询" })
    .getByRole("button", { name: "抢单领取", exact: true })
    .click();
  await expect(
    workspace(admin).getByRole("heading", { name: "会话回复", exact: true }),
  ).toBeVisible();
  await expect(
    admin
      .locator('[data-workspace="conversations"]')
      .getByRole("textbox", { name: "客服消息输入", exact: true }),
  ).toHaveCount(0);
  await expect(
    admin.getByRole("textbox", { name: "客服消息输入", exact: true }),
  ).toBeVisible();
  await admin
    .getByRole("textbox", { name: "客服消息输入", exact: true })
    .fill("已收到截图，我来协助你。");
  await admin.getByRole("button", { name: "发送", exact: true }).click();
  await expect(guest.locator(".chat-feed")).toContainText(
    "已收到截图，我来协助你。",
    { timeout: 15000 },
  );
  await admin.screenshot({
    path: "test-results/support-admin.png",
    fullPage: true,
  });
  const created = await admin.request.post(staff("/api/admin/accounts"), {
    headers: { "X-AIWYW-Request": "1" },
    data: {
      email: "support-browser@example.com",
      password: "support-browser-password-123",
      roles: ["SUPPORT"],
      disabled: false,
    },
  });
  expect(created.status()).toBe(200);
  const { id: targetId } = await created.json();
  const agents = await (
    await admin.request.get(
      staff("/api/admin/support/agents/page?q=") + targetId,
    )
  ).json();
  const target = agents.items.find((row: any) => row.id === targetId);
  expect(target).toBeTruthy();
  await pick(admin, "选择接收客服", [target.nickname]);
  await admin.getByRole("button", { name: "管理员转派", exact: true }).click();
  await expect(admin.getByText("转派已完成，历史记录保留")).toBeVisible();
  await admin.reload();
  await menu(admin, "在线会话");
  await admin
    .getByRole("row")
    .filter({ hasText: "浏览器游客咨询" })
    .getByRole("button", { name: "查看", exact: true })
    .click();
  await expect(
    admin.getByRole("textbox", { name: "客服消息输入", exact: true }),
  ).toHaveCount(0);
  await guest
    .getByRole("dialog", { name: "在线咨询", exact: true })
    .locator(".el-dialog__headerbtn")
    .click();
  await guest.getByRole("tab", { name: "留言与意见", exact: true }).click();
  await guest
    .getByRole("button", { name: "新增留言与意见", exact: true })
    .click();
  await field(guest, "意见反馈内容", { exact: true }).fill(
    "公开留言：希望补充更多工具。",
  );
  await guest.getByRole("button", { name: "提交反馈", exact: true }).click();
  await expect(
    guest.getByText("已提交，公开留言审核后展示；私密反馈仅本人和客服可见。", {
      exact: true,
    }),
  ).toBeVisible();
  const privateResult = await guest.request.post("/api/support/feedback", {
    headers: { "X-AIWYW-Request": "1" },
    data: { kind: "PRIVATE", body: "私密意见：只允许客服查看。" },
  });
  expect(privateResult.status()).toBe(200);
  await admin.goto(staff("/admin/workspace?view=messages"));
  await admin
    .getByRole("row")
    .filter({ hasText: "公开留言：希望补充更多工具。" })
    .getByRole("button", { name: "批准 / 回复", exact: true })
    .click();
  await admin
    .getByRole("dialog")
    .locator("textarea,input")
    .first()
    .fill("已记录，感谢建议。");
  await admin
    .getByRole("dialog")
    .getByRole("button", { name: /确定|确认/ })
    .click();
  await expect(admin.getByText("处理已保存", { exact: true })).toBeVisible();
  const publicFeed = await (
    await stranger.request.get("http://127.0.0.1:3000/api/public/messages")
  ).json();
  expect(JSON.stringify(publicFeed)).toContain("公开留言：希望补充更多工具。");
  expect(JSON.stringify(publicFeed)).not.toContain("私密意见");
  const trackedSearch = guest.waitForResponse(
    (r) =>
      r.url().endsWith("/api/public/events") &&
      r.request().postData()?.includes('"kind":"SEARCH"') === true,
  );
  await guest.goto("/apps?q=markdo");
  await expect(
    guest.getByRole("heading", { name: /全部应用|All applications/ }),
  ).toBeVisible();
  await trackedSearch;
  await guest.goto("/support");
  await guest.setViewportSize({ width: 390, height: 844 });
  await guest.screenshot({
    path: "test-results/support-mobile.png",
    fullPage: true,
  });
  expect(
    await guest.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await admin.goto(staff("/admin/workspace?view=statistics"));
  await expect(
    admin.getByRole("heading", { name: "运营统计", exact: true }),
  ).toBeVisible();
  await select(admin, "统计账本模式", "模拟账本（独立分析）");
  await admin.getByRole("button", { name: "查询统计", exact: true }).click();
  await expect(admin.locator(".report-scope")).toContainText(
    "当前显示模拟账本",
  );
  await admin.getByRole("tab", { name: "搜索关键词", exact: true }).click();
  await expect(
    admin.getByRole("row").filter({ hasText: "markdo" }),
  ).toBeVisible();
  await admin.getByRole("tab", { name: "商品与API收入", exact: true }).click();
  await expect(
    admin.getByRole("row").filter({ hasText: "浏览器文本API" }),
  ).toBeVisible();
  const csv = await admin.request.get(
    staff("/api/admin/statistics/export?mode=SIMULATED"),
  );
  expect(csv.status()).toBe(200);
  expect(await csv.text()).toContain("API_CALL");
  await admin.screenshot({
    path: "test-results/operations-statistics.png",
    fullPage: true,
  });
  await guest.getByRole("button", { name: "English", exact: true }).click();
  await expect(
    guest.getByRole("tab", { name: "Recover guest history", exact: true }),
  ).toBeVisible();
  await guest
    .getByRole("button", { name: "New conversation", exact: true })
    .click();
  await expect(
    guest.getByRole("dialog", { name: "New conversation", exact: true }),
  ).toBeVisible();
  await expect(
    guest
      .getByRole("dialog")
      .getByRole("button", { name: "Start conversation", exact: true }),
  ).toBeVisible();
  await guest.getByRole("dialog").locator(".el-dialog__headerbtn").click();
  expect(errors).toEqual([]);
  await stranger.close();
  await guestContext.close();
  await adminContext.close();
});

// 验证真实导航维护、多编辑页签的草稿隔离与关闭决策，并查看实际桌面/移动端排版。
test("consistent typography, managed article navigation, API editor tabs and account sections", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const admin = await context.newPage(),
    errors: string[] = [];
  admin.on("pageerror", (e) => errors.push(e.message));
  await admin.goto(staff("/admin/workspace?view=apis"));
  const list = admin.locator('[data-workspace="apis"]');
  await list.getByRole("button", { name: "新增API", exact: true }).click();
  const first = admin.locator('[data-workspace^="api-editor-"]:visible');
  const firstId = await first.getAttribute("data-workspace");
  await field(first, "API配置名称", { exact: true }).fill("草稿一独立保留");
  await menu(admin, "API 管理");
  await list.getByRole("button", { name: "新增API", exact: true }).click();
  await field(
    admin.locator('[data-workspace^="api-editor-"]:visible'),
    "API配置名称",
    { exact: true },
  ).fill("草稿二独立保留");
  await expect(admin.locator('[data-workspace^="api-editor-"]')).toHaveCount(2);
  await admin
    .getByRole("tab", { name: "新增API", exact: true })
    .first()
    .click();
  await expect(
    field(admin.locator('[data-workspace="' + firstId + '"]'), "API配置名称", {
      exact: true,
    }),
  ).toHaveValue("草稿一独立保留");
  await first.getByRole("button", { name: "关闭页签", exact: true }).click();
  const close = admin.getByRole("dialog", {
    name: "关闭前保存修改？",
    exact: true,
  });
  await close.getByRole("button", { name: "取消", exact: true }).click();
  await expect(first).toBeVisible();
  await first.getByRole("button", { name: "关闭页签", exact: true }).click();
  await close
    .getByRole("button", { name: "放弃修改并关闭", exact: true })
    .click();
  // 确认框关闭与工作页签移除分两步完成；先验证原草稿确实移除，再定位剩下的同名页签。
  await expect(admin.locator('[data-workspace="' + firstId + '"]')).toHaveCount(
    0,
  );
  await expect(
    admin.getByRole("tab", { name: "新增API", exact: true }),
  ).toHaveCount(1);
  await admin.getByRole("tab", { name: "新增API", exact: true }).click();
  await admin
    .locator('[data-workspace^="api-editor-"]:visible')
    .getByRole("button", { name: "关闭页签", exact: true })
    .click();
  await close
    .getByRole("button", { name: "放弃修改并关闭", exact: true })
    .click();
  await expect(
    admin.getByRole("tab", { name: "新增API", exact: true }),
  ).toHaveCount(0);
  await menu(admin, "用户与权限");
  await menu(admin, "角色与权限");
  const roles = admin.locator('[data-workspace="roles"]');
  await expect(
    roles.getByRole("columnheader", { name: "绑定菜单", exact: true }),
  ).toHaveCount(0);
  await roles
    .getByRole("button", { name: "编辑", exact: true })
    .first()
    .click();
  await expect(admin.getByRole("dialog").getByRole("tree")).toBeVisible();
  await admin
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await menu(admin, "首页栏目");
  const sectionList = admin.locator('[data-workspace="sections"]');
  await sectionList
    .getByRole("row")
    .filter({ hasText: "文章与教程导航" })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  const sections = workspace(admin);
  await field(sections, "栏目名称", { exact: true }).fill("使用指南");
  await field(sections, "栏目顺序", { exact: true }).fill("0");
  await sections.getByRole("button", { name: "保存修改", exact: true }).click();
  await expect(sections.getByRole("status")).toHaveText("栏目已保存并生效");
  await menu(admin, "首页栏目");
  await sectionList
    .getByRole("button", { name: "新增栏目", exact: true })
    .click();
  await field(sections, "栏目名称", { exact: true }).fill("快捷工具");
  await sections.getByRole("button", { name: "保存修改", exact: true }).click();
  await expect(sections.getByRole("status")).toHaveText("栏目已保存并生效");
  const guest = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  guest.on("pageerror", (e) => errors.push(e.message));
  await guest
    .context()
    .addCookies([
      { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
    ]);
  await guest.goto("/");
  const nav = guest.getByRole("navigation", { name: "首页导航", exact: true });
  await expect(nav.getByRole("link").last()).toHaveAttribute(
    "href",
    "/api-services",
  );
  await expect(
    nav.getByRole("link", { name: "使用指南", exact: true }),
  ).toHaveAttribute("href", "/articles");
  await expect(
    nav.getByRole("link", { name: "快捷工具", exact: true }),
  ).toBeVisible();
  await expect(guest.locator(".directory-section h2").first()).toHaveText(
    "使用指南",
  );
  await expect(
    guest.locator(".directory-section h2", { hasText: "快捷工具" }),
  ).toBeVisible();
  await expect(nav.getByRole("link").last()).toHaveCSS("font-size", "14px");
  await guest.screenshot({ path: "test-results/ui-home.png", fullPage: true });
  await nav.getByRole("link", { name: "使用指南", exact: true }).click();
  await expect(guest).toHaveURL(/\/articles$/);
  await guest.goto("/account-access");
  const auth = guest.locator(".auth-actions");
  await expect(auth.getByRole("button")).toHaveCount(3);
  await expect(auth.getByRole("button").first()).toHaveCSS("font-size", "14px");
  const buttons = await auth.getByRole("button").evaluateAll((nodes) =>
    nodes.map((n) => {
      const r = n.getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, left: r.left, right: r.right };
    }),
  );
  expect(
    Math.max(...buttons.map((b) => b.top)) -
      Math.min(...buttons.map((b) => b.top)),
  ).toBeLessThan(2);
  const box = await auth.boundingBox();
  expect(
    Math.abs(
      (buttons[0]!.left + buttons[2]!.right) / 2 - (box!.x + box!.width / 2),
    ),
  ).toBeLessThan(3);
  await guest.screenshot({ path: "test-results/ui-auth.png", fullPage: true });
  await field(guest, "邮箱", { exact: true }).fill("member@example.com");
  await field(guest, "密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await guest.getByRole("button", { name: "确认", exact: true }).click();
  await expect(guest).toHaveURL(/\/account$/);
  await expect(field(guest, "个人昵称", { exact: true })).toBeHidden();
  await guest.getByRole("tab", { name: "个人资料与安全", exact: true }).click();
  await guest
    .getByRole("button", { name: "修改资料与密码", exact: true })
    .click();
  await expect(field(guest, "个人昵称", { exact: true })).toBeVisible();
  await expect(
    guest.getByRole("button", { name: "创建捐赠订单", exact: true }),
  ).toHaveCount(0);
  await guest.screenshot({
    path: "test-results/ui-account.png",
    fullPage: true,
  });
  await guest
    .getByRole("dialog", { name: "个人资料与安全", exact: true })
    .locator(".el-dialog__headerbtn")
    .click();
  await guest.getByRole("tab", { name: "API与客服", exact: true }).click();
  await expect(
    guest
      .getByRole("row")
      .filter({ hasText: "API、Key与订阅" })
      .getByRole("link", { name: "进入", exact: true }),
  ).toBeVisible();
  await guest.setViewportSize({ width: 390, height: 844 });
  await guest.screenshot({
    path: "test-results/ui-account-mobile.png",
    fullPage: true,
  });
  expect(
    await guest.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await admin.screenshot({ path: "test-results/ui-admin.png", fullPage: true });
  expect(errors).toEqual([]);
  await guest.close();
  await context.close();
});

// 新一轮核对列表真实来源、代理IP、独立新增、未激活删除与统计钻取，不能只确认按钮存在。
test("merged applications, object provenance, independent creation, pending account deletion and active-user drill", async ({
  browser,
}) => {
  test.setTimeout(210000);
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  const admin = await context.newPage(),
    errors: string[] = [];
  admin.on("pageerror", (e) => errors.push(e.message));
  await admin.goto(staff("/admin/workspace?view=versions"));
  await expect(admin).toHaveURL(/view=applications/);
  await expect(
    admin
      .locator(".management-sidebar")
      .getByRole("button", { name: "版本与文件", exact: true }),
  ).toHaveCount(0);
  await expect(
    admin
      .locator(".management-sidebar")
      .getByRole("button", { name: "发布与静态化", exact: true }),
  ).toHaveCount(0);
  await expect(
    workspace(admin).getByRole("columnheader", { name: "主键ID", exact: true }),
  ).toBeVisible();
  await expect(
    workspace(admin).getByRole("columnheader", { name: "创建人", exact: true }),
  ).toBeVisible();
  const actor = await (
    await admin.request.get(staff("/api/auth/session"))
  ).json();
  const tag = await admin.request.post(staff("/api/admin/catalog/tags"), {
    headers: {
      "X-AIWYW-Request": "1",
      "X-Forwarded-For": "203.0.113.99",
      "X-AIWYW-Peer-IP": "203.0.113.88",
    },
    data: {
      name: "浏览器来源验收标签",
      description: "真实操作者与来源IP",
      revision: 0,
    },
  });
  expect(tag.status()).toBe(200);
  const tagId = (await tag.json()).id;
  const tags = (
    await (
      await admin.request.get(
        staff("/api/admin/catalog/tags?q=") +
          encodeURIComponent("浏览器来源验收标签"),
      )
    ).json()
  ).items;
  expect(tags[0].id).toBe(tagId);
  expect(tags[0].created_by_id).toBe(actor.id);
  expect(Number(tags[0].created_at)).toBeGreaterThan(0);
  const logs = await (
    await admin.request.get(staff("/api/admin/audit"))
  ).json();
  const audit = logs.find((r: any) => r.config_key === "catalog:" + tagId);
  expect(audit).toBeTruthy();
  expect(audit.source_ip).toMatch(/^(127\.0\.0\.1|::1|0:0:0:0:0:0:0:1)$/);
  await menu(admin, "交易管理");
  await menu(admin, "捐赠档位");
  await workspace(admin)
    .getByRole("button", { name: "新增捐赠档位", exact: true })
    .click();
  await expect(workspace(admin).getByRole("table")).toHaveCount(0);
  await field(workspace(admin), "鼓励值包名称", { exact: true }).fill(
    "浏览器验收鼓励值包",
  );
  await admin.screenshot({
    path: "test-results/management-package-editor.png",
    fullPage: true,
    animations: "disabled",
  });
  await workspace(admin)
    .getByRole("button", { name: "保存捐赠档位", exact: true })
    .click();
  await expect(admin).toHaveURL(/view=packages/);
  await expect(
    workspace(admin).getByRole("row").filter({ hasText: "浏览器验收鼓励值包" }),
  ).toBeVisible();
  await menu(admin, "内容运营");
  await menu(admin, "FAQ 与案例");
  for (const kind of ["FAQ", "案例"]) {
    await workspace(admin)
      .getByRole("button", { name: "新增内容", exact: true })
      .click();
    await expect(workspace(admin).getByRole("table")).toHaveCount(0);
    const editor = workspace(admin);
    await field(editor, "内容中文标题", { exact: true }).fill(
      "浏览器独立" + kind + "验收",
    );
    await field(editor, "内容英文标题", { exact: true }).fill(
      "Independent " + kind + " review",
    );
    await field(editor, "内容公开地址", { exact: true }).fill(
      kind === "FAQ" ? "browser-faq-review" : "browser-case-review",
    );
    await select(admin, "内容类型", kind);
    await editor
      .getByRole("textbox", { name: "内容中文正文", exact: true })
      .fill("用于验证独立页签的" + kind + "正文");
    await editor
      .getByRole("textbox", { name: "内容英文正文", exact: true })
      .fill("Independent editor verification");
    await editor.getByRole("button", { name: "保存草稿", exact: true }).click();
    await expect(editor.getByText("草稿已保存", { exact: true })).toBeVisible();
    await menu(admin, "FAQ 与案例");
  }
  await admin.screenshot({
    path: "test-results/management-content-list.png",
    fullPage: true,
    animations: "disabled",
  });
  const pendingEmail = "pending-review@example.com";
  const guest = await browser.newContext();
  expect(
    (
      await guest.request.post("http://127.0.0.1:3000/api/auth/register", {
        headers: { "X-AIWYW-Request": "1" },
        data: { email: pendingEmail, password: "pending-review-password-123" },
      })
    ).status(),
  ).toBe(200);
  const activation = await inboxLink(pendingEmail, "verify");
  await menu(admin, "用户与权限");
  await menu(admin, "用户管理");
  await field(workspace(admin), "邮箱", { exact: true }).fill(pendingEmail);
  await select(admin, "用户状态筛选", "未激活");
  await workspace(admin)
    .getByRole("button", { name: "查询", exact: true })
    .click();
  const pending = workspace(admin)
    .getByRole("row")
    .filter({ hasText: pendingEmail });
  await expect(pending).toBeVisible();
  await pending
    .getByRole("button", { name: "删除未激活", exact: true })
    .click();
  await admin
    .getByRole("dialog", { name: "删除未激活账户", exact: true })
    .getByRole("button", { name: "删除", exact: true })
    .click();
  await expect(pending).toHaveCount(0);
  expect(
    (
      await guest.request.post("http://127.0.0.1:3000/api/auth/verify-email", {
        headers: { "X-AIWYW-Request": "1" },
        data: { token: new URL(activation).searchParams.get("token") },
      })
    ).status(),
  ).toBe(400);
  await guest.close();
  const member = await browser.newContext();
  expect(
    (
      await member.request.post("http://127.0.0.1:3000/api/auth/login", {
        headers: { "X-AIWYW-Request": "1" },
        data: {
          email: "member@example.com",
          password: "brand-new-member-password-456",
          code: "",
        },
      })
    ).status(),
  ).toBe(200);
  expect(
    (
      await member.request.post("http://127.0.0.1:3000/api/public/events", {
        headers: { "X-AIWYW-Request": "1" },
        data: {
          kind: "PAGE",
          path: "/apps",
          keyword: "",
          source: "validation.example.test",
        },
      })
    ).status(),
  ).toBe(200);
  const report = await (
    await admin.request.get(staff("/api/admin/statistics"))
  ).json();
  const drill = await (
    await admin.request.get(
      staff("/api/admin/statistics/active-users?") +
        new URLSearchParams({ from: report.from, until: report.until }),
    )
  ).json();
  expect(Number(drill.total)).toBe(Number(report.traffic.active_users));
  expect(Number(drill.total)).toBeGreaterThan(0);
  await menu(admin, "运营统计");
  await workspace(admin)
    .getByRole("button", { name: "查看活跃用户", exact: true })
    .click();
  await expect(
    workspace(admin).getByRole("heading", { name: "活跃用户", exact: true }),
  ).toBeVisible();
  await expect(
    workspace(admin).getByRole("row").filter({ hasText: "我的新昵称" }),
  ).toBeVisible();
  await admin.screenshot({
    path: "test-results/management-active-users.png",
    fullPage: true,
    animations: "disabled",
  });
  expect(
    await admin.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
  await member.close();
  await context.close();
});

test("new-window details share navigation, managed API visibility and encrypted proxy configuration", async ({
  browser,
}) => {
  test.setTimeout(150000);
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
  });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const admin = await context.newPage(),
    errors: string[] = [];
  admin.on("pageerror", (error) => errors.push(error.message));
  await admin.goto(staff("/admin/workspace?view=apis"));
  await workspace(admin)
    .getByRole("button", { name: "新增API", exact: true })
    .click();
  const panel = workspace(admin);
  await expect(field(panel, "真实服务地址", { exact: true })).toBeVisible();
  await field(panel, "API配置名称", { exact: true }).fill("浏览器真实代理服务");
  await field(panel, "API调用地址", { exact: true }).fill("browser-proxy-api");
  await field(panel, "API中文说明", { exact: true }).fill(
    "用户访问平台地址，由平台转发到管理员配置的真实服务。",
  );
  await field(panel, "真实服务地址", { exact: true }).fill(
    "https://example.com/vendor-json",
  );
  await select(admin, "真实服务请求方式", "GET · 标量查询参数");
  await field(panel, "真实服务请求头", { exact: true }).fill(
    '{"X-API-Key":"browser-private-upstream-token"}',
  );
  await expect(field(panel, "平台调用地址", { exact: true })).toHaveValue(
    /\/api\/v1\/invoke\/browser-proxy-api$/,
  );
  await panel.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    panel.getByText("已保存，已有购买和调用条款不被覆盖", { exact: true }),
  ).toBeVisible();
  await expect(field(panel, "真实服务请求头", { exact: true })).toHaveValue("");
  const apis = await (await admin.request.get(staff("/api/admin/apis"))).json();
  const api = apis.find((a: any) => a.slug === "browser-proxy-api");
  expect(api.request_method).toBe("GET");
  expect(api.headers_set).toBe(true);
  expect(JSON.stringify(apis)).not.toContain("browser-private-upstream-token");
  const publicJson = await (await admin.request.get("/api/public/apis")).json();
  expect(JSON.stringify(publicJson)).not.toContain("vendor-json");
  expect(JSON.stringify(publicJson)).not.toContain(
    "browser-private-upstream-token",
  );
  const page = await context.newPage();
  await page.goto("/api-services?api=browser-proxy-api");
  const details = page.getByRole("link", { name: /查看详情/ }).first();
  await expect(details).toHaveAttribute("target", "_blank");
  const popup = page.waitForEvent("popup");
  await details.click();
  const detail = await popup;
  await expect(
    detail.getByRole("heading", { name: "浏览器真实代理服务", exact: true }),
  ).toBeVisible();
  await expect(detail.locator(".public-breadcrumb")).toContainText("API 服务");
  const footer = detail.locator(".directory-footer");
  await expect(footer).toContainText("工信部 ICP 备案号待填写");
  await expect(
    footer.locator('a[href="/account"],a[href="/articles"],a[href="/support"]'),
  ).toHaveCount(0);
  expect(await footer.evaluate((el) => getComputedStyle(el).textAlign)).toBe(
    "center",
  );
  await detail.screenshot({
    path: "test-results/experience-api-detail.png",
    fullPage: true,
  });
  await detail.close();
  // 旧场景会下架其教程；本场景自行发布案例，不能依赖其他用例残留的已发布数据。
  const createdCase = await admin.request.post(staff("/api/admin/content"), {
    headers: { "X-AIWYW-Request": "1" },
    data: {
      slug: "browser-detail-case",
      kind: "CASE",
      titleZh: "浏览器详情案例",
      titleEn: "Detail case",
      summaryZh: "详情头尾验证",
      summaryEn: "Detail shell",
      bodyZh: "<p>案例正文</p>",
      bodyEn: "<p>Case body</p>",
      revision: 0,
    },
  });
  expect(createdCase.status()).toBe(200);
  const caseId = (await createdCase.json()).id;
  const publishedCase = await admin.request.post(
    staff("/api/admin/content/") + caseId + "/publish",
    { headers: { "X-AIWYW-Request": "1" }, data: { revision: 1 } },
  );
  expect(publishedCase.status()).toBe(200);
  await page.goto("/articles");
  const article = page
    .locator(
      '.content-grid .entry-actions a[href^="/articles/browser-detail-case"]',
    )
    .first();
  await expect(article).toHaveAttribute("target", "_blank");
  const articlePopup = page.waitForEvent("popup");
  await article.click();
  const articlePage = await articlePopup;
  await expect(articlePage.locator(".public-site-header")).toBeVisible();
  await expect(
    articlePage.locator(".public-breadcrumb [aria-current='page']"),
  ).not.toBeEmpty();
  await expect(articlePage.locator(".directory-footer")).toContainText(
    "工信部 ICP 备案号待填写",
  );
  for (const selector of [
    ".published-article > h1",
    ".published-article > h1 + p",
    ".published-article > time",
  ]) {
    await expect(articlePage.locator(selector)).toHaveCSS(
      "text-align",
      "center",
    );
  }
  await expect(articlePage.locator(".public-site-header")).toHaveCSS(
    "position",
    "sticky",
  );
  await articlePage.close();
  const sections = await (
    await admin.request.get(staff("/api/admin/catalog/sections"))
  ).json();
  const nav = sections.find((s: any) => s.config.mode === "API");
  expect(nav).toBeTruthy();
  const saveNav = async (enabled: boolean, revision: number) => {
    const response = await admin.request.put(
      staff("/api/admin/catalog/sections/") + nav.id,
      {
        headers: { "X-AIWYW-Request": "1" },
        data: { ...nav.config, enabled, position: 0, revision },
      },
    );
    expect(response.status()).toBe(200);
  };
  await saveNav(false, nav.revision);
  await page.goto("/");
  await expect(
    page.locator('.directory-header nav a[href="/api-services"]'),
  ).toHaveCount(0);
  await saveNav(true, nav.revision + 1);
  await page.reload();
  await expect(page.locator(".directory-header nav a").last()).toHaveAttribute(
    "href",
    "/api-services",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
  await context.close();
});

test("manual picker retains cross-page selection and homepage shows configured sections", async ({
  browser,
}) => {
  const context = await browser.newContext({
    storageState: join(directory(), "admin-state.json"),
    locale: "zh-CN",
  });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const page = await context.newPage();
  for (let i = 0; i < 21; i++) {
    const response = await page.request.post(
      staff("/api/admin/catalog/products"),
      {
        headers: { "X-AIWYW-Request": "1" },
        data: content(
          "paged-picker-" + i,
          "跨页选品" + String(i).padStart(2, "0"),
        ),
      },
    );
    expect(response.status()).toBe(200);
  }
  await page.goto(staff("/admin/workspace?view=sections"));
  await page
    .getByRole("row")
    .filter({ hasText: "精选工具" })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  const pickerRoot = workspace(page).locator(".selection-picker");
  // count不自动等待异步编辑页挂载；先等加载完成，避免误把未渲染的原选品当作空集合。
  await expect(pickerRoot).toBeVisible();
  while (
    await pickerRoot.getByRole("button", { name: "移除", exact: true }).count()
  )
    await pickerRoot
      .getByRole("button", { name: "移除", exact: true })
      .first()
      .click();
  await pickerRoot
    .getByRole("button", { name: "选择内容", exact: true })
    .click();
  const dialog = page.getByRole("dialog", { name: "选择栏目内容" });
  await field(dialog, "选品搜索", { exact: true }).fill("跨页选品");
  await dialog.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(
    dialog.locator(".el-table__body-wrapper .el-checkbox"),
  ).toHaveCount(20);
  const first = dialog.getByRole("checkbox").first(),
    firstName =
      "选择 " +
      (await dialog
        .locator(".el-table__body-wrapper tbody tr")
        .first()
        .locator("td")
        .nth(1)
        .innerText());
  await dialog.locator(".el-table__body-wrapper .el-checkbox").first().click();
  await dialog.locator(".btn-next").click();
  await expect(
    dialog.locator(".el-table__body-wrapper .el-checkbox"),
  ).toHaveCount(1);
  const secondName =
    "选择 " +
    (await dialog
      .locator(".el-table__body-wrapper tbody tr")
      .first()
      .locator("td")
      .nth(1)
      .innerText());
  await dialog.locator(".el-table__body-wrapper .el-checkbox").first().click();
  await dialog.locator(".btn-prev").click();
  await expect(
    dialog.getByRole("checkbox", { name: firstName!, exact: true }),
  ).toBeChecked();
  await dialog
    .getByRole("button", { name: "确认选择（2）", exact: true })
    .click();
  await expect(pickerRoot.locator(".selected-name strong")).toHaveText([
    firstName!.slice(3),
    secondName!.slice(3),
  ]);
  await pickerRoot
    .getByRole("button", { name: "下移 " + firstName!.slice(3), exact: true })
    .click();
  await expect(pickerRoot.locator(".selected-name strong")).toHaveText([
    secondName!.slice(3),
    firstName!.slice(3),
  ]);
  await pickerRoot
    .getByRole("button", { name: "选择内容", exact: true })
    .click();
  await dialog
    .getByRole("row")
    .filter({ hasText: firstName!.slice(3) })
    .locator(".el-checkbox")
    .click();
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await expect(pickerRoot.locator(".selected-name strong")).toHaveCount(2);
  // 离开验证编辑页时丢弃草稿，不发布合成选品。
  page.on("dialog", (d) => d.accept());
  await page.goto("/");
  const metadata = await (
    await page.request.get("/api/public/catalog?summary=true")
  ).json();
  expect(metadata.products).toEqual([]);
  const configured = await (
    await page.request.get(staff("/api/admin/catalog/sections"))
  ).json();
  const titles = configured
    .filter((row: any) => row.config.enabled)
    .map((row: any) => row.config.titleZh);
  await expect(page.locator(".directory-section h2")).toHaveText(titles);
  await page.goto("/?section=disabled-or-missing");
  await expect(page.locator(".directory-section h2")).toHaveText(titles);
  await page.goto("/api-services");
  const apiCard = page
    .locator(".directory-entry-card")
    .filter({ hasText: "浏览器文本API" });
  await expect(apiCard).toHaveCSS("height", "220px");
  await expect(
    apiCard.getByRole("link", { name: "调试", exact: true }),
  ).toHaveAttribute("href", "/api-services?api=browser-text-api&debug=1");
  await expect(apiCard.locator('[title="下载数"]')).toHaveCount(0);
  await context.close();
});

test("encouragement daily reward, donation ratio and paged account portals", async ({
  browser,
}) => {
  test.setTimeout(180000);
  const adminContext = await browser.newContext({
      storageState: join(directory(), "admin-state.json"),
    }),
    admin = await adminContext.newPage();
  const context = await browser.newContext({ locale: "zh-CN" });
  await context.addCookies([
    { name: "aiwyw_language", value: "zh", url: "http://127.0.0.1:3000" },
  ]);
  const member = await context.newPage();
  await member.goto("/account-access");
  await field(member, "邮箱", { exact: true }).fill("member@example.com");
  await field(member, "密码", { exact: true }).fill(
    "brand-new-member-password-456",
  );
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  const profile = await (
    await member.request.get("/api/member/profile")
  ).json();
  const before = await (
    await member.request.get("/api/member/wallet?summary=true")
  ).json();
  for (let i = 0; i < 5; i++)
    expect((await member.request.get("/api/auth/session")).status()).toBe(200);
  const after = await (
    await member.request.get("/api/member/wallet?summary=true")
  ).json();
  expect(after.balance).toBe(before.balance);
  const daily = await (
    await member.request.get("/api/member/list/points?state=DAILY_LOGIN")
  ).json();
  expect(daily.total).toBe(1);
  expect(daily.items[0].delta_units).toBe("100");
  const nonce = Date.now();
  for (let i = 0; i < 23; i++) {
    const response = await admin.request.post(
      staff("/api/admin/commerce/adjust"),
      {
        headers: { "X-AIWYW-Request": "1" },
        data: {
          userId: profile.user.id,
          points: 1,
          validDays: 0,
          reason: "账户分页验证" + i,
          requestKey: "account-paged-" + nonce + "-" + i,
        },
      },
    );
    expect(response.status()).toBe(200);
  }
  for (const name of [
    "捐赠订单",
    "鼓励值流水",
    "我的资源",
    "退款申请",
    "站内通知",
    "个人资料与安全",
    "API与客服",
  ]) {
    await member.getByRole("tab", { name, exact: true }).click();
    const table = member.locator(".member-area .el-table").first();
    await expect(table.getByRole("columnheader").last()).toHaveText("操作");
    await expect(member.locator(".member-area .record-query")).toBeVisible();
    await expect(member.locator(".member-area .el-pagination")).toBeVisible();
  }
  await member.getByRole("tab", { name: "站内通知", exact: true }).click();
  await member.getByRole("tab", { name: "我的资源", exact: true }).click();
  await member
    .locator(".member-area .el-table")
    .getByRole("columnheader", { name: /^状态/ })
    .click();
  const notificationReset = member.waitForResponse(
    (r) =>
      r.url().includes("/api/member/records/notifications?") &&
      r.url().includes("sort=created_at") &&
      r.status() === 200,
  );
  await member.getByRole("tab", { name: "站内通知", exact: true }).click();
  await notificationReset;
  await field(member, "列表关键词", { exact: true }).fill("账户分页验证");
  await member.getByRole("button", { name: "查询", exact: true }).click();
  await expect(member.locator(".el-pagination__total")).toContainText("23");
  await expect(member.locator(".el-table__body-wrapper tbody tr")).toHaveCount(
    10,
  );
  await member.locator(".el-pagination .btn-next").click();
  await expect(
    member.locator(".el-pagination .el-pager .is-active"),
  ).toHaveText("2");
  const time = await member
    .locator(".el-table__body-wrapper tbody tr")
    .first()
    .locator("td")
    .nth(1)
    .innerText();
  expect(time).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  await member.getByRole("tab", { name: "捐赠订单", exact: true }).click();
  await member.getByRole("button", { name: "新增捐赠", exact: true }).click();
  const donate = member.getByRole("dialog", { name: "新增捐赠", exact: true });
  await field(donate, "捐赠金额", { exact: true }).fill("1");
  await donate
    .getByRole("button", { name: "创建捐赠订单", exact: true })
    .click();
  const order = member.getByRole("dialog", {
    name: "模拟捐赠订单",
    exact: true,
  });
  await expect(order).toBeVisible();
  await expect(
    order.getByRole("heading", { name: "¥1.00", exact: true }),
  ).toBeVisible();
  await order.locator(".el-dialog__headerbtn").click();
  const donations = await (
    await member.request.get("/api/member/list/recharge")
  ).json();
  expect(donations.items[0].price_fen).toBe("100");
  expect(donations.items[0].points_units).toBe("1000");
  await member.goto("/api-services?manage=1");
  for (const name of [
    "接口与套餐",
    "我的Key",
    "我的订阅",
    "在线调试",
    "调用记录",
  ]) {
    await member.getByRole("tab", { name, exact: true }).click();
    await expect(
      member.locator(".api-center>.el-table").getByRole("columnheader").last(),
    ).toHaveText("操作");
    await expect(member.locator(".api-center>.record-query")).toBeVisible();
    await expect(member.locator(".api-center>.el-pagination")).toBeVisible();
  }
  await member.goto("/support");
  await expect(
    member.locator(".el-table").first().getByRole("columnheader").last(),
  ).toHaveText("操作");
  await member.getByRole("tab", { name: "留言与意见", exact: true }).click();
  for (const table of await member
    .locator(".el-tab-pane:visible .el-table")
    .all())
    await expect(table.getByRole("columnheader").last()).toHaveText("操作");
  const article = await admin.request.post(staff("/api/admin/content"), {
    headers: { "X-AIWYW-Request": "1" },
    data: {
      slug: "free-encouragement-article",
      kind: "ARTICLE",
      titleZh: "免费阅读验收文章",
      titleEn: "",
      summaryZh: "文章没有鼓励值兑换",
      summaryEn: "",
      bodyZh: "<p>正文始终可免费阅读。</p>",
      bodyEn: "",
      revision: 0,
    },
  });
  expect(article.status()).toBe(200);
  const id = (await article.json()).id;
  expect(
    (
      await admin.request.post(staff(`/api/admin/content/${id}/publish`), {
        headers: { "X-AIWYW-Request": "1" },
        data: { revision: 1 },
      })
    ).status(),
  ).toBe(200);
  const guest = await browser.newPage();
  await guest.goto("/articles/free-encouragement-article");
  await expect(
    guest.getByRole("heading", { name: "免费阅读验收文章", exact: true }),
  ).toBeVisible();
  await expect(guest.locator("#download,.price")).toHaveCount(0);
  expect(await guest.locator("time").first().innerText()).toMatch(
    /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
  );
  await expect(
    guest.locator('.directory-footer a[href="/support"]'),
  ).toHaveCount(0);
  const products = await (
    await admin.request.get(staff("/api/admin/catalog/products"))
  ).json();
  const product = products.find(
    (row: { slug: string }) => row.slug === "browser-utility",
  );
  expect(product).toBeTruthy();
  const series = {
    slug: "writing-series",
    titleZh: "写作备考专栏",
    titleEn: "Writing Series",
    summaryZh: "按顺序浏览",
    summaryEn: "",
    published: true,
    refs: ["content:" + id, product.id],
    revision: 0,
  };
  const headers = { "X-AIWYW-Request": "1" };
  const created = await admin.request.post(staff("/api/admin/series"), {
    headers,
    data: series,
  });
  expect(created.status()).toBe(200);
  const key = (await created.json()).id;
  const duplicate = await admin.request.post(staff("/api/admin/series"), {
    headers,
    data: { ...series, slug: "another-writing-series", refs: [product.id] },
  });
  expect(duplicate.status()).toBe(200);
  const second = (await duplicate.json()).id;
  expect(key).toMatch(/^[0-9a-v]{26}$/);
  expect(product.id).toMatch(/^[0-9a-v]{26}$/);

  const choices = await admin.request.get(
    staff(
      "/api/admin/content/series-picker?q=writing-series&sort=created_at&direction=asc&pageSize=1",
    ),
  );
  expect(choices.status()).toBe(200);
  const choicesPage = await choices.json();
  expect(choicesPage.total).toBe(2);
  expect(choicesPage.items[0].ref).toBe(key);

  const articlePath = staff("/api/admin/content/" + id);
  const articleState = await (await admin.request.get(articlePath)).json();
  expect(
    (
      await admin.request.post(articlePath + "/publish", {
        headers,
        data: { revision: articleState.revision, seriesIds: [key, second] },
      })
    ).status(),
  ).toBe(200);
  const secondSeries = await (
    await admin.request.get(staff("/api/admin/series/" + second))
  ).json();
  expect(secondSeries.refs).toEqual([product.id, "content:" + id]);

  const items = await admin.request.get(
    staff(
      "/api/admin/series/selection?kind=ARTICLE&sort=published_at&direction=asc&pageSize=1&q=" +
        encodeURIComponent("免费阅读验收文章"),
    ),
  );
  expect(items.status()).toBe(200);
  expect((await items.json()).items[0]).toMatchObject({
    ref: "content:" + id,
    category: "ARTICLE",
    published_at: expect.any(Number),
  });
  await guest.goto("/series/writing-series");
  const contents = guest.locator(".series-contents a");
  await expect(contents).toHaveCount(2);
  await expect(contents.first()).toHaveAttribute(
    "href",
    "/articles/free-encouragement-article",
  );
  await expect(contents.last()).toHaveAttribute(
    "href",
    "/apps/browser-utility",
  );
  const updated = await admin.request.put(staff("/api/admin/series/" + key), {
    headers,
    data: { ...series, refs: series.refs.toReversed(), revision: 1 },
  });
  expect(updated.status()).toBe(200);
  await guest.reload();
  await expect(contents.first()).toHaveAttribute(
    "href",
    "/apps/browser-utility",
  );
  expect(
    (
      await admin.request.put(staff("/api/admin/series/" + key), {
        headers,
        data: { ...series, revision: 1 },
      })
    ).status(),
  ).toBe(409);
  expect(
    (
      await admin.request.put(staff("/api/admin/series/" + key), {
        headers,
        data: { ...series, published: false, revision: 2 },
      })
    ).status(),
  ).toBe(200);
  expect(
    (await guest.request.get("/api/public/series/writing-series")).status(),
  ).toBe(404);
  expect(
    (
      await guest.request.get("/api/public/series/another-writing-series")
    ).status(),
  ).toBe(200);
  const productPath = staff("/api/admin/catalog/products/" + product.id);
  const draft = await (await admin.request.get(productPath)).json();
  expect(
    (
      await admin.request.post(productPath + "/publish", {
        headers,
        data: { revision: draft.revision, seriesIds: [second] },
      })
    ).status(),
  ).toBe(200);
  const removed = await (
    await admin.request.get(staff("/api/admin/series/" + key))
  ).json();
  expect(removed.refs).toEqual(["content:" + id]);

  const sectionPath = staff("/api/admin/catalog/sections");
  const sections = await (await admin.request.get(sectionPath)).json();
  const section = sections.find(
    (row: { config: { mode: string } }) => row.config.mode === "SERIES",
  );
  expect(section).toBeTruthy();
  await guest.goto("/");
  await expect(guest.locator("#" + section.id)).toBeVisible();
  expect(
    (
      await admin.request.put(sectionPath + "/" + section.id, {
        headers,
        data: { ...section.config, revision: section.revision, enabled: false },
      })
    ).status(),
  ).toBe(200);
  await guest.reload();
  await expect(guest.locator("#" + section.id)).toHaveCount(0);
  await expect(guest.locator(".public-site-nav a[href='/series']")).toHaveCount(
    0,
  );
  expect(
    (
      await guest.request.get("/api/public/series/another-writing-series")
    ).status(),
  ).toBe(200);
  await context.close();
  await guest.close();
  await adminContext.close();
});
