# Cleanup Manifest — v0.4.6 (T08)

Files MOVED / MARKED / DELETED this round. Nothing was deleted without a
restorable snapshot; historical evidence was preserved.

| Original Path | Action | Reason | Evidence Preserved | References Updated |
|---|---|---|---|---|
| docs/handoff/v045/* | KEPT, marked superseded | historic v0.4.5 handoff; referenced by review | yes (git + snapshots) | docs/CURRENT_STATE.md points here as history |
| docs/handoff/v046/00,01,02,03,04,05,06,ACCEPTANCE,SKILLS-MATRIX | KEPT as verification history | superseded by CURRENT_STATE + verification/v0.4.6 | yes | CURRENT_STATE links the set |
| docs/handoff/v046/AGENT_STATE.md | KEPT, marked SUPERSEDED | moved to docs/handoff/current/AGENT_STATE.md | yes | — |
| docs/AGENT_STATE.md (root) | KEPT, marked SUPERSEDED | same | yes | — |
| CHECKPOINT-0/R1/R2.md | KEPT | historic checkpoints, no unique content loss risk accepted yet | yes | — |
| docs/TEST-REPORT.md / TEST-LOG.txt / INSTRUMENTED-TEST-LOG.txt | KEPT | historic logs (pre-v0.4.4); not deleted | yes | superseded banner pending |
| STATUS-REPORT-v0.4.2/0.4.4.md | KEPT | release history | yes | — |
| docs/architecture/CONVERSATION-IDENTITY.md | NEW | engineering summary of the implemented contract | n/a | — |
| docs/CURRENT_STATE.md | NEW | single current-state entry point | n/a | — |
| docs/verification/v0.4.6/* | NEW | fresh evidence set | n/a | — |

## Deliberately NOT done
- No files deleted: every historic doc either holds unique evidence or is
  referenced from another doc that this round did not fully re-audit. Bulk
  deletion is deferred to a dedicated pass with the full reference graph.
- No `git rm` / `git add -A` was executed.
