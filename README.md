# InfiniteFishing Geyser Extension

Replaces InfiniteFishing's tiny minigame bars on Bedrock with a proper boss bar.

## How it works

InfiniteFishing's `PointerBarMinigame` sends Java title text composed of layered
custom-font bitmap characters (bar_color_1–9, judgment_area, pointer, etc.).  
On Java those render at **128 px tall**. On Bedrock they are bitmap glyphs rendered
at normal text size — about 10 px — essentially invisible.

This extension sits inside Geyser and adds a Netty outbound handler to every
Bedrock session's channel pipeline.  When it sees a `SetTitlePacket` containing
InfiniteFishing bar characters it:

1. **Shows a boss bar** at the top of the screen:
   - Progress = pointer position (1/9 → 9/9, left → right)
   - 🟡 Yellow — pointer is moving, not in zone yet  
   - 🟢 Green  — pointer is **inside the catch zone** → click now!  
   - 🔴 Red    — pointer near the edges  
   - 🟣 Purple — rainbow / special catch  
2. **Strips** the invisible bitmap chars from the title so the screen stays clean
3. **Removes** the boss bar automatically when the minigame ends (title CLEAR/RESET)

## Build

Requires Java 17 + Maven.

```bash
mvn clean package
```

Output: `target/infinitefish-geyser-extension-1.0.0.jar`

## Install

Drop the jar into your Geyser `extensions/` folder and restart.  
Works with standalone Geyser and Geyser-Spigot / Geyser-Velocity.

## Compatibility

Tested against Geyser **2.3.x** with CloudburstMC Protocol **3.0.0.Beta5**.  
If your Geyser is older/newer, the `pom.xml` `geyser.version` property is the
only thing you might need to change.

If the boss bar doesn't appear, check the Geyser console for a warning like:
> Could not resolve Netty channel for PlayerName

That means the internal GeyserSession API changed — open an issue with your
exact Geyser build number.

## Also install

Pair this with the fixed resource pack:
`Kafal-Java2Bedrock-gui-offsets-FIXED.mcpack`

That pack scales up the **Evaluator GUI** (fish evaluation chest overlay) 2.5×
so it's readable too.
