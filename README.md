# JWhitelist - simple plugin for the whitelist.
## Stores data in mysql or sqlite.
## Allows you to add a player for a while.
## There is a command to remove players who have not logged on to the server for a certain time.

## Folia and Paper support

Build with Java 25 and Maven (`mvn clean package`). The plugin targets the
Minecraft 26.3 API and emits Java 17-compatible bytecode.

Folia is detected at runtime. Periodic database cleanup uses a dedicated executor;
SQL connection operations are serialized against login, commands, reload, and
shutdown. Player kicks and replies use the player's entity scheduler on Folia and
the Bukkit scheduler on Paper. Player commands queue database work off the region
thread. Console commands remain synchronous so automation can observe a committed
write and capture the success reply before dispatchCommand returns.

The SQLite/MySQL table layout and commands remain compatible. Names are matched
case-insensitively. Expiry parsing rejects invalid, zero, negative, and overflowing
durations; `min` is minutes and `m` retains its existing meaning of 30-day months.
Login is denied if database initialization/reload fails. Existing prelogin bans
are preserved. LuckPerms is optional and uses its own UUID lookup API rather than
Bukkit offline-player lookups. Whitelist clear confirmation is scoped to the
requesting sender, expires after 60 seconds, and works for console as well.

Keep your existing `plugins/JWhitelist/config.yml` and database when upgrading.
Stop the server before replacing the JAR or migrating a SQLite database. Do not
run two whitelist plugins at once. Built-in Minecraft whitelist and proxy login
plugins are independent; authentication plugins stay on the proxy.

### Validation

`mvn test` covers supported duration units and malformed/overflowing values.
The optional smoke-test plugin in `verification/` exercises SQLite persistence,
case-insensitive membership, expiry, simultaneous readers/writers, prelogin
allow/deny, preserving bans, console automation acknowledgement, player scheduler
routing, and reload persistence on a running Folia server. It requires a test
whitelist with at least one entry and must not be installed on production.
Compile its Java source against the built JWhitelist JAR and server API, bundle
it with `verification/plugin.yml`, and place the test JAR beside JWhitelist on an
isolated server. A successful run logs `JWL VERIFICATION PASSED`.

MySQL configuration is retained; the smoke test uses SQLite.
