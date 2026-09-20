# Repository workflow

This repository follows the same collaboration model as `time-to-think/openra`.

## Remotes

- `origin`: the contributor's personal fork; feature branches are pushed here.
- `upstream`: `time-to-think/mindustry`; pull requests and issues belong here.
- `canonical`: `Anuken/Mindustry`; read-only source for official updates.

Never push directly to `upstream` or `canonical`.

## Normal development

1. Fetch `upstream`.
2. Fast-forward the local `develop` branch from `upstream/develop`.
3. Create `feature/<topic>` from the current `upstream/develop`.
4. Commit with an English commit message.
5. Push the feature branch to `origin`.
6. Open a pull request from the personal fork into `time-to-think/mindustry:develop`.

Do not develop directly on `develop`, rewrite shared branch history, or force-push shared branches.

## Official Mindustry synchronization

Only a designated maintainer may merge official changes. The maintainer creates
`sync/mindustry-master` from `upstream/develop`, merges `canonical/master`, pushes
the sync branch to their personal fork, and opens a pull request into
`time-to-think/mindustry:develop`.

Do not open pull requests, issues, or comments against `Anuken/Mindustry` as part
of this team's workflow.

## Project integration

The capstone LLM-agent implementation lives in
`integrations/mindustry-llm-agent`. Generated builds, local model/runtime data,
run logs, credentials, and agent memory must not be committed.
