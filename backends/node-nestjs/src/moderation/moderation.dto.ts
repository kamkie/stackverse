import { ContractField } from "../validation.pipe.js";

export const REPORT_REASONS = ["spam", "offensive", "broken-link", "other"] as const;
export const REPORT_STATUSES = ["open", "dismissed", "actioned"] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];

const optionalText = (value: unknown, max: number, key: string): string | undefined =>
  value === null || value === undefined || (typeof value === "string" && value.length <= max) ? undefined : key;

export class ReportBodyDto {
  @ContractField((value) =>
    (REPORT_REASONS as readonly unknown[]).includes(value) ? undefined : "validation.report.reason.invalid",
  )
  reason!: string;

  @ContractField((value) => optionalText(value, 1000, "validation.report.comment.too-long"))
  comment: string | null = null;
}

export class ResolutionBodyDto {
  @ContractField((value) =>
    (REPORT_STATUSES as readonly unknown[]).includes(value) ? undefined : "validation.resolution.invalid",
  )
  resolution!: ReportStatus;

  @ContractField((value) => optionalText(value, 1000, "validation.resolution.note.too-long"))
  note: string | null = null;
}

export class BookmarkStatusBodyDto {
  @ContractField((value) =>
    value === "active" || value === "hidden" ? undefined : "validation.bookmark-status.invalid",
  )
  status!: "active" | "hidden";

  @ContractField((value) => optionalText(value, 1000, "validation.bookmark-status.note.too-long"))
  note: string | null = null;
}
