# Dependency provenance

GTNH compatibility target: **2.9.0**, released 2026-10-31.

- [Official release history](https://www.gtnewhorizons.com/version-history/).
- [GTNH 2.9.0 manifest](https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/master/releases/manifests/2.9.0.json): GT5-Unofficial 5.09.54.265, ServerUtilities 2.4.22, UniMixins 0.3.1.
- [GT source tag](https://github.com/GTNewHorizons/GT5-Unofficial/tree/5.09.54.265), commit `bb00f3f1023e20d3cce84cf09abb442d22a3a5a5`.
- [ServerUtilities source tag](https://github.com/GTNewHorizons/ServerUtilities/tree/2.4.22).
- Build bootstrap: [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10), GTNH Gradle plugin 2.0.33, shared settings 0.2.2, wrapper 9.7.1. The local project does not auto-update these versions.
- Monitoring images: `prom/prometheus:v3.16.0`, `grafana/grafana:13.3.1`; both pulled and used in local validation.

GT and ServerUtilities are compile-only dependencies and are not bundled in the resulting jar. No upstream GT source is copied into the mod. Mixin hooks refer to the published GT classes. Cached upstream source, test server distributions, and downloaded tools live only in ignored `.cache/`.
