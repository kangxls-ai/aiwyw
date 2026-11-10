// 静态页面只向运营接口提交公开路径与来源域。
const lang = new URLSearchParams(location.search).get("lang");
if (lang === "zh" || lang === "en") {
  document.cookie =
    "aiwyw_language=" + lang + "; Path=/; Max-Age=31536000; SameSite=Lax";
}
let source = "";
try {
  source = new URL(document.referrer).hostname;
} catch {}
const send = (url, body) =>
  fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: { "X-AIWYW-Request": "1", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
send("/api/public/events", {
  kind: "PAGE",
  path: location.pathname.replace(/\/$/, "") || "/",
  keyword: "",
  source,
}).catch(() => {});
for (const button of document.querySelectorAll("[data-static-download]")) {
  button.addEventListener("click", async () => {
    button.disabled = true;
    try {
      const result = await send(
        "/api/public/catalog/" +
          encodeURIComponent(button.dataset.staticDownload) +
          "/download",
        {},
      );
      const data = await result.json();
      if (!result.ok || !data.url)
        throw new Error(data.message || button.dataset.error);
      location.assign(data.url);
    } catch (error) {
      document.querySelector("#static-delivery-error").textContent =
        error.message;
    } finally {
      button.disabled = false;
    }
  });
}
