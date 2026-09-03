## Why

Today, `ContentFilterService` unconditionally rewrites every `image_url` content block in an inbound chat request into a text description via `VisionService`, regardless of whether the client's target model can already see images itself. This wastes a round trip to the fallback vision model and degrades image fidelity (a text summary instead of the raw image) even when the target model has native vision support. The gateway needs to know, per target model, whether it can accept images natively before deciding whether to intervene.

## What Changes

- `LlmGatewayFilterFactory` looks up the request's target `model` id against a new configurable capability map and determines whether that model has native vision support.
- The capability decision is threaded through `FilterContext` (a new field) so `ContentFilterService.filterContentElement` can branch on it for `image_url` content blocks:
  - Native vision support → the `image_url` block is passed through unchanged.
  - No native vision support (including models absent from the map) → unchanged existing behavior: route the image through `VisionService` and replace the block with a text annotation.
- `application.yaml` gains a `model.capabilities` map of `modelId -> [Capability]`, backed by a Kotlin enum (initially just `VISION`) bound via Spring `@ConfigurationProperties` so invalid capability strings fail fast at startup.
- `OcrGatewayFilterFactory.annotateImages` (the docling/OCR route), which always constructs a `FilterContext` targeting the vision fallback model itself, is updated to remain consistent with the new `FilterContext` shape.
- Out of scope: no changes to how `input_audio` or `file` content blocks are handled. The capability map's shape is generic enough to extend to those later, but only `VISION` is introduced now.

## Capabilities

### New Capabilities
- `model-vision-capability-routing`: The gateway determines, per target model and via configuration, whether a model has native vision support, and only performs manual vision-description routing for models that lack it.

### Modified Capabilities
(none — no existing `openspec/specs/` capabilities exist yet)

## Impact

- `src/main/kotlin/de/quati/deepwater/domain/gateway/LlmGatewayFilterFactory.kt` — computes the capability decision from the parsed `model` field and passes it via `FilterContext`.
- `src/main/kotlin/de/quati/deepwater/domain/gateway/ContentFilterService.kt` — `filterContentElement` branches on the new `FilterContext` field for `image_url`.
- `src/main/kotlin/de/quati/deepwater/domain/vision/ModelConfiguration.kt` — `Properties` gains a `capabilities: Map<String, List<Capability>>` field (default empty map).
- `src/main/kotlin/de/quati/deepwater/domain/gateway/OcrGatewayFilterFactory.kt` — updates its `FilterContext` construction site.
- `src/main/resources/application.yaml` — adds the `model.capabilities` map.
- `src/test/kotlin/de/quati/deepwater/domain/gateway/LlmGatewayFilterFactoryTest.kt` — existing test covers the manual-routing path; a new test covers the native-vision passthrough path.
- No API contract changes visible to gateway clients — this only affects internal routing behavior for image content.
