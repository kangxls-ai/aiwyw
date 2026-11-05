<script setup lang="ts">
import DOMPurify from "dompurify";
import "summernote/dist/summernote-lite.css";
import { loadSummernote } from "~/utils/summernote.client";
import type { StorageAsset } from "~/types/storage";
const props = defineProps<{ modelValue: string; label: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  busy: [value: boolean];
}>();
const host = ref<HTMLElement | null>(null),
  library = ref(false),
  error = ref(""),
  loading = ref(true),
  inserting = ref(false);
const { upload, busy, progress } = useAssetUpload();
let editor: any = null,
  active = true,
  syncing = false,
  booting = false;
const safeUrl = (url: string) =>
  (url.startsWith("/") &&
    !url.startsWith("//") &&
    !url.includes("..") &&
    !url.includes("\\")) ||
  /^https:\/\//i.test(url);
/** 进入contenteditable前先净化粘贴和导入内容，避免等提交服务器后才处理脚本。 */
function clean(html: string) {
  const result = DOMPurify.sanitize(html, {
    FORBID_TAGS: [
      "iframe",
      "object",
      "embed",
      "video",
      "audio",
      "form",
      "input",
      "svg",
      "math",
      "style",
      "link",
      "meta",
    ],
    ALLOW_DATA_ATTR: false,
  });
  const container = document.createElement("template");
  container.innerHTML = result;
  container.content.querySelectorAll<HTMLElement>("*").forEach((node) => {
    for (const attr of ["href", "src"]) {
      const value = node.getAttribute(attr);
      if (value && !safeUrl(value)) node.removeAttribute(attr);
    }
    const css = node.getAttribute("style");
    if (css) {
      const allowed = new Set([
        "text-align",
        "color",
        "background-color",
        "font-weight",
        "font-style",
        "text-decoration",
        "font-size",
        "line-height",
        "width",
        "height",
        "max-width",
        "margin-left",
      ]);
      node.setAttribute(
        "style",
        css
          .split(";")
          .filter((rule) => {
            const i = rule.indexOf(":");
            return (
              i > 0 &&
              allowed.has(rule.slice(0, i).trim().toLowerCase()) &&
              !/url\s*\(|expression|@import|behavior|[<>]/i.test(rule)
            );
          })
          .join(";"),
      );
    }
  });
  const safe = container.innerHTML;
  return /^<p>(?:<br\s*\/?>|\s|&nbsp;)*<\/p>$/.test(safe) ? "" : safe;
}
async function files(values: FileList | File[]) {
  if (!editor || inserting.value) return;
  inserting.value = true;
  editor.summernote("saveRange");
  error.value = "";
  try {
    for (const file of Array.from(values)) {
      if (!editor || !active) break;
      try {
        const asset = await upload(file, "IMAGE");
        if (editor) {
          editor.summernote("restoreRange");
          editor.summernote("insertImage", asset.url);
          editor.summernote("saveRange");
        }
      } catch (e) {
        error.value = (e as Error).message;
      }
    }
  } finally {
    inserting.value = false;
  }
}
function selected(asset: StorageAsset) {
  library.value = false;
  if (editor) {
    editor.summernote("restoreRange");
    editor.summernote("insertImage", asset.url);
  }
}
async function boot() {
  if (editor || booting || !active || !host.value) return;
  booting = true;
  loading.value = true;
  try {
    const jquery = await loadSummernote();
    if (!active || !host.value) return;
    syncing = true;
    editor = jquery(host.value);
    editor.summernote({
      lang: "zh-CN",
      height: 300,
      // 使用CSS颜色/字重而非旧font标签，与服务端富文本白名单一致。
      styleWithCSS: true,
      dialogsInBody: false,
      // 粘贴只由净化后的onPaste处理，避免Summernote默认剪贴板再重复上传同一张图片。
      allowClipboardImagePasting: false,
      onCreateLink: (url: string) => (safeUrl(url.trim()) ? url.trim() : ""),
      placeholder: "在这里编辑图文内容，可直接粘贴或上传图片。",
      toolbar: [
        ["style", ["style"]],
        ["font", ["bold", "italic", "underline", "clear"]],
        ["color", ["color"]],
        ["para", ["ul", "ol", "paragraph"]],
        ["table", ["table"]],
        ["insert", ["link", "picture", "library"]],
        ["history", ["undo", "redo"]],
        ["view", ["fullscreen"]],
      ],
      buttons: {
        library: () =>
          jquery.summernote.ui
            .button({
              contents: "文件库",
              tooltip: "从文件库插图",
              // Lite自定义按钮必须显式提供提示容器，否则hover/focus时无法读取定位。
              container: document.body,
              click: () => {
                editor.summernote("saveRange");
                library.value = true;
              },
            })
            .render(),
      },
      callbacks: {
        onChange: (html: string) => {
          if (!syncing) emit("update:modelValue", clean(html));
        },
        onImageUpload: (values: File[]) => files(values),
        onImageLinkInsert: (url: string) => {
          if (safeUrl(url)) editor.summernote("insertImage", url);
          else error.value = "图片地址必须是HTTPS链接或站内资源";
        },
        onPaste: (event: ClipboardEvent) => {
          const clipboard =
            event.clipboardData || (event as any).originalEvent?.clipboardData;
          if (!clipboard) return;
          if (clipboard.files?.length) {
            event.preventDefault();
            files(clipboard.files);
            return;
          }
          const html = clipboard.getData("text/html");
          if (html) {
            event.preventDefault();
            editor.summernote("pasteHTML", clean(html));
          }
        },
      },
    });
    editor.summernote("code", clean(props.modelValue));
    editor
      .next(".note-editor")
      .find(".note-editable")
      .attr("aria-label", props.label)
      .attr("role", "textbox")
      .attr("aria-multiline", "true");
  } catch {
    error.value = "编辑器载入失败，请重新打开此页签。";
  } finally {
    syncing = false;
    loading.value = false;
    booting = false;
  }
}
function destroy() {
  if (editor) {
    syncing = true;
    editor.summernote("destroy");
    editor = null;
    syncing = false;
  }
}
watch(
  () => props.modelValue,
  (value) => {
    if (editor && clean(editor.summernote("code")) !== value) {
      syncing = true;
      editor.summernote("code", clean(value));
      syncing = false;
    }
  },
);
watch(
  () => busy.value || inserting.value,
  (value) => emit("busy", value),
);
onMounted(boot);
onActivated(() => {
  active = true;
  boot();
});
onDeactivated(() => {
  active = false;
  destroy();
});
onBeforeUnmount(() => {
  active = false;
  destroy();
});
</script>
<template>
  <div class="summernote-field">
    <span class="asset-control-label">{{ label }}</span>
    <p v-if="loading" class="muted">正在载入编辑器…</p>
    <div ref="host" />
    <p v-if="busy" class="muted">
      图片上传：{{ progress < 100 ? `${progress}%` : "正在保存…" }}
    </p>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <AssetLibraryDialog
      v-if="library"
      purpose="IMAGE"
      @selected="selected"
      @close="library = false"
    />
  </div>
</template>
