# 05 — Flexible Usage Condition with Total and Per-Group Requirements

**What to build:** Comprehensive support for app rule usage conditions allowing guardians to configure total required contributor time, individual group required times, or both simultaneously.

**Blocked by:** 03 — App Group Edit Immediate Service Synchronization

**Status:** ready-for-agent

- [ ] Extend AppRule data model and JSON serialization with backward-compatible fields for total condition minutes and per-contributor-group condition minutes map.
- [ ] Update AppRuleEvaluator to enforce that total contributor usage meets the total threshold (if > 0) AND each individual contributor group with a specified threshold meets its requirement.
- [ ] Update CreateAppRuleFragment UI to provide a total usage condition input field alongside individual condition input fields for each selected contributor group (treating empty/zero as unconstrained).
- [ ] Maintain earned allowance compatibility when usage conditions are satisfied.
- [ ] Add comprehensive unit tests covering all matrix combinations of total-only, per-group-only, combined total and per-group, and unconstrained conditions.

