# GTMAddOns

A client-side [Fabric](https://fabricmc.net/) mod for the **GTM** Minecraft
server: swap timing, gun and melee tracking, ratings, a leaderboard and a set of
HUD helpers, with player stats shared between everyone who uses it.

> **Open beta.** Expect rough edges. Bug reports and suggestions are welcome as
> issues.

It only reads your own inventory, your own shots and what the server sends you,
and it never changes gameplay.

## What it does

- **Swap timing** - times your wingsuit and jetpack swaps and checks the
  wingsuit really reached your hotbar, with an optional full breakdown (steps,
  mouse path, momentum).
- **Gun tracking** - shots, hits, headshots and kills per gun, and how fast
  movement guns launch you.
- **Melee combos** - combos you broke, combos of yours that got broken, first
  hits, plus live "Combo'd" / "Hit again" timers.
- **Ratings** - Aim, Movement (or Melee) and Overall, 0-100, for each kind of
  PvP: Ground, Wing, JP and Air.
- **Stats screens** - a leaderboard of ratings per PvP, your own stats page, a
  fight log, and filters (any number of fights from 1 to 100, and only certain
  opponents).
- **HUD helpers** - swap timer, wing boost angle and height, a cleaner `/near`,
  all movable, plus gun sound volumes and a hit sound.

Everything is reached from one menu: type `/gao` (or `/gtmaddons`).

## Install

You need Minecraft **1.21.11**, [Fabric Loader](https://fabricmc.net/use/) 0.18
or newer, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 21.

1. Download `gtmaddons-<version>.jar` from this repository's **Releases** page.
2. Put it in your `mods` folder next to Fabric API. On Lunar Client that is the
   `mods/fabric-1.21.11` folder of your Fabric 1.21 profile.
3. Start the game and type `/gao`.

## Stats server

The mod uploads your fight stats to a shared server; the server's code is in the
separate `GTMAddOns-backend` repository. **What is sent, who can see it and how
long it is kept is described in "Data collection" below - please read it.**
There is currently no setting to switch uploading off. If you want your stats
removed, open an issue.

To use your own server, deploy the backend to your own Cloudflare account,
change `BACKEND_URL` in
`src/main/java/com/example/gtmaddons/stats/StatsClient.java` and rebuild. A
build you make yourself talks to *your* server, and its stats are separate from
the official one.

## Not affiliated

This is an unofficial community project. It is not affiliated with or endorsed
by GTM, Mojang or Microsoft, or Lunar Client. Check the server's own rules
before using any client mod on it.

## Data collection

**This mod uploads your fight stats.** Stats are only recorded during
fights: from GTM's combat tag starting ("You are now in combat!") until
you get a kill (`[GTM] You killed X!`) or die. A death is caught from
GTM's **WASTED** title (it only shows when you die, and its subtitle names
the killer), with the chat messages (`<you> was killed by X!`,
`was murdered by`, `was shanked by`, `took a trip through an oven`,
`got his ass kicked by`) and actually dying as backups. If the tag ends without either, that fight is thrown
away. After a kill while you're still tagged, the next fight starts right
away.

When a fight ends, the mod sends the following to the GTMAddOns stats
server, tied to your Minecraft username and UUID:

- whether it was a kill or a death, who it was against, and when it
  started and ended
- each swap's result (success / failed / canceled), plus the timings and
  mouse-movement measurements listed under Debug mode
- totals per gun name: shots fired, hits, headshots and kills, plus for
  movement guns your average and best speed right after a shot
- melee combo totals

The server keeps each player's **last 100 fights**; older ones are
deleted as new ones come in. The menus show stats over the last 25, 50 or
100 of them.

Nothing else is sent: no chat, no server address, no coordinates, and
no other players apart from the name of who you killed or who killed
you. To prove your identity, the mod signs a one-time code with
the key Mojang gives your account for secure chat. Your login and
access token are never sent anywhere.

**Everyone who uses the mod can see everyone's stats**, including your
username, in-game under Player Stats in the `/gao` menu.

If you switch Minecraft accounts in-game, each fight is saved to the
account that fought it.

## Display

- Live, top-left corner, while the inventory is open
- On close: an action-bar message — either the swap time, or "Swap
  Failed" if your chestplate didn't actually change
- A "Last Swap: X.XXXs" (or "Swap Failed") readout in the corner for
  ~4 seconds after closing

Timing is event-driven (Fabric's `ScreenEvents`), not polled on the game
tick — the clock starts/stops on the same frame the inventory screen
actually opens/closes, so precision is roughly one render frame (a few
ms at high fps) rather than up to a full 20Hz tick (~50ms) behind.

A "wingsuit" is any gliding item (the elytra, or anything a server has
given the glider component). Opening your inventory only counts as a swap
attempt if a wingsuit is somewhere outside your hotbar (armor slot, main
inventory or offhand) at that moment — otherwise nothing is tracked.

Each attempt ends in one of three results:

- **Success** — a wingsuit reached your hotbar at any point while the
  inventory was open. The swap time is from opening the inventory to
  closing it. (Success is checked every frame rather than at close,
  because on high ping the wingsuit can still show in your armor slot
  after it has really been moved.)
- **Swap Canceled** — no wingsuit reached the hotbar, but you moved other
  items around.
- **Swap Failed** — no wingsuit reached the hotbar and nothing was moved.

Canceled swaps are counted separately and aren't included in "swaps
attempted" or the success rate.

## Menu

Everything is done from the menu. Type `/gao` (or `/GTMAddOns`,
`/gtmaddons`) to open it. There are no other commands.

- **Player Stats:** a leaderboard of ratings. One tab per PvP (Ground, Wing,
  JP, Air); everyone is ranked by their Overall rating, highest at the top, with
  their Aim, Movement (Melee on JP), K/D and number of fights. Players who can't
  be rated yet are listed below without a rank. It has the same filters as
  Personal Stats: 25 / 50 / 100 fights, or **Filter...** for any number from 1 to
  100 and only fights against the opponents you tick. Click a player for their
  page.
- **Admin mode** (Settings, admin accounts only): while on, the leaderboard, a
  player's page and the fight log show red delete buttons that remove a
  player's stats data, or a single fight, from the server - for when someone has
  boosted their ratings. Every delete asks first and cannot be undone.
- **Personal Stats:** your own page.

  Both kinds of page have a tab for each PvP category and open on
  whichever tab you had open last. They open on the **Overview**, styled
  like csstats.gg: rings for K/D and your rating, a breakdown card (on
  Wing, swap success and swap time together: success %, average time,
  success and mouse efficiency pies and the time splits; pie charts of
  each Air swap type on Air, combos broken and kept on JP, head / body /
  miss on Ground), your last 10 fights as green (kill) and red (death)
  dots, hit rate, headshot %, momentum
  (Wing), swap time (Air), melee (JP) or movement gun speed (Ground),
  your guns by most shots, and a gun stats table (shots, hit %, headshot
  % and kills per gun; on a narrow screen kills, then shots, are left
  out). Scroll if it doesn't fit.

  **Click any card** for its insight page, with the numbers behind that
  stat. The buttons along the top switch between the tab's pages:
  - **Ratings:** each rating, what Overall is made of (each part's score,
    weight, share of Overall and the Overall points it costs you, and
    which part has most to gain), and everything that went in.
  - **Fights:** K/D, fights won, and from the last 10 fights: current and
    best kill streak, fight lengths, quickest kill, your record against
    each opponent, and a timeline.
  - **Swaps (Wing):** success, cancels, effective swap time, each step's
    share of the average swap, momentum, mouse movement, recent swaps.
  - **Air swaps:** success, effective time, how much each swap type is
    used, and each type's average, best and consistency.
  - **Melee (JP, Air):** combo share, breaks, combos kept, first hits,
    the melee rating part by part, and how combos are counted.
  - **Movement (Ground):** each movement gun's score, average and best.
  - **Guns:** hit rate, headshots, shots and hits per kill, where shots
    land, every gun with its aim score, and guns by type.

  Above Back:
  - **25 fights | 50 fights | 100 fights:** stats over that many of the
    player's most recent fights **of the selected tab's PvP**. Each fight
    counts as the PvP you were in when it started, so the JP tab is your
    last JP fights. The line under the name shows how many fights, the
    **K/D** (kills per death) and the kills and deaths.
  - **Filter...:** pick any number of fights from 1 to 100 and, if you like,
    only certain opponents (tick as many as you want; search by name). The
    stats and ratings then cover the newest N fights of that tab's PvP against
    those opponents. While a filter is on, none of the 25 / 50 / 100 buttons
    is selected; press one to go back.
  - **Fight log:** every fight in the last 100, newest first: when, who
    against, win or loss, how long, which PvP, and the ratings you had in it
    (Aim, Movement or Melee, Overall). The button beside the PvP filter
    switches between **this fight** (that fight's own numbers, so some show
    "-" when there weren't enough shots, swaps or combos) and **running 10 /
    25 / 50 / 100** (your ratings over that fight and the ones before it in
    the same PvP). Click a fight for the ratings page behind it.
  - **Individual gun stats:** guns are grouped by type by default (see
    Gun stats); turn this on to see every gun on its own.
- **Settings:** swap timer (corner text and a timer where the action bar
  sits), **Move HUD** (drag any on-screen display to where you want it;
  right-click one to put it back, or Reset all), swap debug
  (full breakdown in chat after each swap), better combo timers, boost
  angle (while gliding, your look angle under the crosshair: green when
  shift will boost, which needs more than 10° up, red when it won't),
  boost height (while gliding, your Y under the crosshair: green below
  Y 200, where boosting works, red at 200 or above),
  **Better near** (GTM's `/near` reply as one compact message, closest
  player first, so you can read it without opening chat: just each
  player's name, bold in their rank's color (Supreme red, Sponsor purple,
  Elite aqua, Premium green, VIP gold), then their distance in gray
  brackets, e.g. `Stayzz_ (4b)`. It replaces GTM's message rather than
  adding a copy),
  latency tester, dev mode (dev accounts only),
  and **Gun Sounds**.

**Gun Sounds** has a volume slider for each gunshot sound. GTM's guns
play vanilla sounds, and guns that share a sound share a category:

| Category | Guns |
|---|---|
| Rifles | Advanced Rifle, Assault Rifle, Bullpup Rifle, Carbine Rifle, M4, Special Carbine |
| SMGs | Assault SMG, Combat PDW, Gusenberg Sweeper, Micro SMG, SMG, Tokyo's Lego Smg |
| Pistols | Combat Pistol, Heavy Pistol, Heavy Revolver, Marksman Pistol, Pistol |
| Shotguns & Musket | Assault/Heavy/Pump/Sawed-off Shotgun, Musket |
| Snipers | Assault Sniper, Heavy Sniper, Sniper Rifle |
| Machine Guns | Combat MG, MG |
| Launchers | Homing Launcher, RPG |
| Browning M2, Minigun, Grenade Launcher | one each |

All of these are listed from the start. If GTM adds a new gun, the mod
learns its sound after a few shots and adds it, either to the matching
category or as a new row. Reload sounds (trapdoor, lever, piston and so
on) are never treated as gunshots. A slider changes that sound
everywhere: other players' shots, and anything else on the server that
uses it.

**Both stats sections are locked while you're combat-tagged.** Their
buttons are greyed out, and an open stats screen closes as soon as you
get tagged.

The other settings are saved to `config/gtmaddons.json`, so
they're remembered after a restart. Dev mode isn't saved, because it has
to pass the access check each time.

## PvP categories

Every shot and swap is labeled with the kind of PvP you're in, based on
what you're carrying and wearing at that moment. The checks run in this
order:

1. **Air:** you're carrying both a jetpack and a wingsuit (worn or
   anywhere in your inventory).
2. **JP:** you're wearing a jetpack (an item with "Jetpack" in its name),
   or there's one in your hotbar and your chestplate slot is empty.
3. **Wing:** you're wearing a wingsuit, or one is in your hotbar and your
   chestplate slot is empty.
4. **Ground:** anything else.

All four track gun accuracy. **Wing** and **Air** also track swap
stats, and **Ground** tracks movement guns.

**Movement guns** (Sawed-off Shotgun, Pump Shotgun, Heavy Revolver)
launch you, so for each of their shots the mod records your horizontal
speed in blocks per second: the peak over the 0.15s right after the
shot. The Ground tab shows the average and fastest for each gun, where
Wing and Air show swap stats. In dev mode, each shot's line in our dev
log shows the speed before and after it. A swap's category is decided when you open your inventory,
before the swap changes what you're wearing.

**Air swaps** come in four kinds, worked out from what your hotbar
gains or loses. The hotbar is used instead of the chest slot, which can
show stale items on high ping. The kinds:

- **Jetpack swap:** a jetpack goes on or off, with no wingsuit involved.
- **Wingsuit swap:** a wingsuit goes on or off, with no jetpack involved.
- **Jetpack → Wingsuit** and **Wingsuit → Jetpack:** one comes off as
  the other goes on.

Just moving a jetpack or wingsuit between your inventory and hotbar is
counted as a canceled swap, not a real one.

**Loot doesn't change your category.** GTM's backpack can't be opened
while you're combat-tagged, so mid-fight the only way to gain a jetpack
or wingsuit is picking one up from a kill. While you're tagged, the mod
only counts the jetpacks and wingsuits you had when the tag started. The
tag starts with the server's `[COMBATTAG]` message or your first hit
dealt or taken. It ends on the server's "combat tag ended" message, or
when you die. GTM can extend the tag without telling you, so there's no
countdown in the mod. As a safety net, the tag also ends after 3 minutes
with no hits dealt or taken.
Out of combat, everything in your inventory counts, including items
brought out of your backpack.

## Melee combos

Every registered melee hit locks the player who was hit for 0.7s, and
their swings "blank" until it runs out. The attacker can't land another
hit on them until their weapon's cooldown (the white sweep over the hotbar
item) runs out: 0.5s for most weapons, shorter for some, like chanclas.
GTM shows `⚔ Combo'd - 0.6s` and `Target recovering - 0.1s`, but only
when you swing, so the numbers never count down.

With **Better combo timers** on, the mod hides those messages and shows
live countdowns above the crosshair the moment a hit lands:

- `⚔ Combo'd |||||||||||||||||||| 0.43s` when you're hit, until you can hit back.
  The bar drains and goes from red to gold to yellow, then shows
  `✔ Hit back` in green.
- `⚔ Hit again |||||||||||||||||||| 0.31s` (aqua) when you land a hit, until
  you can land another, then `✔ Hit ready` in green. It uses the cooldown
  the server set for that weapon, remembered per weapon, so weapons with
  shorter cooldowns are timed right. A weapon whose cooldown hasn't been
  seen yet uses 0.5s, corrected as soon as the server sends the real one.

Only one of the two shows at a time: a running countdown beats a "ready"
flash, and if both are counting down, the newer one shows. More
generally, no two displays are drawn on top of each other: if you move
two into the same spot, only the more important one shows (combo timers,
then boost lines, then the swap timer, then the corner text).

Combo stats:

- **Enemy combos you broke:** an enemy's run of registered melee hits on
  you is broken when you land a registered hit on them.
- **Your combos that got broken:** the same, the other way round.

- **First hits:** a hit that is neither a break (ending the other
  player's combo) nor a hold (continuing your own) - nobody had a combo
  going between the two of you. Every combo starts with either a break or
  a first hit. Shown as the share of first hits that were yours (counted
  from 2026-09-29 on).

A combo that ends because the attacker stops (2 seconds with no hit
from them) or someone dies counts as not broken. Only
player-vs-player melee counts; gun hits never do. Combos are shown per
PvP category (mostly JP) in Personal/Player Stats, fight results and
the 1/5/10-minute views.

## Ratings (beta)

On every stats page's rating card, for that tab's PvP: **Aim**,
**Movement** and **Overall** (on JP: **Gun aim**, **Melee** and
**Overall**), each 0-100, where a player at every baseline scores 50. Click the
rating card for the Ratings page to see what went into each. This is groundwork, and every number behind it
is in `rating/RatingWeights.java` so it can be tuned:

- **Aim:** for each gun (with at least 20 shots), points per shot:
  headshots are worth the most, body shots less, and misses nothing. That
  is compared to the gun's baseline, so 50 means baseline and 100 twice
  it. Guns are averaged, weighted by shots and by how much the gun counts:
  snipers and rifles count most, launchers less, and the Net Launcher
  least.
- **Movement:** at least 5 successful swaps. On Wing: the swap and
  momentum parts below, equally weighted. On Air: the same Swap score over
  all Air swap types, where 0.35s scores 100 and 1.2s scores 0. On Ground: at least 10 movement gun shots, scored by
  your average speed right after them. Sprinting speed (5.6 b/s) scores 0,
  since the shot added nothing, and 20 b/s scores 100. Each gun is scored
  and then averaged by shots. JP has Melee instead.
- **Melee (JP, and part of Air's Overall):** at least 5 combos (yours plus the enemy's). The equal
  average of three %s: enemy combos you broke, your combos you kept, and
  your share of all combos (50 means as many as the enemy).
- **Overall:** on Wing, four parts averaged by weight:
  - **Swap:** speed and reliability together, success rate × (1 /
    average swap time). It's scored as the effective swap time that works
    out to (average ÷ success rate): 0.25s scores 100, 0.9s scores 0. So
    0.40s at 80% success scores like a 0.50s swap.
  - **Momentum:** the % of your speed kept through the swap.
  - **Aim:** the Aim rating. It counts more the more of your kills come
    from long-range guns (snipers and the Musket): weight 1 × (1 + the
    long-range share of your kills), so up to double.
  - **K/D:** 1.0 scores 50, 2.0 or more scores 100.

  All weights start at 1. A part with no data yet is left out, and Overall
  shows once Swap and Aim both have enough data. On Ground it's the same
  formula with Movement in place of Swap and Momentum: (Movement + Aim ×
  its long-range weight + K/D) ÷ the weights, shown once Movement and Aim
  both have enough data. On JP, K/D plays the biggest part: (Gun aim × its
  long-range weight + Melee + K/D × 2) ÷ the weights, so K/D counts as much
  as gun aim and melee together. It shows once K/D and either gun aim or
  melee have enough data. On Air everything Air tracks counts equally:
  (Swap + Aim × its long-range weight + Melee + K/D) ÷ the weights, shown
  once Swap and Aim have enough data. Air has no Momentum part, since
  momentum is only recorded for Wing swaps. Mouse efficiency isn't part of
  the rating any more.

## Gun stats

Gun shots are tracked during fights (see Data collection), and there's
nothing to turn on:

- **Shot:** the ammo readout in the held gun's name (e.g.
  `Combat MG «49/29897»`) goes down while the gun's name stays the same.
  Switching guns isn't counted.
- **Hit:** the server reports damage caused by you in the same tick as
  the shot.
- **Headshot:** the server's `⊕` hitmarker arrives with the hit.
- **Kill:** the chat message "You killed …" arrives with the hit.
- **Net Launcher:** only a hit on a player wearing a wingsuit (or
  gliding) counts, since that's the hit that nets them in cobwebs. A hit on
  anyone else is just a single damage tick and counts as a miss, and so
  does a miss that turns into a cobweb fireball.

Totals per gun show in fight results and on each player's page
in Player Stats and Personal Stats.

On every tab, guns are grouped by type: one row each for **Rifle**,
**Sniper**, **SMG**, **Pistol**, **Shotgun**, **Machine gun** and
**Launcher**, with the same stats (shots, hit %, headshot %, kills) added
up across those guns. One-of-a-kind guns, like the Net Launcher and the
Musket, are listed on their own. **Individual gun stats** (above Back)
shows every gun separately.

## Dev mode

Dev mode (Settings in the `/gao` menu) is limited to accounts listed in
`DEV_UUIDS` in the backend's `wrangler.toml`. It writes everything around each shot to
`gtmaddons-dev.log` in the game folder:

- damage events and who caused them
- health changes
- every sound, with its distance from you
- titles, subtitles and action-bar/chat messages
- particles
- where your crosshair was pointing
- a RESULT line per shot: hit/miss, target, distance, headshot, kill,
  gunshot sound, click-to-shot response time and ping

After each burst it also prints a one-line summary per gun to chat.

Wing boosts: each shift press while gliding is reported in chat as
`Wing boost OK` (GTM's boost sounds played) or `Wing boost NONE`, with
your look angle and height (world Y and blocks above the ground). Boosts
need more than 10° up, so each line says whether the result was expected.
A red `NONE (angle OK, should have boosted)` points at GTM's height limit.
Each line shows what the session so far says about that limit, as both Y
and height above ground. The limit is between the highest boost and the
lowest of those misses. Whichever measure stays consistent (not `MIXED`)
is the one GTM uses. Misses within 1.5s of a boost aren't counted, in case
there's a cooldown. A boost that starts while shift is held is judged by
where you were looking about one ping earlier, which is roughly what the
server saw.

Swings: dev mode flags hits that registered without a fresh swing
animation, with the reason and the item, target, attack charge, swing
length and time since your last click, in chat and the log. Missed spam
clicks mid-swing are normal and aren't reported. When the server changes
the held item, it names the item components that changed; a gun's ammo
count changing doesn't count. Possible reasons: clicking
mid-swing (a swing only restarts once the current one is half done), an
item with no swing animation, a dropped click (re-clicking within one
game tick of a miss, so the game never sees the button released, or the
item's minimum attack charge), or the server
changing the held item right after the click (its re-equip animation can
hide the swing).

/near: when you run `/near`, dev mode captures GTM's reply. That's every
chat and action-bar message for a moment afterwards: until 1s after the
last one, or 3s if nothing comes, and at most 6s. Each is written to the
log as its own `/near reply` block, with its timing, exact text, and any
hover text or click action on parts of it (servers often put details like
distances there). A chat line says how many messages were caught. In QA
mode the messages still show in chat, but nothing is saved.

## QA mode

QA mode sits next to Dev mode in Settings and needs the same access. It
shows the same chat messages as dev mode but doesn't write
`gtmaddons-dev.log`, so nothing is saved to disk. Only one of the two can
be on at a time.

## Debug mode

Each swap prints:

- **Reach chest slot** — open → cursor first touches the chest armor slot
- **Chest slot → hotbar** — touching the slot → wingsuit lands in hotbar
- **Hotbar → close** — wingsuit in hotbar → inventory closed
- **Wing to hotbar** — open → wingsuit in hotbar
- **Total swap** — open → close
- **Mouse moved** — total cursor movement
- **Momentum** — on a successful Wing swap that puts the wingsuit into an
  empty hotbar slot: your horizontal speed (blocks/s) when you opened the
  inventory, your average speed over the 2 seconds after you closed it
  (cut short if you land), and the % kept. Because of that wait, the swap
  debug report for these swaps shows up to 2 seconds late. It shows on the Wing
  tab as the averages and "Momentum kept"
- **Efficiency** — how direct your mouse movement to the wingsuit was.
  100% is one perfectly straight move from where the cursor started to the
  slot, then no movement until the wingsuit is in the hotbar. It is the
  distance you needed divided by that distance plus everything that went
  against it: every bit of movement to either side of the straight line,
  movement backward, away from the slot (counted twice: going back, then
  making the ground up again), and all movement after first touching the
  slot until the wingsuit is in the hotbar, inside the slot or out. So 300°
  needed with a 20° bow (40° sideways) is 300 / 340 = 88%, and going 30°
  past the slot and back is 300 / 360 = 83%. Movement is measured once per
  rendered frame, and a 1.5 px band either side of the line is allowed so
  whole-pixel mouse steps on a straight line don't count. Movement after
  the wingsuit is in the hotbar isn't included (it's shown separately).
  Swaps recorded before 2026-09-29 used an easier formula (needed divided
  by path length), so their % reads higher
- **Approach** — movement before reaching the slot vs. the straight-line
  distance you actually needed (to the point where you first touched it)
- **Sideways off the straight line** and **Backward** — the two parts of the
  approach that count against efficiency
- **Movement after touching the slot** — everything after first touching
  the slot until the wingsuit reaches the hotbar (overshooting and coming
  back, wiggling on it), plus how far past it you went at most
- **Unneeded after wing in hotbar** — movement between the wingsuit
  reaching the hotbar and closing (closing is a key press, so none of
  it is needed)

If the wingsuit wasn't in your chest slot, the slot it *was* in is used
instead. Movement is shown in degrees — how far that much mouse movement
would turn your camera at your current sensitivity. This assumes raw
mouse input (no Windows "Enhance pointer precision"), since the inventory
cursor uses the OS cursor.

## Requirements

- JDK 21
- Gradle (or just use an IDE with the Fabric plugin — see below)
- Minecraft 1.21.11, Fabric Loader, Fabric API

## Building

```
cd swapinfo
gradle wrapper --gradle-version 8.10   # one-time, if you don't already have a wrapper
./gradlew build
```

The built jar will be in `build/libs/gtmaddons-1.0.0.jar`.

**Easier option:** open the `swapinfo/` folder in IntelliJ IDEA with the
"Minecraft Development" (Fabric) plugin installed, let it import the Gradle
project, then use the Gradle `build` task from the sidebar. This avoids
needing to hand-set up the wrapper.

## Installing

Drop the built jar into your `.minecraft/mods` folder alongside Fabric API.
Since Lunar Client runs a Fabric-compatible mod loader, a standard Fabric
mod placed in the right mods folder for your Lunar profile should load the
same way it would in vanilla Fabric — check Lunar's mod-loading docs if it
doesn't pick it up automatically.

## Version caveats (please read)

I pinned `minecraft_version`, `yarn_mappings`, `loader_version`, and
`fabric_version` in `gradle.properties` to what looked right for 1.21.11 as
of writing, but Minecraft/Fabric version strings shift often and I can't
fully guarantee these are exact as of when you build this. If Gradle fails
to resolve a dependency:

1. Check https://fabricmc.net/develop for the current recommended versions
   for Minecraft 1.21.11 and update `gradle.properties` to match.
2. The class/method names used (`InventoryScreen`/`CreativeInventoryScreen`,
   the `HudRenderCallback` signature taking `(DrawContext,
   RenderTickCounter)`, `EquipmentSlot`, and the client command
   registration signature) have been stable in recent Yarn mappings, but
   if you get a "cannot resolve symbol" error on any of them, use
   IntelliJ's "Go to Class" (Ctrl+N) to search for the class by a partial
   name and find what it's actually called in your exact mappings — this
   is exactly how we fixed `AbstractInventoryScreen` not existing earlier.

## Customizing

- Change the corner position: edit the `6, 6` coordinates in
  `onHudRender`.
- Change how long the "Last Swap" readout lingers: edit
  `RESULT_DISPLAY_MS`.
- Also time chest/container screens, not just your own inventory: change
  the `instanceof AbstractInventoryScreen<?>` check in `onClientTick` to
  `instanceof HandledScreen<?>` (add the import).

## License

Copyright (C) 2026 Chuck015. This mod is free software, licensed under the
GNU General Public License v3.0 (see `LICENSE`): you may use, modify and
share it, and anything you distribute that is built from it must be shared
under the same license, with its source. It comes with no warranty.
