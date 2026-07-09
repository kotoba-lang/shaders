# kotoba-lang/shaders

Kotoba package for `kotoba.shaders`.

## Dependency change (2026-07-09)

`kotoba.shaders` now requires `kami.wgsl` (from `kotoba-lang/webgpu`) directly instead of the
standalone `kotoba-lang/wgsl` repo's `kotoba.wgsl`. The two copies of the WGSL-as-data compiler
were byte-identical (no behavioural bug, unlike the `sprite-gpu` case), but only `kami.wgsl`
receives ongoing feature/bugfix work — see `kotoba-lang/wgsl`'s CHANGELOG.md for the full writeup.
`kotoba-lang/wgsl` itself now re-exports `kami.wgsl` too, so this change is behaviour-preserving
either way — it just removes an extra hop.

## `kotoba.shaders` vs. `kami.shaders` (2026-07-09 dedup pass)

This repo's own `kotoba.shaders` namespace was also checked against `kotoba-lang/webgpu`'s
internal `src/kami/shaders.cljc`, following the same "clj-wgsl Phase-4 incident" pattern that
caused real drift for `kotoba-lang/sprite-gpu`/`gpu`/`webgl`. **Conclusion: no code change
needed here.**

- **Content**: diffing the two (normalizing `kotoba.*`→`kami.*`) found them identical except for
  docstring wording (this repo's mentions the `kami.wgsl` dependency and the fact that
  `kami.webgpu` runs the generated shader; `kami.shaders` — already living inside `webgpu` —
  phrases the same relationship without needing to call out a cross-repo dependency). Every
  function body and shader fragment matches byte-for-byte.
- **History**: `kotoba-lang/webgpu`'s `kami.shaders` was built up over several commits on
  2026-06-24 (`be5de28`→`500c33f`, "lit + shadow shaders single-sourced web↔native (parity by
  source, drift fixed)"). This repo's copy was wiped by the Phase-4 scaffold and restored on
  2026-07-02 (`2fb3bdc`) to content that already matched that fully-developed state — the
  restore was not behind.
- **Tests**: both sides carry an equivalent `test/shader_test.clj` (same assertions; webgpu's
  adds a `run-tests` CI-gate tail).
- **Consumers**: a repo-wide `grep -rn "kotoba-lang/shaders" --include=deps.edn` across `orgs/`
  and a source-level grep for `kotoba.shaders` requires found **zero external consumers**.

Since the content is already in sync and nothing external depends on this namespace, converting
it into a live re-export (the `sprite-gpu`/`gpu`/`webgl` pattern) would only add plumbing with no
consumer to benefit from it. This repo is safe to keep as a self-contained, currently-in-sync
duplicate; if a real external consumer of `kotoba.shaders` ever appears, re-evaluate whether to
convert it to a re-export at that point (or point the new consumer at `kami.shaders` directly).

## Test

```sh
clojure -M:test
```
