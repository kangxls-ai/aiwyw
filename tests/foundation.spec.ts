import { test, expect, type Page } from "@playwright/test";
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { createHmac } from "node:crypto";
import QRCode from "qrcode";

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
async function configuration(page: Page, values: Record<string, string>) {
  const entries = await (await page.request.get("/api/admin/settings")).json();
  const changes = Object.entries(values).map(([key, value]) => ({
    key,
    value,
    revision: entries.find((e: any) => e.definition.key === key).revision,
    clear: false,
  }));
  const result = await page.request.put("/api/admin/settings", {
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
  await page
    .getByLabel("一次性安装码")
    .fill(readFileSync(join(directory(), "install.token"), "utf8").trim());
  await page
    .getByLabel("邮箱", { exact: true })
    .fill("browser-admin@example.com");
  await page
    .getByLabel("密码", { exact: true })
    .fill("browser-test-admin-password-9182");
  await page
    .getByRole("button", { name: "测试数据库连接", exact: true })
    .click();
  await expect(page.getByText("连接测试成功")).toBeVisible();
  await page.getByRole("button", { name: /完成安装/ }).click();
  await expect(page.getByRole("heading", { name: "安装已完成" })).toBeVisible();
  await page.getByRole("link", { name: /前往登录/ }).click();
  await page
    .getByLabel("邮箱", { exact: true })
    .fill("browser-admin@example.com");
  await page
    .getByLabel("密码", { exact: true })
    .fill("browser-test-admin-password-9182");
  await page.getByRole("button", { name: /登录后台/ }).click();
  await expect(page).toHaveURL(/admin\/security$/);
  expect((await page.request.get("/api/admin/settings")).status()).toBe(403);
  await page.getByRole("button", { name: "开始绑定" }).click();
  const secret = (await page.locator(".secret-code").innerText()).trim();
  await page.getByLabel("6位动态码").fill(otp(secret));
  await page.getByRole("button", { name: "确认绑定" }).click();
  await expect(
    page.getByRole("heading", { name: "双重验证已启用" }),
  ).toBeVisible();
  await page.getByRole("link", { name: /进入配置管理/ }).click();
  await page
    .context()
    .storageState({ path: join(directory(), "admin-state.json") });
  await page
    .getByLabel("网站名称", { exact: true })
    .fill("AI万有网 浏览器验证");
  await page.getByRole("button", { name: /保存修改/ }).click();
  await expect(page.locator(".alert[role=status]:visible")).toHaveText(
    "配置已保存",
  );
  await page.getByLabel("网站名称", { exact: true }).fill("尚未保存");
  await page
    .getByRole("button", { name: "关闭配置管理页签", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await expect(page.getByLabel("网站名称", { exact: true })).toHaveValue(
    "尚未保存",
  );
  await page
    .getByLabel("网站名称", { exact: true })
    .fill("AI万有网 浏览器验证");
  await page
    .locator(".settings-groups")
    .getByRole("button", { name: "邮件服务", exact: true })
    .click();
  await page
    .getByLabel("SMTP密码或授权码", { exact: true })
    .fill("isolated-mail-password");
  await page.getByRole("button", { name: /保存修改/ }).click();
  await expect(
    page.getByLabel("SMTP密码或授权码", { exact: true }),
  ).toHaveValue("");
  expect(
    await (await page.request.get("/api/admin/settings")).text(),
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
  const png = await QRCode.toBuffer("AIWYW ISOLATED FILE FIXTURE");
  await page
    .locator(".settings-groups")
    .getByRole("button", { name: "充值收款", exact: true })
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
  await editor.getByLabel("应用名称", { exact: true }).fill("浏览器工具");
  await editor.getByLabel("英文名称", { exact: true }).fill("Browser utility");
  await editor.getByLabel("公开地址", { exact: true }).fill("browser-utility");
  await editor.getByLabel("版本", { exact: true }).fill("1.0.0");
  await editor.getByLabel("简介", { exact: true }).fill("真实目录端到端验证");
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
  const tags = editor.getByRole("combobox", { name: "应用标签", exact: true });
  for (const tag of ["办公效率", "文本处理"]) {
    await tags.fill(tag);
    await expect(
      page.getByRole("option", { name: tag, exact: true }),
    ).toBeVisible();
    await page.getByRole("option", { name: tag, exact: true }).click();
  }
  await tags.press("Escape");
  await upload(page, "上传应用图标", {
    name: "utility-icon.png",
    mimeType: "image/png",
    buffer: png,
  });
  await expect(page.getByAltText("应用图标", { exact: true })).toBeVisible();
  await upload(page, "上传添加截图", {
    name: "utility-screenshot.png",
    mimeType: "image/png",
    buffer: png,
  });
  await expect(page.getByAltText("截图1", { exact: true })).toBeVisible();
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
  await page.getByLabel("栏目名称", { exact: true }).fill("精选工具");
  await page.locator(".el-checkbox").filter({ hasText: "浏览器工具" }).click();
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
  await page
    .getByRole("dialog")
    .getByLabel("标签名称", { exact: true })
    .fill("未使用标签");
  await page
    .getByRole("dialog")
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
  await page.getByRole("button", { name: "查询", exact: true }).click();
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
  await page
    .locator(".application-editor:visible")
    .getByLabel("应用名称", { exact: true })
    .fill("并行草稿");
  await page
    .locator(".application-editor:visible")
    .getByLabel("公开地址", { exact: true })
    .fill("parallel-draft");
  await page
    .getByRole("tab", { name: "编辑：浏览器工具", exact: true })
    .click();
  await expect(
    page
      .locator(".application-editor:visible")
      .getByLabel("应用名称", { exact: true }),
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
    page
      .locator(".application-editor:visible")
      .getByLabel("应用名称", { exact: true }),
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
        await page.request.post("/api/admin/catalog/products", {
          headers: { "X-AIWYW-Request": "1" },
          data: content("paging-" + i, "分页应用" + i),
        })
      ).status(),
    ).toBe(200);
  }
  await page.getByLabel("名称或关键字", { exact: true }).fill("分页应用");
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
  await page
    .locator(".application-editor:visible")
    .getByLabel("应用名称", { exact: true })
    .fill("保存失败仍保留");
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
  await page.getByRole("link", { name: "更多", exact: true }).first().click();
  await page.getByRole("button", { name: "办公效率", exact: true }).click();
  await page.reload();
  await expect(page.locator(".catalog-app-card")).toHaveCount(1);
  await page.locator(".catalog-details-link").click();
  await expect(
    page.getByRole("heading", { name: "工具使用说明", exact: true }),
  ).toBeVisible();
  const download = page.waitForEvent("download");
  await page.locator("#download").click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("utility.zip");
  expect(readFileSync((await file.path())!, "utf8")).toBe(
    "isolated-browser-download",
  );
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
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("member-password-123456");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member.getByText(/验证邮件将发送/)).toBeVisible();
  const verify = await inboxLink("member@example.com", "verify");
  await member.goto(verify);
  await member
    .getByRole("button", { name: "确认验证邮箱", exact: true })
    .click();
  await expect(member).toHaveURL(/account-access\?action=login/);
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("member-password-123456");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member
    .getByRole("button", { name: "创建充值订单", exact: true })
    .click();
  await expect(
    member.getByRole("dialog", { name: "模拟充值订单" }),
  ).toBeVisible();
  await member.getByRole("button", { name: "我已付款", exact: true }).click();
  await expect(member.getByText(/付款声明已提交/)).toBeVisible();
  const memberNickname = (
    await (await member.request.get("/api/member/profile")).json()
  ).user.nickname;
  await admin.goto("/admin/workspace?view=recharge");
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
  await admin
    .getByLabel("逐单处理原因", { exact: true })
    .fill("模拟充值核实通过");
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
      await (await admin.request.get("/api/admin/catalog/products")).json()
    ).find((p: any) => p.slug === "browser-utility").content.downloadUrl,
  };
  const created = await admin.request.post("/api/admin/catalog/products", {
    headers: { "X-AIWYW-Request": "1" },
    data: body,
  });
  expect(created.status()).toBe(200);
  const id = (await created.json()).id;
  const draft = await (
    await admin.request.get("/api/admin/catalog/products/" + id)
  ).json();
  expect(
    (
      await admin.request.post(
        "/api/admin/catalog/products/" + id + "/publish",
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
    .fill("软件无法使用，请整单退积分");
  await member
    .getByRole("dialog")
    .getByRole("button", { name: "提交", exact: true })
    .click();
  await admin.goto("/admin/workspace?view=refunds");
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
  await admin
    .getByLabel("逐单处理原因", { exact: true })
    .fill("交付问题核实，整单退积分并撤销权益");
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
  ).toBe("100.00");
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
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  const reset = await inboxLink("member@example.com", "reset");
  await member.goto(reset);
  await member
    .getByLabel("密码", { exact: true })
    .fill("updated-member-password-123");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("updated-member-password-123");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  expect(errors).toEqual([]);
  await context.close();
  await adminContext.close();
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
    const result = await admin.request.post("/api/admin/catalog/products", {
      headers,
      data: content(
        "sort-" + runId + "-" + String(i).padStart(2, "0"),
        titlePrefix + String(i).padStart(2, "0"),
      ),
    });
    expect(result.status()).toBe(200);
  }
  await admin.goto("/admin/workspace?view=applications");
  await admin.getByLabel("名称或关键字", { exact: true }).fill(titlePrefix);
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await expect(admin.locator(".el-pagination")).toContainText("23");
  const box = await admin
    .getByLabel("名称或关键字", { exact: true })
    .boundingBox();
  const label = await admin
    .locator(".el-form-item")
    .filter({ has: admin.getByLabel("名称或关键字", { exact: true }) })
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
  await admin.getByLabel("名称或关键字", { exact: true }).fill("markdo");
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin
    .locator(".el-table__body-wrapper")
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await admin
    .getByRole("button", { name: "填入MarkDo介绍", exact: true })
    .click();
  await admin.getByRole("button", { name: "填入介绍", exact: true }).click();
  await expect(admin.getByLabel("应用名称", { exact: true })).toHaveValue(
    "MarkDo · 本地待办与局域网同步",
  );
  expect(
    (await admin.getByLabel("版本", { exact: true }).boundingBox())!.width,
  ).toBeLessThanOrEqual(180);
  await admin.screenshot({
    path: "test-results/compact-markdo-editor.png",
    fullPage: true,
  });
  // 单独新页面进入授权管理，避免关闭未保存介绍时的确认流程影响本次授权场景。
  admin.once("dialog", (dialog) => dialog.accept());
  await admin.reload();
  const created = await admin.request.post("/api/admin/catalog/products", {
    headers,
    data: {
      ...content(licenseSlug, licenseName, 10),
      deliveryMode: "LICENSE",
    },
  });
  expect(created.status()).toBe(200);
  const id = (await created.json()).id;
  await admin.goto("/admin/workspace?view=licenses");
  await select(admin, "授权策略应用", licenseName);
  await admin
    .getByRole("button", { name: "保存授权策略", exact: true })
    .click();
  await expect(
    admin.getByText("策略已保存；请重新发布应用后生效", { exact: true }),
  ).toBeVisible();
  const draft = await (
    await admin.request.get("/api/admin/catalog/products/" + id)
  ).json();
  expect(
    (
      await admin.request.post(
        "/api/admin/catalog/products/" + id + "/publish",
        { headers, data: { revision: draft.revision } },
      )
    ).status(),
  ).toBe(200);
  await member.goto("/account-access");
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("updated-member-password-123");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member.goto("/checkout?slug=" + licenseSlug);
  await member.getByRole("button", { name: "确认兑换", exact: true }).click();
  await expect(member).toHaveURL(/account\?resource=/);
  const order = new URL(member.url()).searchParams.get("resource")!;
  const row = member.getByRole("row").filter({ hasText: licenseName });
  await row.getByRole("button", { name: "激活授权", exact: true }).click();
  const dialog = member.getByRole("dialog");
  await expect(dialog.getByLabel("激活密钥", { exact: true })).toBeVisible();
  const key = await dialog.getByLabel("激活密钥", { exact: true }).inputValue();
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
  await dialog
    .getByLabel("换机原因", { exact: true })
    .fill("旧电脑故障需要换机");
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
    data: { reason: "软件无法使用申请整单退积分" },
  });
  expect(
    (
      await admin.request.post(
        "/api/admin/commerce/refunds/" + order + "/review",
        {
          headers,
          data: {
            approve: true,
            reason: "核实整单退积分并撤销激活",
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
  await admin.goto("/admin/workspace?view=roles");
  await admin.getByRole("button", { name: "新增角色", exact: true }).click();
  const editor = admin.getByRole("dialog");
  await editor.getByLabel("角色名称", { exact: true }).fill("目录查看员");
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
    await (await admin.request.get("/api/admin/roles")).json()
  ).items.find((r: any) => r.name === "目录查看员");
  const create = await admin.request.post("/api/admin/accounts", {
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
  await member.goto("/login");
  await member
    .getByLabel("邮箱", { exact: true })
    .fill("multi-role@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("multi-role-password-123");
  await member.getByRole("button", { name: /登录后台/ }).click();
  await expect(member).toHaveURL(/admin\/security/);
  expect(
    (await member.request.get("/api/admin/catalog/products")).status(),
  ).toBe(403);
  await configuration(admin, { "security.mfa_required": "false" });
  await member.reload();
  expect(
    (await member.request.get("/api/admin/catalog/products")).status(),
  ).toBe(200);
  expect((await member.request.get("/api/admin/settings")).status()).toBe(403);
  const session = await (await member.request.get("/api/auth/session")).json();
  expect(session.roles).toContain("FINANCE");
  expect(session.nickname).toMatch(/^(?=.*[a-zA-Z])(?=.*[0-9])[a-zA-Z0-9]+$/);
  expect(
    (
      await member.request.put("/api/member/nickname", {
        headers: { "X-AIWYW-Request": "1" },
        data: { nickname: "工具收藏家" },
      })
    ).status(),
  ).toBe(200);
  await member.goto("/admin/workspace?view=applications");
  await expect(member.locator(".management-banner")).toContainText(
    "工具收藏家",
  );
  await configuration(admin, { "security.mfa_required": "true" });
  expect(
    (await member.request.get("/api/admin/catalog/products")).status(),
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
  await admin.goto("/admin/workspace?view=roles");
  await admin.getByRole("button", { name: "新增角色", exact: true }).click();
  const editor = admin.getByRole("dialog"),
    parent = editor
      .locator(".el-tree-node__content")
      .filter({ hasText: /^商品管理$/ }),
    child = editor
      .locator(".el-tree-node__content")
      .filter({ hasText: /^应用管理$/ });
  await editor.getByLabel("角色名称", { exact: true }).fill("树形授权验收");
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
    await (await admin.request.get("/api/admin/roles")).json()
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
  await member.goto("/login");
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
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("updated-member-password-123");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await member.getByLabel("个人昵称", { exact: true }).fill("我的新昵称");
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
  await member
    .getByLabel("当前密码", { exact: true })
    .fill("wrong-password-123");
  await member
    .getByLabel("新密码", { exact: true })
    .fill("brand-new-member-password-456");
  await member
    .getByLabel("确认新密码", { exact: true })
    .fill("different-new-password-123");
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(
    member.getByText("两次输入的新密码不一致", { exact: true }),
  ).toBeVisible();
  await member
    .getByLabel("确认新密码", { exact: true })
    .fill("brand-new-member-password-456");
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(
    member.getByText("当前密码不正确", { exact: true }),
  ).toBeVisible();
  await member
    .getByLabel("当前密码", { exact: true })
    .fill("updated-member-password-123");
  await member.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(member).toHaveURL(/account-access\?changed=1/);
  expect(
    (
      await other.request.get("http://127.0.0.1:3000/api/member/profile")
    ).status(),
  ).toBe(401);
  await other.close();
  await member.getByLabel("邮箱", { exact: true }).fill("member@example.com");
  await member
    .getByLabel("密码", { exact: true })
    .fill("brand-new-member-password-456");
  await member.getByRole("button", { name: "确认", exact: true }).click();
  await expect(member).toHaveURL(/\/account$/);
  await expect(member.getByLabel("个人昵称", { exact: true })).toHaveValue(
    "我的新昵称",
  );
  await member.close();
  await adminContext.close();
});
