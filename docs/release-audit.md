# ContractScrolls Release Audit

This audit reflects the current release-prep tree after the April 22, 2026 cleanup pass.

## Fixed In This Pass

- The repository now has a real `.gitignore` and no longer carries IDE metadata, checked-in Gradle outputs, or stray vendored Cloud source copies as publish noise.
- GitHub Actions CI now runs `./gradlew build check` on pushes and pull requests.
- Basic unit tests now cover config parsing, contract hash verification, and legacy color stripping.
- Gradle now targets `paper-api:1.21.11-R0.1-SNAPSHOT` and the local run task matches `1.21.11`, instead of mixing `1.21.1` compile/run targets with newer Paper servers.
- Proposal creation now enforces `maxContractsPerPlayer` for both parties, matching the documented config behavior.
- Written books and writable books are both parsed through the documented Bukkit/Paper book meta APIs, instead of only accepting `WritableBookMeta`.
- Proposal creation now fails closed if the held book cannot be consumed. The contract is no longer persisted after an item-consumption failure.
- If a proposal fee is enabled, the fee is only finalized after the held book has been safely consumed, and the book is restored when the charge fails.
- Admin-only inventories now re-check `contractscrolls.staff` before opening or handling clicks, instead of trusting only the command/UI entry point.
- Admin player search now uses a UUID-backed player cache seeded once and refreshed incrementally, instead of routing menus through name-only lookups.
- Template pages are clamped to Bukkit/Paper book limits before they are written to item meta.

## Security Review

### Dupe / item safety

- Main dupe surface was the proposal flow: a contract could proceed even after the book-consume step failed. That is now blocked.
- Template application replaces the main-hand item safely and moves the previous item to an empty slot only when needed. It does not clone-and-keep the original main-hand stack.
- Failed persistence restores the consumed proposal book and refunds the configured Vault fee.

### Permission boundaries

- Staff-only views are now guarded in both commands and GUI handlers.
- Non-staff players are prevented from opening another player’s contracts through internal menu paths.
- Requirement bypass now remains explicit only through the configured bypass UUID list.

### Data integrity

- Contract terms are still hashed at creation time and checked on admin view.
- Contract state transitions remain main-thread initiated and persist through a single-writer queue, which avoids async Bukkit access and reduces race windows.
- Expired, denied, revoked, and fully signed states are rejected in the signing and denial flows.

## Remaining Release Risks

### Must add before a public release

- Automated tests are still missing. Add at least:
  - command flow tests
  - book/template application tests
  - contract state transition tests
  - persistence fallback tests
- CI is still missing. A release branch should fail on build/test/lint regressions automatically.

### Still worth improving

- `BookUtil.openWritableBookEditor()` still uses reflected CraftBukkit/NMS internals because Paper exposes `openBook()` for written books, not a public writable-book editor API.
- There is no target-side rate limit yet. A proposer cooldown exists, but a determined player can still spread proposals across many targets over time.
- The plugin still trusts Vault provider behavior for exact currency semantics. This is normal for Vault, but the final server economy should be tested with the actual provider in use.
- `ContractStore` keeps all contracts in memory. That is acceptable for moderate scale, but very large long-term archives may need pagination or cold-storage tooling later.
- MySQL no longer silently falls back to SQLite during startup or writes, because that behavior could fork contract history across two backends.

## Performance Review

- Current hot paths are acceptable for a Paper plugin with normal SMP load.
- The biggest menu optimization from this pass is avoiding repeated `getOfflinePlayers()` scans.
- Persistence is already off the main thread through the SQL write queue, which is the correct direction for Paper safety.

## Recommended Tooling To Add Next

- MockBukkit for command, inventory, and item-meta test coverage.
- `detekt` for Kotlin static analysis.
- `ktlint` for formatting drift and style guardrails.
- Expand GitHub Actions to include lint and release-artifact validation once those checks exist.

## Documentation Used

- Paper `BookMeta` and `WritableBookMeta` javadocs:
  - `https://jd.papermc.io/paper/1.21.11/org/bukkit/inventory/meta/BookMeta.html`
  - `https://jd.papermc.io/paper/1.21.11/org/bukkit/inventory/meta/WritableBookMeta.html`
- Paper `Bukkit#getOfflinePlayerIfCached` / `Bukkit#getOfflinePlayers` javadocs:
  - `https://jd.papermc.io/paper/1.21.11/org/bukkit/Bukkit.html`
- Paper player book-opening javadocs/source used in earlier template-editor work:
  - `https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Player.html`
  - `https://raw.githubusercontent.com/PaperMC/Paper/main/paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java`
