# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Test
./gradlew test

# Run a single test class
./gradlew test --tests "de.quati.deepwater.DeepwaterApplicationTests"
```

## Architecture

**deepwater** is a Spring Cloud Gateway application written in Kotlin. It acts as a reactive API gateway built on Spring WebFlux (non-blocking, Project Reactor).

- **Stack**: Spring Boot 4.x, Spring Cloud Gateway (WebFlux), Kotlin 2.x, Java 21, Kotlin Coroutines + Reactor
- **Entry point**: `DeepwaterApplication.kt` — standard `@SpringBootApplication` bootstrap
- **Config**: `src/main/resources/application.yaml` — gateway routes, filters, and predicates are declared here

Gateway routing logic lives in `application.yaml` (or can be defined programmatically via `RouteLocator` beans). Filters and predicates follow Spring Cloud Gateway conventions. Coroutines are available for custom filter/handler code via `kotlinx-coroutines-reactor`.