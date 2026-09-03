## 1. Configuration

- [x] 1.1 Add a `Capability` enum in `de.quati.deepwater.domain.vision` with a single member `VISION`.
- [x] 1.2 Add `capabilities: Map<String, List<Capability>>` (default empty map) to `ModelConfiguration.Properties`, bound under `model.capabilities`.
- [x] 1.3 Add a `model.capabilities` example map to `application.yaml` (e.g. `hippo-vision: [vision]`), leaving other models unlisted.

## 2. Capability lookup

- [x] 2.1 Add a small helper (e.g. `ModelConfiguration.Properties.hasVisionCapability(modelId: String): Boolean`) that returns `true` only if the map contains the model id and its capability list includes `VISION`, `false` otherwise (including when the model id is absent).

## 3. Threading the decision through `FilterContext`

- [x] 3.1 Add a new field to `FilterContext` (e.g. `hasNativeVision: Boolean`) in `LlmGatewayFilterFactory.kt`.
- [x] 3.2 Update `LlmGatewayFilterFactory.mutateRequest` to compute `hasNativeVision` from the parsed `model` via the capability lookup before constructing `FilterContext`.
- [x] 3.3 Update `OcrGatewayFilterFactory.annotateImages` to populate the new `FilterContext` field via the same capability lookup against `modelConfiguration.vision`.

## 4. Branching in `ContentFilterService`

- [x] 4.1 Update `ContentFilterService.filterContentElement`'s `"image_url"` branch: when `context.hasNativeVision` is `true`, return `cEl` unchanged; otherwise keep the existing `VisionService`-backed rewrite.

## 5. Tests

- [x] 5.1 Update `LlmGatewayFilterFactoryTest`'s existing test (or its fixture) so it clearly exercises a model without vision capability, confirming the manual-routing path still rewrites the image to text.
- [x] 5.2 Add a new test to `LlmGatewayFilterFactoryTest` for a model configured with vision capability, asserting the `image_url` content block is forwarded unchanged and `VisionService.processImage` is never invoked.
- [x] 5.3 Add a test for a model absent from the capability map, asserting it falls back to the manual-routing path.
- [x] 5.4 Add a unit test for the capability lookup helper covering: listed with `VISION`, listed with an empty/other list, and absent from the map.

## 6. Verification

- [x] 6.1 Run `./gradlew test` and confirm all tests pass.
