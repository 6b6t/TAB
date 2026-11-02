# Hard-mute visibility

Velocity TAB reads hard-mute UUIDs from AsyncChat's authoritative `asyncchat_uuid_mutes` table (`hard_mute = TRUE` and `expires_at` later than the current epoch-millisecond timestamp). The connection is read-only and polls every two seconds. A status change reconciles existing tab entries, and outbound player-info updates are filtered as they pass through TAB, including entries arriving from another proxy. A muted player keeps their own entry.

The reader uses the same environment variable names as AsyncChat: `CONFIG_ASYNCCHAT_DATABASE_HOST`, `CONFIG_ASYNCCHAT_DATABASE_PORT`, `CONFIG_ASYNCCHAT_DATABASE_DATABASE`, `CONFIG_ASYNCCHAT_DATABASE_USERNAME`, and `CONFIG_ASYNCCHAT_DATABASE_PASSWORD`. Grant the proxy account `SELECT` on the mute table. If any required setting is missing or the query fails, TAB logs one warning for that unavailable period and fails open.

The connection announcements in `6b6t-plugins/proxy/JoinNotifications` and `VelocityWelcomeMessage` are delivered only to the joining player. No proxy plugin in that repository broadcasts public join, leave, or server-switch lines; those lines are emitted by backend server software and are outside this Velocity TAB change.
