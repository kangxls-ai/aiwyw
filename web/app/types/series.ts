import type { DirectoryItem } from "./catalog";
export interface SeriesCard {
  slug: string;
  titleZh: string;
  titleEn: string;
  summaryZh: string;
  summaryEn: string;
}
export interface SeriesDetail {
  series: SeriesCard;
  items: DirectoryItem[];
}
