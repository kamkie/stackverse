import { Transform } from "class-transformer";
import { ContractField } from "../validation.pipe.js";

export const VISIBILITIES = ["private", "public"] as const;
export type Visibility = (typeof VISIBILITIES)[number];

const TAG_PATTERN = /^[a-z0-9-]{1,30}$/;

const httpUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0;
  } catch {
    return false;
  }
};

const trimString = ({ value }: { value: unknown }): unknown => (typeof value === "string" ? value.trim() : value);

export class BookmarkBodyDto {
  @Transform(trimString)
  @ContractField((value) => {
    if (typeof value !== "string" || value === "") return "validation.url.required";
    if (value.length > 2000 || !httpUrl(value)) return "validation.url.invalid";
    return undefined;
  })
  url!: string;

  @Transform(trimString)
  @ContractField((value) => {
    if (typeof value !== "string" || value === "") return "validation.title.required";
    return value.length > 200 ? "validation.title.too-long" : undefined;
  })
  title!: string;

  @ContractField((value) =>
    value === null || value === undefined || (typeof value === "string" && value.length <= 4000)
      ? undefined
      : "validation.notes.too-long",
  )
  notes: string | null = null;

  @Transform(({ value }: { value: unknown }) => {
    if (!Array.isArray(value)) return value;
    return [...new Set(value.map((tag) => (typeof tag === "string" ? tag.trim().toLowerCase() : tag)))];
  })
  @ContractField((value) => {
    if (!Array.isArray(value) || value.some((tag) => typeof tag !== "string")) {
      return "validation.tag.invalid";
    }
    if (value.length > 10) return "validation.tags.too-many";
    return value.every((tag) => TAG_PATTERN.test(tag as string)) ? undefined : "validation.tag.invalid";
  })
  tags: string[] = [];

  @ContractField((value) =>
    (VISIBILITIES as readonly unknown[]).includes(value) ? undefined : "validation.visibility.invalid",
  )
  visibility: Visibility = "private";
}
