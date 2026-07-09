export function formatDate(value: string, locale: string): string {
  return new Date(value).toLocaleString(locale);
}

export function endOfDayIso(day: string): string {
  return new Date(`${day}T23:59:59.999`)
    .toISOString()
    .replace(".999Z", ".999999Z");
}
