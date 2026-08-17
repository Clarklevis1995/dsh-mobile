# DeepSeek Harness mobile client exploration

## Harness architecture

DeepSeek Harness (`dsh`) is a developer-preview agent harness built around Cordis. Its core architectural rule is that product capabilities are plugins. The host and browser client are separate TypeScript program faces; host services annotated as remotes are projected into the client API gateway, while the Web UI itself is assembled from a host-pushed plugin graph.

The agent session is event sourced. A session produces ordered events such as user messages, assistant streaming chunks and finalized messages, turn/step lifecycle events, tool calls/results, queue state, approval requests, plan state, subagent lineage and usage/timing data. Persistence and projections are separate plugins, so UI features consume derived session state rather than owning the agent loop.

## Web UI feature inventory

The official repository and Web UI packages expose these user-facing areas:

- Workspace management: add/select/rename/reorder/delete workspaces; grouped or flat session lists; session rename, fork and archive; recency/manual ordering; session/content search; live running, unread and pending-interaction status.
- Conversation: streamed assistant text, collapsible reasoning, recursive tool call trees and tool-specific renderers, code/terminal/read/search/web output, image attachments, produced files, retry/error rows and per-turn timing.
- Composer: new sessions, queue and steer delivery, plan mode, model selection, permission presets, slash commands, `@` sources, pending-message queue editing and question/approval takeovers.
- Agent operations: todo/plan display and review, approvals, goals, background jobs, subagents, skills, workflows and message feedback.
- Trajectory: a turn-aware User/Assistant/Tool/Subtool ledger, turn and step grouping, timing overview lanes, TTFT/decode timing, usage/input/output inspector, search, selection, zoom/pan, tail following and virtualized history paging.
- Settings: general behavior, models/providers and credentials, appearance, permission defaults, agent presets, plugin configuration and read-only plugin inventory.

The mobile UI intentionally maps the three desktop columns into native destinations: workspace home, conversation/trajectory, and settings.

## Mobile gateway protocol

Sibling plugin: `../dsh-plugin-mobile-gateway`, version `0.1.6`.

Endpoint defaults to `/ws/mobile` on the Harness web server.

Client to server frames:

```json
{ "type": "ping" }
{ "type": "subscribe", "sessionId": "..." }
{ "type": "unsubscribe" }
{ "type": "message", "sessionId": "...", "text": "...", "mode": "queue" }
{ "type": "message", "text": "...", "mode": "steer" }
```

When `sessionId` is omitted from `message`, the plugin creates a session through `apiProxy.sessions.create`, then submits the prompt through `apiProxy.sessions.prompt`.

Server to client frames:

- `hello`: protocol version, server port and connected-client count.
- `pong`: server timestamp.
- `subscribed`: active session filter or `null`.
- `sent`: accepted session ID and delivery mode.
- `error`: code, message and optional session ID.
- `event`: session ID, sequence, timestamp and curated event payload.

Forwarded event types are `user/message`, `assistant/chunk`, `assistant/message`, `tool/call`, `tool/result`, `turn/start`, `turn/end`, `step/start` and `step/end`; unknown event types retain their type name so a newer server remains displayable.

## v0.1.6 query surface and remaining boundary

The gateway now exposes `workspaces`, `sessions`, paged `history`, `search`, `host`, `directories`, and `workspace-create`. The app synchronizes workspaces, sessions, and host metadata after `hello`, requests history when opening a session, and merges raw history events with curated live events by sequence number.

The remaining Web UI parity gap is mutation-heavy functionality: cancellation, approval answers, plan review, model/permission selection, queue editing, attachments, session rename/fork/archive, and authenticated remote access are not in the mobile protocol yet.
