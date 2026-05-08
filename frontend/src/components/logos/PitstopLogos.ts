/**
 * Logo concepts for pitstop. Each is a small SVG component the user can
 * preview from /logos and pick. Stored as inline SVG so the bundle size
 * stays trivial and we never depend on raster assets.
 *
 * The shared <PitstopLogo :name="..." :size="..."> component renders
 * whichever the user has selected (persisted to localStorage).
 */
export type LogoName =
  | "arc"
  | "arc-redline"
  | "arc-ticks"
  | "arc-dotted"
  | "arc-segments"
  | "arc-needle"
  | "arc-360"
  | "wrench-gauge"
  | "lights"
  | "spark"
  | "wheel"
  | "tread-p";

export interface LogoMeta {
  name: LogoName;
  label: string;
  description: string;
}

export const LOGOS: LogoMeta[] = [
  {
    name: "arc",
    label: "Speedo arc",
    description: "Tachometer arc with a needle dot at the redline.",
  },
  {
    name: "arc-redline",
    label: "Speedo + redline",
    description: "Same arc; the last quarter is warn-coloured to mark the redline.",
  },
  {
    name: "arc-ticks",
    label: "Speedo + ticks",
    description: "Arc with five tick marks (0/¼/½/¾/full), needle at ¾.",
  },
  {
    name: "arc-dotted",
    label: "Speedo dotted",
    description: "Arc rendered as a row of dots, with a brighter dot at the needle tip.",
  },
  {
    name: "arc-segments",
    label: "Speedo zones",
    description: "Three coloured arc segments — green / amber / warn — and a needle.",
  },
  {
    name: "arc-needle",
    label: "Needle only",
    description: "No arc; just the needle and pivot. The most reductive of the set.",
  },
  {
    name: "arc-360",
    label: "Full tach",
    description: "Closed circular tach with a single tick at 12 o'clock and a needle.",
  },
  {
    name: "wrench-gauge",
    label: "Gauge + wrench",
    description: "Dial gauge crossed with a small wrench — service feel.",
  },
  {
    name: "lights",
    label: "Start lights",
    description: "Three F1-style start lights stacked vertically.",
  },
  {
    name: "spark",
    label: "Spark + bolt",
    description: "Stylized spark plug with an ignition bolt.",
  },
  {
    name: "wheel",
    label: "Steering wheel",
    description: "Minimal three-spoke steering wheel ring.",
  },
  {
    name: "tread-p",
    label: "Tread P",
    description: "Tire tread forming a P-mark.",
  },
];

export const STORAGE_KEY = "pitstop_logo";
export const DEFAULT_LOGO: LogoName = "arc";
