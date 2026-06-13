// ESLint flat config (ESLint 9+). Lints TS + Vue SFCs. Kept lenient on
// purpose — the build (vue-tsc) is the real type gate; this catches the
// obvious foot-guns (unused vars, accidental `any`, debugger) without
// blocking on stylistic noise. Prettier owns formatting (eslint-config-
// prettier disables conflicting rules). `pnpm lint` is NOT wired into the
// build, per the convention.
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import vue from "eslint-plugin-vue";
import prettier from "eslint-config-prettier";
import globals from "globals";

export default tseslint.config(
  {
    ignores: ["dist/**", "node_modules/**", "*.config.js", "*.config.ts"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs["flat/recommended"],
  {
    files: ["**/*.vue"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
  },
  {
    files: ["**/*.{ts,vue}"],
    languageOptions: {
      globals: {
        ...globals.browser,
        // Ambient DOM lib type used in type positions (maplibre sources).
        GeoJSON: "readonly",
      },
    },
    rules: {
      "@typescript-eslint/no-explicit-any": "warn",
      // TS already enforces exhaustive returns; the lint rule fires false
      // positives on exhaustive switch-as-expression computeds.
      "vue/return-in-computed-property": "off",
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      // SFCs are single-instance views; multi-word-name rule is noise here.
      "vue/multi-word-component-names": "off",
      // Prettier owns formatting; attribute ordering is stylistic noise.
      "vue/attributes-order": "off",
      "vue/attribute-hyphenation": "off",
    },
  },
  {
    files: ["**/*.{test,spec}.ts"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  prettier,
);
