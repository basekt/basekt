# BaseKt: contributor and agent guide

BaseKt is a set of small Ktor libraries for building a backend and its admin. Read [README.md](README.md) for what
each module is. This file holds the rules every module follows.

## Design rules

1. **One capability, one module, one Ktor plugin.** A module never needs a sibling it doesn't use. Anything shared goes
   down into `core:basekt-schema` or the module's SPI, never sideways.
2. **SPIs, not concrete dependencies.** Data, auth and storage each have a small interface. Adapters live in their own
   modules (`basekt-data-exposed`, `basekt-data-memory`...), and the server depends only on the SPI.
3. **No business words.** BaseKt knows resources, fields, relations, roles and permissions. It never knows a product, a
   player or an invoice. Those are the user's schema.
4. **The schema is data at runtime.** No annotation processing is required. A KSP generator may come later as an
   option, never the only way.
5. **Plain Ktor.** Plugins are built with `createApplicationPlugin`, with typed configuration and `MonitoringEvent`
   lifecycle. Configuration comes from `ApplicationConfig`. A library never creates the engine, never reads
   environment variables itself, and never holds global state.
6. **Typed and validated.** Definitions are `@Serializable` and validated with clear messages. Errors that reach a
   client are typed.
7. **Explicit API.** Every module builds with `explicitApi()` and has KDoc on every public declaration.
8. **Tests prove it's generic:** `testApplication` tests per plugin, the in-memory adapter for speed, and at least two
   made-up schemas that use a module differently.

## Skills to load

These are from the `kmp-agent-skills` plugin (`claude plugin install kmp-agent-skills@kmp-agent-skills`):

| Work | Skill |
|---|---|
| Plugin structure, configuration, lifecycle, DI, `testApplication` | `kmp-ktor-server` |
| Realtime and WebSocket modules | `kmp-ktor-realtime` |
| Auth adapters | `kmp-ktor-auth-service` |
| Module boundaries | `kmp-clean-architecture` |
| Coroutines and cancellation | `kmp-coroutines-flow-patterns` |
| Publishing to Maven Central | `kmp-library-publishing` |

## PR checklist

- [ ] One capability per module. No new sideways dependency between sibling modules.
- [ ] New backends go behind the module's SPI. The server code doesn't change for an adapter.
- [ ] No business vocabulary in identifiers.
- [ ] No required code generation. The schema works as runtime data.
- [ ] Ktor plugins with typed config and lifecycle hooks. No engine, environment reads or globals in a library.
- [ ] Explicit API with KDoc, and serializable, validated definitions.
- [ ] `testApplication` tests, with two made-up schemas.
- [ ] `./gradlew build` passes.
