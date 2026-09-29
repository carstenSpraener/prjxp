# prjxp — Architecture Documentation (arc42)

**About arc42**

arc42 is the template for documenting software and system architectures.
Template Version 9.1 (based on the AsciiDoc version). Created, maintained and © by
Dr. Peter Hruschka, Dr. Gernot Starke and contributors. See <https://arc42.org>.

| | |
|---|---|
| **Document** | prjxp — An AI toolset to provide an expert for a software project |
| **Version** | 1.0 |
| **Date** | September 2026 |
| **Status** | Draft (generated from the code base) |

## Table of Contents

| # | Section | File |
|---|---------|------|
| 1 | Introduction and Goals (task, quality goals, stakeholders) | [01_introduction_and_goals.md](01_introduction_and_goals.md) |
| 2 | Architecture Constraints | [02_architecture_constraints.md](02_architecture_constraints.md) |
| 3 | Context and Scope (business & technical context, in/out of scope) | [03_context_and_scope.md](03_context_and_scope.md) |
| 4 | Solution Strategy (key forces, technical strategy) | [04_solution_strategy.md](04_solution_strategy.md) |
| 5 | Building Block View (static view: modules, components) | [05_building_block_view.md](05_building_block_view.md) |
| 6 | Runtime View (scenarios: pipeline, MCP queries, hub import, …) | [06_runtime_view.md](06_runtime_view.md) |
| 7 | Deployment View (Docker image, modes, volumes, local dev) | [07_deployment_view.md](07_deployment_view.md) |
| 8 | Cross-cutting Concepts (PxChunk, JSONL, SPI, config, multi-project) | [08_cross_cutting_concepts.md](08_cross_cutting_concepts.md) |
| 9 | Design Decisions (ADR-style) | [09_design_decisions.md](09_design_decisions.md) |
| 10 | Quality Requirements and Scenarios | [10_quality_scenarios.md](10_quality_scenarios.md) |
| 11 | Risks and Technical Debt | [11_risks_and_technical_debt.md](11_risks_and_technical_debt.md) |
| 12 | Glossary | [12_glossary.md](12_glossary.md) |

## Reading Guide

- **Newcomer:** start with [01](01_introduction_and_goals.md) → [03](03_context_and_scope.md) →
  [05](05_building_block_view.md) (Level 1 black boxes are enough for the big picture).
- **Contributor:** read [05](05_building_block_view.md) (Level 2/3), [08](08_cross_cutting_concepts.md)
  and [09](09_design_decisions.md).
- **Operator:** read [06](06_runtime_view.md) and [07](07_deployment_view.md).

> **Note:** This document is written in English and derived from the current state of the
> code base (Gradle multi-project, `de.spraener.prjxp`). Where the code and older documents
> disagree, this document follows the code.
