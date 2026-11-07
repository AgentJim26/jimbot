# JimBot — headless Fabric bot bridge (Minecraft 26.3)

Client-side Fabric mod that turns a headless game client into a remotely-driven bot
(built for VarenXD on our SMP). No GUI, no keystrokes: everything runs through two
newline-delimited JSON files plus the in-game chat the bot itself can already send.

## How it works

- **In:** `~/backend/mcbot/mod-cmd.jsonl` — one JSON object per line, e.g.
  `{"action":"hold","target":"Fishing Rod"}`, `{"action":"deposit","target":"all","chest":"copper"}`.
  The mod polls this file on the client tick and executes each command in-game.
- **Out:** `~/backend/mcbot/mod-result.jsonl` — one JSON verdict per action,
  e.g. `{"t":...,"ok":true,"detail":"holding Fishing Rod x1 (hotbar 7)"}`.
- **Chat in:** incoming whispers and global mentions of the bot are appended to
  `~/backend/mcbot/dm-queue.jsonl` as they arrive (event-driven, no log tailing),
  e.g. `{"t":...,"sender":"MeowAnimeow","key":"meowanimeow","text":"...","global":true}`.
- **Chat out:** via the [hmc-specifics](https://github.com/headlesshq/hmc-specifics)
  console commands (`msg <text>`, `/msg <player> <text>`), fed through headlessmc stdin.

## Actions (mod-cmd.jsonl)

| action | fields | does |
|---|---|---|
| `inv` | — | list pockets (`inv(n): slot:Item xN ...`) |
| `hold` | `target` (name or hotbar 0-8) | select hotbar slot, syncs to server |
| `drop` / `dropAll` | `target`, `count` | throw stacks via container click |
| `wear` | `target` | quick-move armor on, verifies it landed |
| `unequip` | `target`/`all` | quick-move worn armor back to pockets |
| `interact` | — | right-click crosshair / nearest interactable |
| `deposit` | `target`, `chest`, `count` | open nearest matching container, shift-click items in, close |
| `withdraw` | `target`, `chest`, `count` | same in reverse (container → pockets) |
| `look` / `unlook` | `target` | track a player with the head / stop |
| `aim` | `dyaw`, `dpitch` | relative head turn (deg, +yaw = right, +pitch = up) |
| `rememberDir` / `faceDir` / `dirs` | `name` | save/restore/list named head angles (`directions.json`) |
| `board` | — | right-click nearest boat, verifies seating |
| `cast` / `reel` | — | cast rod (verifies water landing) / reel in nearest own hook |
| `fishstart` / `fishstop` / `fishstatus` | `chest` | autonomous loop: bite→reel→recast, banks catches, `rodbroke`/`dry` events to the queue |

Requires Minecraft 26.3 + Fabric Loader 0.20.1 + Fabric API. Built with Loom against Yarn mappings.

## Run it

Configured for a headless client (see `run.sh` in the operator's backend, not shipped here):
launch with headlessmc into a `fabric-26.3` profile with this mod + Fabric API on board.
