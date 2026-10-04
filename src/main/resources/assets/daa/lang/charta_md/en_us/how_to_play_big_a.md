# Big A (打大A)

*Big A* is a climbing game from Inner Mongolia, played with **two 54 card decks** by **five** players.
This is the Charta port. It follows the common ruleset; the paragraphs below say exactly what this port
does wherever a local variant could go either way.

## The board

A suit is drawn at the deal. Its two aces are the **big aces** (大A) and are the highest cards in the
game.

The two big aces split the table in two:

* Their holders are the **big ace side** (主方).
* Everybody else is a **catcher** (抓方).

**One holder leads and is announced.** The other — the *second big ace* — stays hidden until their big
ace actually hits the table, which is the whole tension of the game. If one player happens to hold
**both** big aces they are announced at once and play alone against the other four.

## The ladder

Strongest first. Note where the two and the three sit:

```
big A  >  big joker  >  small joker  >  3  >  2  >  A  >  K  >  Q  >  J  >  10  >  ...  >  5  >  4
```

## The shapes

| Shape | Cards | Beats |
| --- | --- | --- |
| single | 1 | a lower single |
| pair | 2 the same | a lower pair |
| run | 3+ in a row, one each | a lower run of the same length |
| paired run | 3+ pairs in a row | a lower paired run of the same length |
| triple (蛋子) | 3 the same | anything below it, or a bigger triple |
| bomb (大蛋子) | 4 or more the same | anything below it, or a *bigger* bomb |
| two / three / four jokers | 2, 3 or 4 jokers | as a bomb, per the ladder |
| triple run | 3 triples in a row | as a bomb |
| false eight | two quads in a row | as a bomb |
| true seven | seven 4s | as a bomb |
| true eight | all eight 4s | as a bomb |
| both big aces | the two big aces | everything |

Runs read off the chain `4 5 6 7 8 9 10 J Q K A 2 3` and are compared by their **lowest** card, so
`A 2 3` is a run and sits above `Q K A`. A joker is not on the chain.

**A single 王 can only be taken by a 大蛋子 or better.** **A single 大A can only be taken by a 大蛋子 of
five or more**, or by anything above a 大蛋子.

## The 4

The 4 is the lowest card in the game and the only one with a second job. Thrown at a 蛋子 or a 大蛋子 it
**takes the pile into your hand** instead of beating it, and the pile is then whatever you threw — always
**one 4 fewer than the pile is tall**:

* a 蛋子 (3 cards) costs a pair of 4s
* a 大蛋子 of four costs three 4s
* a 大蛋子 of five costs four 4s

A single 4 cannot recycle anything. The cards you take start the next hand you hold, and the player after
you answers what you actually threw.

## A turn

The holder of the main big ace leads. Play runs **counter clockwise** from there.

* Play a shape, or **pass**.
* A pass does not take you out of the round: when everybody else has passed, the last player to have
  thrown leads again and the pile is swept.
* When a player runs out of cards they are **out**, and the turn order closes over them.
* A hand is dealt and sorted strongest first, so a pair, a triple or a run is a contiguous block of the
  fan.

## Winning

Play stops the moment one side has all of its members out — **but only the side that produced the very
first player out (头贡) wins**. If the other side got the 头贡, the board is a **draw** even if they then
run out, which is what stops a lone main big ace from farming a table by dumping their hand.

## Controls

* **Click a card** in your own fan to select or deselect it. Selected cards are tinted and outlined.
* **Play** (`Enter`) throws the selection, **Pass** (`Backspace`), **Clear** (`Delete`).
* **F9** opens the layout editor: drag to move, scroll to scale, right click an element to reset it,
  `R` resets the element under the pointer and `S` writes `config/daa-layout.properties`.

## Playing alone (bot mode)

**Bot mode** is on by default: every empty chair is filled with a bot, so one player can open a table
immediately. Turn it off and the table waits for five real players.

Next to it sits **bot strength**, 0/1/2:

* **0 relaxed** — beats whatever it can, saves no big ace and no joker, and does not know its partner.
  Good for learning what a legal play looks like.
* **1 normal** — knows its partner, keeps the four special cards (big ace, big joker, small joker and
  the fours) for their moment, and only reaches for a bomb when an opponent is nearly out.
* **2 fierce** — takes every four-recycle it is offered, bombs once an opponent is down to four cards,
  and will overspend to hold the pile.

There are four options in all; *reveal the second big ace* turns the hidden partner into an open one
and is the single biggest change to how the game feels. Bot strength is read when a bot is seated, so
changing it mid-game leaves the bots already at the table alone.
