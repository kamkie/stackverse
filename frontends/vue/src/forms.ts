import type { FieldError } from "./api/problem";

export type FieldErrorMap = Record<string, string>;

export function toFieldErrorMap(errors: FieldError[]): FieldErrorMap {
  const fields: FieldErrorMap = {};
  for (const error of errors) {
    const field = error.field.split(/[.[\]]/)[0];
    if (field && fields[field] === undefined) fields[field] = error.message;
  }
  return fields;
}

export function formatDateTime(value: string, language = "en"): string {
  return new Date(value).toLocaleString(language);
}

export function tagsFromInput(value: string): string[] {
  return value
    .split(/[\s,]+/)
    .map((tag) => tag.trim())
    .filter(Boolean);
}

export function tagsToInput(tags: string[] | undefined): string {
  return (tags ?? []).join(" ");
}
