# v0.4.6 — Skills Matrix

Honest inventory. This session's skill catalog was inspected; a skill is marked
**LOCAL** only when it is present in the installed catalog and was actually
loaded/used, **REMOTE-ONLY** when it exists only as an external reference, and
**NOT-INSTALLED** when the brief named it but the harness does not provide it.

## Used in this session (LOCAL)

| Skill | Status | Used for |
|---|---|---|
| `using-superpowers` | LOCAL — loaded (supplied inline) | route packet, evidence-first discipline, no fabricated tool calls |
| `deep-understanding` | LOCAL — present in catalog | requirement + source reading (applied as the Phase 0 source review) |
| `systematic-debugging` | LOCAL | the D1/D2/D3 defect confirmation (read source → prove with SQLite/real runs → fix) |
| `test-driven-development` | LOCAL | RED-before-GREEN ordering for Phase 1 (see 02 plan) |
| `verification-before-completion` | LOCAL | every claim in this handoff is tied to an executed command |
| `writing-plans` | LOCAL | `02-IMPLEMENTATION-PLAN.md` |
| `brainstorming` | LOCAL | architecture options for R1 before committing |
| `code-review-excellence` | LOCAL | the v0.4.5 review pass (FINDING-1..10) |
| `open-code-review` | LOCAL — available | not invoked this turn (no `ocr` CLI configured); recorded as available-not-used |
| `understand-*` (`understand-diff`, `understand-explain`) | LOCAL — available | not invoked this turn |
| `dispatching-parallel-agents` | LOCAL — available | not needed (single-threaded verification) |
| `performance-optimization` | LOCAL — available | Phase 2/7 reference only |
| `security-and-hardening` | LOCAL — available | signing/privacy review reference |
| `documentation-and-adrs` | LOCAL — available | this handoff set |
| `ci-cd-and-automation` | LOCAL — available | Phase 7 gates reference |

## Addy Osmani 24-topic series

Source: `https://github.com/addyosmani/agent-skills` (external). The brief asked
to map all 24 topics. Status per topic:

| Topic | Local availability |
|---|---|
| using-agent-skills | REMOTE-ONLY (local equivalent: `using-superpowers`) |
| brainstorming | LOCAL |
| planning-and-task-breakdown | LOCAL (as `writing-plans`) |
| incremental-implementation | LOCAL |
| test-driven-development | LOCAL |
| debugging-and-error-recovery | LOCAL (`systematic-debugging`) |
| code-review-and-quality | LOCAL |
| code-simplification | LOCAL |
| api-and-interface-design | LOCAL |
| spec-driven-development | LOCAL |
| source-driven-development | LOCAL |
| doubt-driven-development | LOCAL |
| documentation-and-adrs | LOCAL |
| git-workflow-and-versioning | LOCAL |
| using-git-worktrees | LOCAL |
| ci-cd-and-automation | LOCAL |
| observability-and-instrumentation | LOCAL |
| performance-optimization | LOCAL |
| security-and-hardening | LOCAL |
| deprecation-and-migration | LOCAL |
| shipping-and-launch | LOCAL |
| frontend-ui-engineering | LOCAL (not relevant to this backend phase) |
| browser-testing-with-devtools | LOCAL (requires chrome-devtools MCP; not used) |
| context-engineering | LOCAL |
| constraint-driven-development | NOT-INSTALLED (no local equivalent) |
| finishing-a-development-branch | LOCAL |
| requesting-code-review | LOCAL |
| receiving-code-review | LOCAL |
| verification-before-completion | LOCAL |

**Not claimed:** this session did not load all of the above. The matrix records
availability + relevance, not a claim of execution. Only the "Used" table above
is a claim of actual use.

## Brief-named skills that DO NOT exist in this catalog

Recorded so no future agent believes they ran:

`visual-atelie v2`, `html-ppt`, `web-video-presentation`, `ui-ux-pro-max`,
`algorithmic-art`, `cinema-worldbuilder`, `industrial-brutalist-ui`,
`fixing-metadata`, `gpt-taste`/`gpt-tasteskill`, `brand-guidelines`, `brandkit`,
`stitch-design-taste`/`stitch-skill`, `grill-with-docs`, `plan-interrogate`,
`pro-workflow`, `debugging-code`, `plantain-code-quality`,
`pro-token-efficiency`, `pro-mcp-audit`, `pro-context-optimizer`,
`pro-replay-learnings`, `pro-survey-generator`, `pro-batch-orchestration`,
`pro-permission-tuner`, `baoyu-youtube-transcript`, `superpower`,
`brooks-brooks-*`, `styleseed`, `caveman`, `improve-codebase-architecture`,
`OpenCodeReview` (capitalised; the local skill is `open-code-review`).

Diagram tooling: no `archify`/`mermaid` CLI execution path exists in this
harness; the roadmap diagram was rendered with the `render_ui` component
(`mermaid` / `flowchart` types), which IS available. That is what was used — not
a skill claim.

## Anti-fabrication note
The brief asked for "~15 skills enabled". The honest count of skills actually
exercised this session is the 8 rows in the "Used" table. Loading 15 skills to
satisfy a quota would be theatre; the rest are recorded as available.
