/** 地址是稳定资源引用，不含本地路径、Bucket密钥或长期签名。 */
export interface StorageAsset {
  id: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  purpose: "IMAGE" | "DOWNLOAD";
  status: string;
  provider: string;
  createdAt: number;
  publicVisible: boolean;
  referenceCount: number;
  url: string;
  previewUrl: string;
}
export interface StoragePage {
  items: StorageAsset[];
  total: number;
  page: number;
  pageSize: number;
}
