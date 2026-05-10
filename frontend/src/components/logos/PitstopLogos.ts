/**
 * Logo concepts for pitstop. The brand mark is a 270° tachometer arc
 * with a redline segment at the upper-right; this file holds the
 * baseline plus a small set of refinement variants the user can
 * preview from /logos and pick. Stored as inline SVG so the bundle
 * stays trivial and we never depend on raster assets.
 *
 * The shared <PitstopLogo :name="..." :size="..."> component renders
 * whichever variant the user has selected (persisted to localStorage).
 */
export type LogoName =
  | "arc-ticks-redline"
  | "arc-ticks-redline-needle"
  | "arc-ticks-redline-fine"
  | "arc-ticks-redline-bold"
  | "arc-ticks-redline-fill";

export interface LogoMeta {
  name: LogoName;
  label: string;
  description: string;
}

export const LOGOS: LogoMeta[] = [
  {
    name: "arc-ticks-redline",
    label: "Redline (baseline)",
    description:
      "270° tach arc with major ticks every 45°; the last 40° runs warn-coloured. The current default.",
  },
  {
    name: "arc-ticks-redline-needle",
    label: "Redline + needle",
    description:
      "Adds a clean diagonal needle pointing at the redline boundary. Strong directional cue without crowding the silhouette.",
  },
  {
    name: "arc-ticks-redline-fine",
    label: "Redline · fine ticks",
    description:
      "Same baseline plus six minor sub-ticks halfway between the majors. Reads as instrument-grade at large sizes.",
  },
  {
    name: "arc-ticks-redline-bold",
    label: "Redline · bold",
    description:
      "Thicker arc + ticks + a larger hub. Heavier presence; better at small sizes (sidebar, favicon).",
  },
  {
    name: "arc-ticks-redline-fill",
    label: "Redline · capped",
    description:
      "Solid filled redline segment that fades into the cool body via gradient. Hot-metal feel.",
  },
];

export const STORAGE_KEY = "pitstop_logo";
export const DEFAULT_LOGO: LogoName = "arc-ticks-redline-needle";
