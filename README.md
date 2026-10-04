# Big A (打大A) — a Charta addon

A Fabric 1.21.1 addon for [Charta](https://github.com/LucaArgolo/charta) implementing **打大A (Big A)**,
the Inner Mongolian five-hand climbing game, played with two 54-card decks.

Everything the table needs — the cards, the shader, the fan, the hover lift, the history and options
panels — comes from Charta. This mod adds the game: the deal, the hidden partner, the whole shape ladder,
the 4 recycle, the bots and the table screen.

| | |
| --- | --- |
| Minecraft | 1.21 – 1.21.1 |
| Loader | Fabric, loader 0.17+ |
| Requires | [Charta](https://modrinth.com/mod/charta) 1.2.5, Fabric API |
| Game id | `daa:big_a` |
| Deck | `daa:double` — a 108 card double deck with both jokers |

## The game in one paragraph

A suit is drawn at the deal and its two aces become the **大A**, the highest cards on the board. Their
holders are the **主方**; everybody else is a **抓方**. One holder leads and is announced, the other stays
hidden until their 大A is played. Play runs counter clockwise: beat the pile or pass, and when everybody
passes the last player to have thrown leads again. The 4 — the lowest card in the game — is the only card
with a second job: thrown at a 蛋子 it takes the pile into your hand instead of beating it. The first side
to get a player out wins it, **but only if that player was its own 头贡**; otherwise the board is a draw.

The full rules, the shape table and the controls are in game, on the **how to play** page of the table
(in `src/main/resources/assets/daa/lang/charta_md/`).

## Playing

1. Build a card table out of **card table blocks**. Charta accepts a 3×3, 4×3 or 5×3 rectangle, filled
   in and flat; a five hand game wants the 5×3 one, which also seats everybody on a side.
2. Put the **Double deck** (`daa:double`) on it and select **打大A / Big A**.
3. Up to five players. Empty chairs are filled with bots.

## Controls

* Click a card in your own fan to select it. **Enter** plays the selection, **Backspace** passes,
  **Delete** clears it.
* **F9** opens the layout editor: drag to move, scroll to scale, right click an element to reset it,
  `R` resets the element under the pointer, `S` writes `config/daa-layout.properties`.

## Building

```sh
# Charta must be in the local maven repo first:
#   cd ../charta && ./gradlew publishToMavenLocal
./gradlew build          # jar lands in build/libs
./gradlew deploy         # ... and is copied into the PCL2 instance
```

`deploy` reads `pcl2ModsDir` from the Gradle properties, defaulting to
`D:/PCL2/.minecraft/versions/1.21.1-Fabric 0.19.5/mods`.

## How it is put together

* `dev.daa.game.Combo` — the entire shape ladder as one enum, plus recognition and comparison. Every
  legality question in the game is a call into it, on the server only.
* `dev.daa.game.DaaGame` — the server authority. Deals 108 cards one scheduled tick at a time, draws the
  trump suit, seats the two factions, and validates every play.
* `dev.daa.game.DaaScreen` + `dev.daa.client.DaaLayout` — the viewer-relative ring. The local fan is
  always at the bottom, the other four seats wrap around it, and every coordinate is authored on a fixed
  640×360 design frame that is scaled onto the window.
* `dev.daa.mixin` — nine mixins. The interesting one is `AutoPlayerPace`: Charta's `AutoPlayer` is also
  what a real player's `CardPlayer` is, and its 2–4 second autopilot is far too fast for a 22-card hand,
  so the think time is stretched for humans only.
* The deck is a datapack file, so the mod ships no registrations beyond its game type, menu and payload.

## Licence

MPL-2.0, matching Charta.
