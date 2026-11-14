# Flames and Steel: playtest release {{RELEASE}}

Thank you for helping playtest **Flames and Steel**, a World War II strategy game for TripleA by James. This download contains the map and a modified TripleA engine that it needs: Flames and Steel will **not** run on regular TripleA.

This is a test version. Rules change from one release to the next, so please always play the newest release, and make sure everyone in your game has the same one. This release is game build **{{BUILD}}** with map version **{{MAP_VERSION}}**.

---

## 1. Install Java (once)

The game needs Java **25** or newer.

1. Go to **https://adoptium.net** and download **Temurin 25** for your system.
   - Windows: the `.msi` installer.
   - Mac: the `.pkg` installer. On a Mac with an Apple chip (M1, M2, M3 or later), choose **aarch64**; on an older Intel Mac, choose **x64**.
   - Linux: your distribution's Java 25 package, or the Temurin `.tar.gz`.
2. Run the installer and accept the defaults.

You only need to do this once. If Java is missing or too old, the start file tells you so and opens the download page.

## 2. Install and start the game

1. Download the latest release zip from **{{RELEASES_URL}}** and unzip it anywhere, for example on your Desktop. Keep the folders together: the start file needs the `game` and `flames_and_steel` folders next to it.
2. Start the game:
   - **Windows:** double-click **Run Flames and Steel.bat**. If Windows SmartScreen warns you, click **More info**, then **Run anyway**. A black window opens: keep it open while you play (closing it closes the game).
   - **Mac:** double-click **Run Flames and Steel.command**. The first time, macOS may refuse because the file is from an unidentified developer:
     - Right-click (or Control-click) the file, choose **Open**, then **Open** again; or
     - On newer macOS, open **System Settings → Privacy & Security**, scroll down, and click **Open Anyway** next to the message about the file.
     - If you see "permission denied", open Terminal, type `chmod +x ` (with a space after it), drag the `.command` file into the Terminal window, and press Return. Then double-click it again.
     - A Terminal window opens: keep it open while you play.
   - **Linux:** run `run-flames-and-steel.sh` (for example `bash run-flames-and-steel.sh` in a terminal).

The start file copies the map into your TripleA maps folder and starts the game. The maps folder is `Documents/triplea/downloadedMaps` if you already have a `triplea` folder in Documents, otherwise `triplea/downloadedMaps` in your home folder (on Windows, `C:\Users\<you>\triplea\downloadedMaps`). Only the `flames_and_steel` folder there is replaced: if you have regular TripleA installed, your other maps and saved games are not changed. (If you once told TripleA to keep its maps somewhere else, under Engine Preferences, put that setting back to the default first.)

## 3. Join the private network (ZeroTier, once)

Live games run over a small private network, so nobody has to change router settings and nobody needs an account.

1. Download and install **ZeroTier** from **https://www.zerotier.com/download/**.
2. Join the network:
   - **Windows:** click the ZeroTier icon in the system tray (bottom right, near the clock; you may need the ^ arrow), choose **Join New Network…**, enter the network ID, and click **Join**.
   - **Mac:** click the ZeroTier icon in the menu bar (top right), choose **Join New Network…**, enter the network ID, and click **Join**.
   - The network ID is **{{NETWORK_ID}}**.
3. Tell James you've joined. He has to approve you before you can connect.
4. Once approved, your computer gets a ZeroTier address such as `10.147.17.23`. To find it:
   - Windows: ZeroTier icon → **Show Networks…** → **Managed IPs**.
   - Mac: ZeroTier icon → the network → **Managed IPs**.

Only people James approves can connect to this network.

## 4. Play a live game

One player hosts; everyone else joins. Nobody can join a game that has already started, so start together.

**The host:**
1. Start the game. On the first screen, click **Select Game** and choose **Flames and Steel** (or **Saved Game** to continue a saved one).
2. Click **Host Networked Game**, type your name, keep the port **3300**, and click **OK**.
3. Tell the other players your **ZeroTier address** (step 3.4).
4. A list of nations appears. Untick **Local** for each nation another player will play.
5. When everyone has taken their nations, click **Play**.

**Everyone else:**
1. Start the game and click **Connect to Networked Game**.
2. Type your name, keep the port **3300**, enter the host's **ZeroTier address** as the server address, and click **Connect**.
3. Click **Play** next to each nation you will play. Then wait for the host to start.

**Windows firewall:** the first time, Windows may ask whether to allow Java on networks. Tick **both Private and Public** and click **Allow**. Windows often treats the ZeroTier network as "Public", so allowing Private only is not enough. If players can't connect, this is the most likely reason. The host's computer is the one that must allow incoming connections.

**Saving:** save the game often (**File → Save**), and always before stopping for the day. The host's save is the one that counts. If someone gets disconnected, the host's game stops and saves itself automatically (the message names the file, in the `autoSave` folder of saved games); the host then clicks **Saved Game**, picks that file, clicks **Host Networked Game** again, and everyone reconnects and takes their nations again.

**Same version:** every player must have the same release. If your release differs from the host's, the game refuses to connect and says which build or map version the host has: download that release.

**Play against people, not the computer:** the computer opponent doesn't understand Flames and Steel's special rules yet, so the game only offers human players for this map. A nation nobody plays must still be given to someone.

**Mac notes:** the game itself is the same as on Windows. Some keyboard shortcuts shown as Ctrl in the game may use Cmd on a Mac; the buttons always work. Nobody has tested this release on a Mac yet, so please report anything that looks different.

## 5. Rules

The game's notes (in the game, **Help → Game Notes**) explain the rules that differ from standard TripleA: the air phase, manpower, research stars, Special Production Centers, Vichy France, China, and more.

## 6. Reporting problems and ideas

This is what the playtest is for, so please report anything that seems wrong, unclear, too strong or too weak.

- Report it at **{{ISSUES_URL}}**.
- Say which release you were playing, which nation, and which round.
- For a bug, **attach the saved game** from just before it happened if you can, and describe what you expected and what happened instead. The game also writes `game/console.log` in the release folder; attach that too.
- For a balance idea, say what you saw in your game that made you think so.

Thank you for playtesting!

---

*Flames and Steel runs on a modified version of TripleA, which is free software under the GNU General Public License v3 (see `LICENSE`). The modified source code is at **{{REPO_URL}}**.*
