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
