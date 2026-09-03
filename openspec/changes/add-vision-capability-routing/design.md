## Context

See proposal.md - Why. Today `ContentFilterService.filterContentElement` unconditionally routes every `image_url` block through `VisionService` using the fixed `model.vision` fallback model, regardless of the request's actual target `model`. `LlmGatewayFilterFactory` already parses `model` out of the request body and constructs `FilterContext`, which flows into `ContentFilterService`. `OcrGatewayFilterFactory.annotateImages` also constructs a `FilterContext`, but always targets the vision fallback model itself (the docling/OCR route), so it is trivially "native vision" in the new model.

## Goals / Non-Goals

**Goals:**
- Let `LlmGatewayFilterFactory` decide, per request, whether the target model has native vision support, using a configurable map.
- Only skip manual vision routing when the target model is explicitly known to support vision.
- Keep today's behavior as the default for any model not explicitly configured.

**Non-Goals:**
- No routing decisions for `input_audio` or `file` content blocks in this change.
- No dynamic/runtime capability discovery (e.g., querying the upstream provider) — configuration only.
- No change to the OCR/docling route's own behavior beyond keeping its `FilterContext` construction consistent.

## Decisions

**Capability representation: enum-backed list per model, not a boolean flag.**
`model.capabilities: Map<String, List<Capability>>` where `Capability` is a Kotlin enum with a single member, `VISION`, for now. Spring's relaxed binding will bind yaml string values (e.g. `vision`) to the enum and fail startup on an unrecognized value (e.g. `visoin`), satisfying the spec's "fails to start rather than silently ignoring" requirement. Alternative considered: a flat `model.vision-capable-models: List<String>`. Rejected because it hard-codes the map to a single capability; the enum-list shape costs one extra config level now but requires no reshaping when `AUDIO`/`FILE` capabilities are added later.

**Default for unlisted or capability-less models: no native vision.**
A model absent from the map, or present with an empty/non-`VISION` list, is treated as lacking native vision support. This exactly preserves current behavior for every model that isn't explicitly opted in, so rollout requires zero config changes to avoid regressions — only additive config to opt specific models into passthrough.

**Decision threading: computed once in `LlmGatewayFilterFactory`, carried via `FilterContext`.**
`LlmGatewayFilterFactory.mutateRequest` already extracts `model` before building `FilterContext`. It performs the capability lookup there and adds a new field to `FilterContext` (e.g. `hasNativeVision: Boolean`) rather than having `ContentFilterService` perform its own lookup. This keeps the capability registry lookup in one place and keeps `ContentFilterService` a pure dispatcher that branches on context it's given, matching its existing `context(_: FilterContext)` pattern. Alternative considered: inject the capability registry into `ContentFilterService` directly. Rejected because `LlmGatewayFilterFactory` is the natural owner of "what does this request target," per the proposal's framing, and centralizing the lookup avoids a second config dependency in `ContentFilterService`.

**`ContentFilterService.filterContentElement` branches on the new field for `image_url` only.**
```
"image_url" -> if (context.hasNativeVision) cEl
               else handleImageUrl(cEl)?.let { cache.getOrPut(...) { visionService.processImage(it.second) } }?.toJsonObject()
```
No change to `text`, `input_audio`, or `file` branches.

**`OcrGatewayFilterFactory.annotateImages` construction site.**
It builds `FilterContext(model = modelConfiguration.vision, ...)`. This route's job is producing the image description in the first place (`ocrService.annotated` internally calls `visionService.processImage` unconditionally) — its `FilterContext` isn't used for a passthrough decision, so this call site is only updated to satisfy `FilterContext`'s new field. For consistency, the field is populated via the same capability lookup used elsewhere (looking up `modelConfiguration.vision` itself) rather than hardcoded, so the field's meaning stays uniform across both construction sites, even though `OcrGatewayFilterFactory`'s behavior doesn't change either way.

## Risks / Trade-offs

- **Config drift**: an operator adds a new vision-capable model upstream but forgets to update `model.capabilities` → images silently keep going through manual routing instead of failing loudly. Mitigation: this is the same safe-default direction as today's behavior (degrades to a working text description, not an error), so the failure mode is a missed optimization, not a broken request.
- **Enum rigidity**: adding a second capability later (e.g. `AUDIO`) is additive to the enum and map value type, not a breaking change to this design.

## Migration Plan

- No data migration. `model.capabilities` defaults to an empty map if omitted from `application.yaml`, which reproduces current behavior exactly (every model routes through manual vision handling).
- Roll out by deploying the code change with an empty/absent `model.capabilities` map first (no behavior change), then incrementally add known vision-capable model ids.
- Rollback: revert the config addition (or the deploy) — no persisted state depends on the new field.
