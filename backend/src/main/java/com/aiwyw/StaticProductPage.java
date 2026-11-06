package com.aiwyw;

import static com.aiwyw.ContentHtml.escape;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * 生成可脱离商品查询接口阅读的完整详情页。只接收净化后的公开快照。 页面脚本只切换语言、上报浏览及在用户点击时申请交付；不读取私有下载地址或钱包。
 * 整个脚本使用固定CSP摘要，富文本即使含script也无法获得执行许可。
 */
public final class StaticProductPage {
  private StaticProductPage() {}

  public static final String SCRIPT =
      """
(()=>{
  const root=document.documentElement,slug=root.dataset.slug;
  const cookie=document.cookie.split('; ').find(v=>v.startsWith('aiwyw_language='));
  let lang=new URLSearchParams(location.search).get('lang')||(cookie?cookie.split('=')[1]:navigator.language.startsWith('zh')?'zh':'en');
  function change(value){lang=value==='zh'?'zh':'en';root.lang=lang;root.dataset.language=lang;document.cookie='aiwyw_language='+lang+';path=/;max-age=31536000;SameSite=Lax';}
  change(lang);document.querySelector('#language').addEventListener('click',()=>change(lang==='zh'?'en':'zh'));
  const headers={'X-AIWYW-Request':'1'};
  fetch('/api/public/catalog/'+slug+'/view',{method:'POST',headers}).catch(()=>{});
  const button=document.querySelector('#download'),status=document.querySelector('#delivery-status');
  button.addEventListener('click',async()=>{button.disabled=true;status.textContent='';try{
    const response=await fetch('/api/public/catalog/'+slug+'/download',{method:'POST',headers});
    const data=await response.json();if(!response.ok)throw new Error(data.message||'暂时无法下载');
    location.assign(data.url);
  }catch(error){status.textContent=error.message}finally{button.disabled=false}});
})();
""";

  public static String scriptHash() {
    try {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(SCRIPT.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String render(Catalog.Product product, Map<String, String> settings) {
    Catalog.ProductInput p = product.content();
    String name = escape(p.nameZh()),
        english = escape(p.nameEn().isBlank() ? p.nameZh() : p.nameEn());
    String canonical =
        "https://"
            + settings.getOrDefault("site.domain", "www.aiwyw.com")
            + "/apps/"
            + product.slug();
    StringBuilder screenshots = new StringBuilder();
    for (String url : p.screenshots())
      screenshots
          .append("<a href=\"")
          .append(escape(url))
          .append("\" target=\"_blank\" rel=\"noopener noreferrer\"><img loading=\"lazy\" src=\"")
          .append(escape(url))
          .append("\" alt=\"")
          .append(name)
          .append(" 应用截图\"></a>");
    String icon =
        p.iconUrl().isBlank()
            ? "<span class=\"letter\">" + escape(p.nameZh().substring(0, 1)) + "</span>"
            : "<img src=\"" + escape(p.iconUrl()) + "\" alt=\"\">";
    return """
<!doctype html><html lang="zh-CN" data-language="zh" data-product-status="PUBLISHED" data-slug="%s"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s · %s</title><meta name="description" content="%s">
<link rel="canonical" href="%s"><link rel="alternate" hreflang="zh-CN" href="%s?lang=zh"><link rel="alternate" hreflang="en" href="%s?lang=en">
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#343c53;font:15px/1.8 system-ui,'Microsoft YaHei',sans-serif}
a{color:#7064e8;text-decoration:none}button{font:inherit;cursor:pointer}header{background:#fff;border-bottom:1px solid #e6e8f4;padding:24px max(24px,calc((100%% - 1160px)/2));display:flex;align-items:center;justify-content:space-between}
.brand{font-size:21px;font-weight:700;color:#343c53}.brand span{display:inline-grid;place-items:center;width:42px;height:42px;margin-right:12px;background:#7866ed;color:#fff;border-radius:12px}header button{border:1px solid #e5defa;background:#fff;border-radius:7px;padding:7px 12px;color:#7064e8}
main{max-width:1160px;margin:36px auto;padding:0 20px}.back{display:block;margin-bottom:20px;color:#747d94}.hero,.screenshots,.body{background:#fff;border:1px solid #e5e9f3;border-radius:15px;padding:34px;margin-bottom:24px}
.hero{display:flex;gap:28px;align-items:center;min-height:240px}.app-icon{width:96px;height:96px;flex-shrink:0;border-radius:24px;background:#eee9ff;display:grid;place-items:center;overflow:hidden}.app-icon img{width:100%%;height:100%%;object-fit:cover}.letter{font-size:44px;color:#7866ed}.info{flex:1}h1{font-size:30px;line-height:1.3;margin:0 0 15px}h2{font-size:22px}p{color:#68748b}.version{font-size:13px;color:#818ba0}#download{background:#7866ed;color:white;border:0;border-radius:9px;padding:13px 22px;white-space:nowrap}#download:disabled{opacity:.6}.price{color:#7866ed;text-align:center;margin-bottom:10px}.notice{display:none;color:#a5643f;font-weight:600}[data-product-status=UNLISTED] .notice{display:block}[data-product-status=UNLISTED] #download{display:none}
.gallery{display:flex;gap:20px;overflow-x:auto}.gallery img{max-height:360px;max-width:100%%;border-radius:9px}.body{overflow-wrap:anywhere}.body img{max-width:100%%;height:auto}.body pre{overflow-x:auto;padding:15px;background:#f5f6fa}.body table{border-collapse:collapse;width:100%%}.body td,.body th{border:1px solid #dfe4ee;padding:8px 12px}.body blockquote{border-left:3px solid #b8aaef;padding-left:18px;color:#68748b}.tags{display:flex;gap:8px;flex-wrap:wrap}.tags span{background:#f1edff;padding:2px 9px;border-radius:5px;font-size:12px;color:#786baf}#delivery-status{display:block;color:#a33b4c;max-width:250px;font-size:13px}footer{max-width:1120px;margin:30px auto;padding:20px;color:#818ba0;font-size:12px}
[data-language=zh] .en,[data-language=en] .zh{display:none}@media(max-width:650px){header{padding:18px 20px}.hero{flex-wrap:wrap;padding:24px;gap:20px}.app-icon{width:68px;height:68px}.info{min-width:180px}.action{width:100%%}#download{width:100%%}h1{font-size:25px}.body,.screenshots{padding:22px}main{margin-top:24px}}
</style></head><body><header><a class="brand" href="/"><span>万</span>%s</a><button id="language" aria-label="切换语言">中文 / English</button></header>
<main><a class="back" href="/apps"><span class="zh">← 所有应用</span><span class="en">← All applications</span></a>
<section class="hero"><div class="app-icon">%s</div><div class="info"><h1><span class="zh">%s</span><span class="en">%s</span></h1><p class="zh">%s</p><p class="en">%s</p><div class="tags">%s</div><p class="version">%s · %s</p><div class="notice"><span class="zh">此应用已下架，已购资源请在我的资源访问。</span><span class="en">Unlisted. Existing customers can access their resources in their account.</span></div></div>
<div class="action"><div class="price">%s <span class="zh">鼓励值</span><span class="en">encouragement</span></div><button id="download"><span class="zh">%s</span><span class="en">%s</span></button><span id="delivery-status" role="status"></span></div></section>
%s<section class="body"><h2><span class="zh">应用介绍</span><span class="en">About this application</span></h2><div class="zh">%s</div><div class="en">%s</div></section></main><footer>%s · %s</footer><script>%s</script></body></html>
"""
        .formatted(
            escape(product.slug()),
            name,
            escape(settings.getOrDefault("site.name", "AI万有网")),
            escape(p.summaryZh()),
            escape(canonical),
            escape(canonical),
            escape(canonical),
            escape(settings.getOrDefault("site.name", "AI万有网")),
            icon,
            name,
            english,
            escape(p.summaryZh()),
            escape(p.summaryEn().isBlank() ? p.summaryZh() : p.summaryEn()),
            p.tags().stream()
                .map(tag -> "<span>" + escape(tag) + "</span>")
                .collect(java.util.stream.Collectors.joining()),
            escape(p.version()),
            escape(p.type()),
            p.pricePoints().stripTrailingZeros().toPlainString(),
            p.type().equals("API") ? "进入API服务" : p.pricePoints().signum() == 0 ? "下载应用" : "兑换与下载",
            p.type().equals("API")
                ? "API services"
                : p.pricePoints().signum() == 0 ? "Download" : "Redeem and download",
            screenshots.isEmpty()
                ? ""
                : "<section class=\"screenshots\"><h2><span class=\"zh\">应用截图</span><span"
                    + " class=\"en\">Screenshots</span></h2><div class=\"gallery\">"
                    + screenshots
                    + "</div></section>",
            p.bodyZh(),
            p.bodyEn().isBlank() ? p.bodyZh() : p.bodyEn(),
            escape(settings.getOrDefault("site.name", "AI万有网")),
            escape(settings.getOrDefault("site.domain", "www.aiwyw.com")),
            SCRIPT);
  }
}
