import { readFile } from "node:fs/promises";
import { timingSafeEqual } from "node:crypto";
import { resolve } from "node:path";

let savedEntry: string | undefined;

export async function matchesStaffEntry(candidate: string) {
  if (!savedEntry) {
    try {
      const entry = (
        await readFile(
          resolve(
            process.env.AIWYW_SERVER_DATA_DIR || "backend/data",
            "staff-entry",
          ),
          "utf8",
        )
      ).trim();
      if (!/^[0-9a-f]{32}$/.test(entry)) return false;
      savedEntry = entry;
    } catch {
      return false;
    }
  }
  return timingSafeEqual(Buffer.from(savedEntry), Buffer.from(candidate));
}

export function mandatoryStaffApi(path: string) {
  return /^\/api\/(?:admin|auth\/mfa)(?:\/|$)/.test(path);
}

export function scopedStaffApi(path: string) {
  return (
    mandatoryStaffApi(path) ||
    /^\/api\/(?:auth\/(?:login|logout|session)|member\/(?:profile|nickname|password))(?:$)/.test(
      path,
    ) ||
    /^\/api\/(?:support\/assets|member\/evidence)\/(?:[0-9a-v]{26}|[0-9a-f-]{36})$/.test(
      path,
    )
  );
}
