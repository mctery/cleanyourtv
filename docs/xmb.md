# XMB home

On this branch the launcher's Home is a PlayStation 3 style cross-bar. Five categories run across the screen
(Settings, Video, Music, Apps, Network), the selected category's items run down from it, and the selected item
always sits right under the category icon, with the items already passed stacked above the bar, as on a PS3.
Behind it runs the PS3 wave in a colour that changes every month and dims at night.

Tested on a Xiaomi Mi Box S 2nd gen (MiTV-AFKR0, Google TV 11, Mali-G31). It needs OpenGL ES 3.0 for the wave;
without it the menu works over a plain black background.

## The only Home

The XMB answers HOME through the `XmbHome` alias. The tile grid (`MainActivity`) is still in the code, so changes
from the main project keep merging, but it is switched off in the manifest: it never starts and is not in the TV's
app list. Two HOME activities in one app would not work anyway: on Android 10 and later Home is chosen per app,
and between two of its own Android has no saved choice and shows its "Choose Home app" dialog.
`launcher/build-and-install.sh` installs it like the grid, and makes `XmbHome` the Home activity.

The grid's on-TV tools (Launcher settings, Edit dock, Reorder, the app library, module cards) go with it.
The order of the apps is still `dock_apps` from `config.xml` or a theme.

Unlike the main project's launcher, this branch asks for three more permissions, all granted at install:
`INTERNET` for the weather, `BLUETOOTH` (up to Android 11) for the Bluetooth audio and remote battery icons, and
`QUERY_ALL_PACKAGES` to see an app Play Store is still downloading.

## Keys

| Key | Does |
|---|---|
| Left, Right | Change category |
| Up, Down | Move in the category |
| OK | Open |
| Hold OK, or Menu | Options: Open, Move to another category, App info |
| Back | Closes the options |
| Home | From an app: back to where you were. On the XMB: Video, first item |

After 45 seconds without a key the menu fades out and only the wave stays. Any key brings it back.

## Status row

Left of the clock, white icons with a soft glow, no frames. Always there, from the clock leftwards:

| Icon | Shows |
|---|---|
| Palette | The theme colour: it glows in the wave's colour |
| Wi-Fi arcs, LAN | The connection: one, two or three lit arcs for the Wi-Fi strength, the LAN icon on a cable, a struck-out Wi-Fi when there is none |
| Sky and degrees | The weather now (below) |

Only while there is something to say, further left: a download arrow with its progress while an app installs,
a USB drive, Bluetooth headphones or a speaker. Leftmost, in amber: the remote's battery under 20%, storage under
500 MB, ADB still on (USB debugging, which on many boxes also opens ADB on the network), and the remote-button
helper not running. For the last one Home first tries to fix it itself: 15 seconds after it starts, if the helper
should run and does not, it turns accessibility off and on once (with `WRITE_SECURE_SETTINGS`, as
`restore_key_services`), which binds a helper Android still holds as crashed.

The weather comes from [Open-Meteo](https://open-meteo.com/) (no account or key) for the box's own last known
location, rounded to 0.01 degree (about 1 km) before it is sent. It is read at most every 30 minutes, only while
Home is on screen, and hidden when the last answer is more than three hours old. The remote's battery is the level
Android reports for a paired input device or, where it reports none (an LE remote on Android 11, as on the Mi Box
S), the level Home reads from the remote's own Battery Service: at most every six hours, over a GATT connection of
its own beside Android's, closed again at once.

Settings > Status bar chooses what the row shows: a look (Standard, Simple, Everything, Clock only), each item
always, only when there is something to say, or hidden (OK goes round them and the row changes at once), the order
(OK picks an item up, Up and Down move it, OK puts it down), and whether the clock and the row stay on the wave when
the menu fades. The box's temperature, when the firmware lets apps read it, is one of the items (hidden by default).
From a computer: `"xmb_status": ["network=always", "weather=hidden"]`; a setup made on the TV wins.

## Diagnostics

Settings > Diagnostics records the screen for 30 seconds or 2 minutes (Android asks each time; a red camera shows in
the status row; the video goes to `Movies/XMB`, 1280x720) and switches on an event panel: the wave's frame rate and
the latest of what changed the screen (a rebuild of the cross-bar and why, an icon added to or gone from the row,
focus and screensaver changes, wave frames that came late). The same lines go to logcat as `TvHome.Debug`.

## Theme colour

Settings > Theme colour opens a list right of the column: Monthly (a colour for each month, the default), the
twelve month colours by name, and Custom. The wave follows the selection as you move. Custom opens a picker: Left
and Right turn the hue, Up and Down the brightness, OK saves, Back cancels. Every colour dims at night like the
month's. From a computer: `"xmb_color": "#234EBA"` (or `"month"`, `"month1"` to `"month12"`) in `config.xml` or a
theme; a colour picked on the TV wins until that value changes.

## Menus

Settings > Menus lists the categories: pick one to rename it, change its icon (from 40 Material Symbols), move it
left or right, or remove it (your own only); Add a menu offers Games, Tools, Social, Sports, News, Kids, Learning,
Shopping and Photos, or a name typed with the TV's keyboard (or said into the remote). From a computer:
`"xmb_menus": ["games:Games:sports_esports", "tools:Tools:handyman"]`. Menus changed on the TV win; a menu a theme
adds later still appears unless it was removed on the TV.

Those preset keys also collect apps by themselves: games takes every app that says it is a game, social, news and
photos the apps of that Android category, tools the productivity apps, browsers, remote-desktop apps and Play
Store, and each a few known package names. An app moved with Hold OK, or listed in `xmb_categories`
(`"com.example.app=tools"`), goes where it was put. A menu of your own with no apps yet shows a line saying how to
fill it; an empty built-in one is left out.

## Background music

A quiet loop while Home is in front, as on the PlayStation, faded in and out. Settings > Background music
switches between off, low, medium and high. It never plays over other sound: it does not start while another app
plays music, and stops when anything else takes the audio focus (the assistant, a cast). None ships with the
launcher: put a file on the TV as `sfx/bgm.flac` (or `.ogg`, `.mp3`, `.m4a`, `.wav`) the way the sounds below go
on, and it loops without a gap.

## What goes where

TV apps go in Video, Music or Apps: known streaming and music apps by package name, then the app's own
`android:appCategory`, else Apps. Move an app with **Hold OK > Move to another category**, or list it in
`xmb_categories` in `config.xml` or a theme's `config.json`: `"xmb_categories": ["com.example.tv=video"]`
(`video`, `music` or `apps`; a move made on the TV wins). Inside a category the order is the tile grid's:
`dock_apps`, then by name (an order saved earlier with the grid's Reorder counts until `dock_apps` changes).
`hidden_apps`,
`app_labels`, `app_banners` and `launch_actions` apply as on the grid.

Settings holds TV settings, Display and sound, Apps and the menu-sound switch. Network shows the connection with
its address and the Private DNS in use.

## Sounds

None ship with the launcher. Put your own files on the TV and the XMB plays them: `cursor`, `cursor-down`,
`confirm`, `cancel` and `opening`, as `.wav` or `.ogg`. The adb shell often cannot write into the launcher's
folder, so send them through its theme provider:

```sh
tools/tv.py --push cursor.wav /data/local/tmp/cursor.wav
tools/tv.py 'content write --uri content://com.gathin.tvhome.theme/sfx/cursor.wav < /data/local/tmp/cursor.wav; rm /data/local/tmp/cursor.wav'
```

Larger files (an `opening` cue, the music) go through `tools/b64push.py` instead of `--push`. The XMB reads them when Home
starts; Settings > Menu sounds switches them off and on. A quiet 16-bit WAV is brought up to full level before it
plays (at most 30 dB louder): cues made for a computer's speakers are otherwise hard to hear on a TV, next to
programmes mastered near full scale.

## Cost

On the Mi Box S, about 18% of one CPU core while the XMB is on screen, and about 17 ms of each 33 ms frame on
its Mali-G31: the wave is drawn at 30 frames a second at the screen's resolution (up to 1080 lines, 4x
multisampled), its height map recalculated 15 times a second.
Nothing runs while an app, the screensaver or the assistant is in front.

With `restore_key_services` on (and the install script's `RESTORE_KEY_SERVICES=1`), the XMB puts the remote's
key helpers back on Android's list after a force stop, as the tile grid does. On the Mi Box S that is not always
enough: Android can keep a helper marked as crashed, and unbound, until accessibility goes off and on again.

## Credits

- The wave is a Java and OpenGL ES port of [PlayStation-3-XMB](https://github.com/linkev/PlayStation-3-XMB) by
  Mart (MIT, `launcher/XMB-WAVE-LICENSE-MIT.txt`), tuned as in KittiwanaNotify's admin, whose cross-bar this
  layout follows (anchor, timing, month colours, idle fade).
- The icons are [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0,
  `launcher/ICONS-LICENSE-APACHE.txt`).
