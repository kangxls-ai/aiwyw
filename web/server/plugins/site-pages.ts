import {
  publicPageSlot,
  readPublicPage,
  writePublicPage,
} from "../utils/site-pages";

export default defineNitroPlugin((nitro) => {
  const headers = {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "private, no-cache",
    vary: "Accept-Language, Cookie",
  };

  nitro.hooks.hook("render:before", async (context) => {
    const slot = publicPageSlot(context.event);
    if (!slot) return;
    context.event.context.publicPageSlot = slot;
    const body = await readPublicPage(slot);
    if (body) {
      context.event.context.publicPageHit = true;
      context.response = {
        body,
        statusCode: 200,
        headers: { ...headers, "x-aiwyw-static": "hit" },
      };
    }
  });

  nitro.hooks.hook("render:response", async (response, { event }) => {
    const slot = event.context.publicPageSlot;
    if (
      !slot ||
      event.context.publicPageHit ||
      event.context.publicSiteUnavailable ||
      (response.statusCode ?? 200) !== 200 ||
      typeof response.body !== "string"
    )
      return;

    try {
      if (await writePublicPage(slot, response.body)) {
        response.headers = {
          ...response.headers,
          ...headers,
          "x-aiwyw-static": "published",
        };
      }
    } catch (error) {
      // 文件不可写时仍能按原方式渲染；后台手动发布会明确报告失败。
      console.error("Unable to publish public page", error);
    }
  });
});
