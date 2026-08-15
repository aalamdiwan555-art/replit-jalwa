# ATPILOT Fleet Control

ATPILOT is an Android testing utility with a browser admin console for reviewing approved devices and sending safe, user-authorized test commands.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

- `android-app/` — imported Android device agent and local testing flows.
- `artifacts/device-admin/` — browser-based fleet admin console.
- `artifacts/api-server/src/routes/device-control.ts` — device registry, heartbeat, command queue, and dashboard routes.
- `lib/api-spec/openapi.yaml` — source-of-truth API contract.
- `lib/db/src/schema/device-control.ts` — PostgreSQL device-control tables.

## Architecture decisions

- Device enrollment and heartbeat are separate from local Android account approval, so a device can be reviewed before remote commands are allowed.
- Commands are queued server-side and delivered on the next device heartbeat; the Android app remains user-started and does not upload screen data.
- Device health is derived from the last heartbeat, with a short online window and explicit paused/pending states.

## Product

- Admins can review an entire device fleet, approve or disable devices, inspect device health, and queue safe testing commands.
- Android devices can enroll with a per-device token and poll for commands without uploading screenshots or templates.

## User preferences

_Populate as you build — explicit user instructions worth remembering across sessions._

## Gotchas

- Build Android with `-PcontrolApiBaseUrl=https://your-domain/api` to enable fleet sync.
- The admin panel currently needs an authentication integration before production use; the user declined the suggested Clerk connection during this build.

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
