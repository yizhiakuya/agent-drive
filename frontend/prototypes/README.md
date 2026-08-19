# Frontend Prototype References

`agent-drive-reference.tsx` is the user-supplied React prototype used as a visual and interaction reference for Agent Drive.

The reference emphasizes a chat-first workspace, compact monochrome controls, visible background task status, expandable reasoning/tool steps, and explicit confirmation for high-risk actions. It is intentionally kept outside `src/` so its mock data and local timers cannot enter the production bundle or be mistaken for the live API contract.

When adapting the reference, preserve the existing product behavior: real sessions, SSE chat streaming, registered frontend actions, file operations, task APIs, authentication, and the `auto/low/medium/high` thinking-level contract.
