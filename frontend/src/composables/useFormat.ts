import { format, formatDistanceToNow, parseISO } from "date-fns";

export function fmtNumber(
  value: number | null | undefined,
  opts: { digits?: number; suffix?: string; placeholder?: string } = {},
): string {
  if (value == null || Number.isNaN(value)) return opts.placeholder ?? "—";
  const digits = opts.digits ?? 1;
  const v = Number(value).toFixed(digits);
  return opts.suffix ? `${v} ${opts.suffix}` : v;
}

export function fmtMpg(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "mpg" });
}
export function fmtMiles(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "mi" });
}
export function fmtGallons(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 2, suffix: "gal" });
}
export function fmtMoney(v: number | null | undefined, currency = "$"): string {
  if (v == null || Number.isNaN(v)) return "—";
  return `${currency}${Number(v).toFixed(2)}`;
}
export function fmtPct(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "%" });
}
export function fmtTemp(v: number | null | undefined, unit = "°F"): string {
  return fmtNumber(v, { digits: 0, suffix: unit });
}
export function fmtRpm(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 0, suffix: "rpm" });
}
export function fmtSpeed(v: number | null | undefined, unit = "mph"): string {
  return fmtNumber(v, { digits: 0, suffix: unit });
}

export function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null || Number.isNaN(seconds) || seconds < 0) return "—";
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec.toString().padStart(2, "0")}s`;
  return `${sec}s`;
}

export function fmtDate(iso: string | null | undefined, pattern = "yyyy-MM-dd"): string {
  if (!iso) return "—";
  try {
    return format(parseISO(iso), pattern);
  } catch {
    return iso;
  }
}
export function fmtDateTime(iso: string | null | undefined): string {
  return fmtDate(iso, "yyyy-MM-dd HH:mm");
}
export function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return "—";
  try {
    return formatDistanceToNow(parseISO(iso), { addSuffix: true });
  } catch {
    return iso;
  }
}
