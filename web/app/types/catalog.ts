/** 公开接口与后台草稿使用相同形状，但公开downloadUrl恒为空，禁止前端推导私有交付地址。 */
export interface ProductContent {
  slug: string;
  nameZh: string;
  nameEn: string;
  summaryZh: string;
  summaryEn: string;
  bodyZh: string;
  bodyEn: string;
  tags: string[];
  iconUrl: string;
  screenshots: string[];
  type: string;
  pricePoints: number;
  version: string;
  deliveryMode: string;
  downloadUrl: string;
  revision: number;
}
export interface CatalogProduct {
  id: string;
  slug: string;
  content: ProductContent;
  revision: number;
  status: string;
  publicationId: string | null;
  firstPublishedAt: number;
  views: number;
  downloads: number;
}
export interface SectionConfig {
  titleZh: string;
  titleEn: string;
  subtitleZh: string;
  subtitleEn: string;
  icon: string;
  enabled: boolean;
  position: number;
  mode: string;
  sort: string;
  days: number;
  limit: number;
  tags: string[];
  productIds: string[];
  revision: number;
}
export interface AdminSection {
  createdAt: number;
  id: string;
  config: SectionConfig;
  revision: number;
}
export interface PublicSection {
  id: string;
  kind: "APPLICATIONS" | "ARTICLES" | "API" | "SERIES";
  href: string;
  titleZh: string;
  titleEn: string;
  subtitleZh: string;
  subtitleEn: string;
  icon: string;
  limit: number;
  products: CatalogProduct[];
}
export interface PublicCatalog {
  products: CatalogProduct[];
  sections: PublicSection[];
}

export interface DirectoryItem {
  ref: string;
  kind: string;
  name: string;
  nameEn?: string;
  summary?: string;
  summaryEn?: string;
  slug?: string;
  href?: string;
  priceUnits?: string;
  views?: number;
  product?: CatalogProduct;
}
export interface DirectoryPage {
  items: DirectoryItem[];
  page: number;
  total: number;
  tags: string[];
}
export interface HomeSection {
  id: string;
  kind: string;
  titleZh: string;
  titleEn: string;
  subtitleZh: string;
  subtitleEn: string;
  icon: string;
  items: DirectoryItem[];
}
