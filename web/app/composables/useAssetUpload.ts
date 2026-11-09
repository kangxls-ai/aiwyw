import type { StorageAsset } from "~/types/storage";
/** 所有上传入口调用同一个服务端适配器；客户端不持有S3密钥，也不自行决定物理存储位置。 */
export function useAssetUpload() {
  const { staffApi } = useStaffPaths();
  const busy = ref(false),
    progress = ref(0),
    error = ref(""),
    changed = useState("storage-changed", () => 0);
  let current: XMLHttpRequest | null = null;
  async function upload(
    file: File,
    purpose: "IMAGE" | "DOWNLOAD",
  ): Promise<StorageAsset> {
    if (busy.value) throw new Error("请等待当前文件上传完成");
    busy.value = true;
    progress.value = 0;
    error.value = "";
    try {
      return await new Promise<StorageAsset>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        current = xhr;
        xhr.open("POST", staffApi("/api/admin/storage/assets"));
        xhr.setRequestHeader("X-AIWYW-Request", "1");
        xhr.responseType = "json";
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable)
            progress.value = Math.round((e.loaded / e.total) * 100);
        };
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            changed.value++;
            resolve(xhr.response as StorageAsset);
          } else
            reject(
              new Error(xhr.response?.message || "文件上传失败，请稍后重试"),
            );
        };
        xhr.onerror = () => reject(new Error("上传连接中断，请重试"));
        xhr.onabort = () => reject(new Error("上传已取消"));
        const body = new FormData();
        body.append("file", file);
        body.append("purpose", purpose);
        xhr.send(body);
      });
    } catch (e) {
      error.value = (e as Error).message;
      throw e;
    } finally {
      current = null;
      busy.value = false;
    }
  }
  function cancel() {
    current?.abort();
  }
  onBeforeUnmount(cancel);
  return { busy, progress, error, upload, cancel };
}
