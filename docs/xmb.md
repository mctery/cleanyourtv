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

Unlike the main project's launcher, this branch asks for more permissions. Granted at install: `INTERNET` for the
weather and the speed test, `BLUETOOTH` (up to Android 11) for the Bluetooth audio and remote battery icons,
`QUERY_ALL_PACKAGES` to see an app Play Store is still downloading, and `KILL_BACKGROUND_PROCESSES` for Open apps on
TVs that still let an app close another. Granted only over ADB (`mytv/install.sh`): `DUMP` and
`PACKAGE_USAGE_STATS`, for the list of open apps (below). The remote-button helper may read the screen
(`canRetrieveWindowContent`), for closing apps in Settings.

## Keys

| Key | Does |
|---|---|
| Left, Right | Change category |
| Up, Down | Move in the category |
| OK | Open |
| Hold OK, or Menu | Options, a list floating beside the item as on the PSP: Open, Move to another category, App info, and Close for an app left open |
| Back, Left | Closes the options (from Move, back to the options) |
| Home | From an app: back to where you were. On the XMB: Video, first item |

Home pressed on Home is a short pause for Android (pause, the intent, resume, in one go): the music, the wave and the
status row keep going through it, and only what a longer pause stops (an app in front, the assistant) stops them.

After 45 seconds without a key the menu fades out and only the wave stays (`xmb_idle_seconds` in config.xml or a
theme; `0` keeps it up). Any key brings it back.

## Boot animation

When the TV is turned on, Home plays a boot animation drawn with the wave itself, about five seconds (at 60 frames a
second while the lines travel, with every other row of the wave so the GPU keeps up; at 30 for the slow parts): black, then the wave's lines drawn in white from the left edge to the right with the start-up sound, each
row of the wave a little behind the next so the front is a soft fan rather than a wipe, every head carrying its own
light and leaving a trail that cools and sparkles behind it, the lines starting gently and gliding to a stop; a swell
of light as they meet the right edge; the colour rising out of the middle band to the top and bottom through a wide
soft edge while the last lines arrive, the lines settling into the wave as it always is; the menu coming in over the
colour, the categories one after another from the selected one, the column's rows cascading. Meanwhile Home makes
ready what the menu shows (the glow behind every item, every banner handed to the GPU), so the first moves are as
smooth as any later. A key during it brings the menu at once. It plays when the box starts and when it wakes from standby after half a minute
or more with Home in front (`xmb_boot_animation`: `power`, the default; `boot` for a start of the box only; `off`).
It follows the box's own boot logo, which only a rooted box could change. Settings > Diagnostics > Play the
start-up animation shows it at any time.

## Motion

Nothing on screen jumps. Moves take 240 ms with KittiwanaNotify's easing. In the status row an item that comes glows
up, one that goes fades out where it stood, one that changes (the Wi-Fi strength, the temperature, the dust's
colour) cross-fades, and the others slide to their new places. On the cross-bar a setting's value, a menu's name or
icon cross-fades where it is; an app installed, removed or moved and a menu added, removed or moved slide the
categories and rows that stay to their new places while new ones come in and the others fade out. The side list's
entries fade out when it closes or changes level, slide when one is carried, and cross-fade when a value changes; the
name prompt and the colour picker fade in and out. The wave drifts into a new colour (the hour's or the month's) over
a few seconds instead of switching, and follows quickly while a colour is being picked. The menu and the side list
draw at the display's 60 frames a second (measured on the Mi Box S while moving: 98 to 100% of frames on time); the
wave at 30, a slow drift, in a GL context of low GPU priority so the menu's frames go first. Settings > Diagnostics
can make the wave 60: smoother, twice the GPU's work, and on the Mi Box S a few more late frames in the menu.

## Status row

Left of the clock, white icons with a soft glow, no frames. Always there, from the clock leftwards:

| Icon | Shows |
|---|---|
| Palette | The theme colour: it glows in the wave's colour |
| Wi-Fi arcs, LAN | The connection: one, two or three lit arcs for the Wi-Fi strength, the LAN icon on a cable, a struck-out Wi-Fi when there is none |
| Sky and degrees | The weather now (below) |
| PM over 2.5, and a number | Fine dust in µg/m³, the letters glowing in the colour of Thailand's scale: blue up to 15, green up to 25, yellow up to 37.5, orange up to 75, red above |

Only while there is something to say, further left: the box's download speed (a gauge and "45 Mb/s", every app
together) while something downloads faster than 2 Mb/s, a download arrow with its progress while an app installs,
a USB drive, Bluetooth headphones or a speaker. Leftmost, in amber: the remote's battery under 20% (a battery on its
side in four blocks, as on the PSP and PS3), storage under
500 MB, ADB still on (USB debugging, which on many boxes also opens ADB on the network), and the remote-button
helper not running. For the last one Home first tries to fix it itself: 15 seconds after it starts, if the helper
should run and does not, it turns accessibility off and on once (with `WRITE_SECURE_SETTINGS`, as
`restore_key_services`), which binds a helper Android still holds as crashed.

The weather and the dust come from [Open-Meteo](https://open-meteo.com/) (no account or key; its forecast and
air-quality services) for the box's own last known location, rounded to 0.01 degree (about 1 km) before it is sent. It is read at most every 30 minutes, only while
Home is on screen, and hidden when the last answer is more than three hours old. The remote's battery is the level
Android reports for a paired input device or, where it reports none (an LE remote on Android 11, as on the Mi Box
S), the level Home reads from the remote's own Battery Service: at most every six hours, over a GATT connection of
its own beside Android's, closed again at once.

Settings > Status bar chooses what the row shows: a look (Standard, Simple, Everything, Clock only), each item
always, only when there is something to say, or hidden (OK goes round them and the row changes at once), the order
(OK picks an item up, Up and Down move it, OK puts it down), and whether the clock and the row stay on the wave when
the menu fades. The box's temperature, when the firmware lets apps read it, is one of the items (hidden by default;
not listed on a TV whose firmware keeps it from apps, as the Mi Box S does). The dust shows always by default, or
only from orange up; the speed only while something downloads by default, or always.
From a computer: `"xmb_status": ["network=always", "weather=hidden"]`; a setup made on the TV wins.

## Speed test

Network > Test internet speed measures how fast the box downloads right now, for when a film stutters: OK starts it,
the line under it counts up while it runs (and the status row shows the speed), and the result stays there, "115 Mb/s,
ping 32 ms, 1 minute ago". It runs only when asked: four tiny requests for the round trip, then six seconds (at most
90 MB) of download from Cloudflare's speed test service, `speed.cloudflare.com`, the one its own speed test page
uses. The first second does not count (the connection getting up to speed), and the ping is the TCP round trip the
service measured itself. Home going behind an app stops it.

## Open apps

Settings > Open apps says how many apps are left running behind Home and how much memory is free, "3 apps, 540 MB
of 1.9 GB RAM free". OK lists them, the most memory first, each with what it holds ("Crossy Road, 513 MB"; one
playing in the background says it is in use), under Close all apps. OK on an app closes it, Close all apps closes
every one, and Hold OK on an open app in any category offers Close too.

Android shows an app only its own processes, so the list is what the activity manager reports
(`dumpsys activity processes`, read in the background in about 0.1 s). That takes `DUMP` and `PACKAGE_USAGE_STATS`,
which only ADB can grant: `adb shell pm grant com.gathin.tvhome android.permission.DUMP`, the same for
`android.permission.PACKAGE_USAGE_STATS` (`mytv/install.sh` does both). Without them the item shows the free memory
and says the list needs them.

Closing is Force stop, as by hand. On the Mi Box S an ordinary app may end only its own processes
(`killBackgroundProcesses` answers `Invalid packageName` for any other, as on Android 14), so the remote-button
helper, an accessibility service that is already on, opens the app's page in the TV's Settings and presses Force stop
and then OK, under a black screen naming the app; Home comes back when it is done, about a second an app. It presses
nothing else: the row whose text is Settings' own "Force stop" (read from the Settings app, so in the TV's language)
on the page that shows the app's package name, then OK under a title and warning that are Settings' own force-stop
texts. Anything else, or nothing within a few seconds, and it leaves that app and says it could not close it. Keys
wait meanwhile; Back stops it. It reads the screen only while closing. Without the helper, Close tries
`killBackgroundProcesses`, which some TVs still allow.

A force-stopped app starts afresh when opened and stays asleep until then; apps that Google Play services call on
(Play Store, Play Movies) come back by themselves.

## Diagnostics

Settings > Diagnostics plays the boot animation, switches the wave between 30 and 60 frames a second (60 is smoother
and twice the GPU's work; `xmb_wave_fps` sets where it starts), records the screen for 30 seconds or 2 minutes (Android asks each time; a red camera shows in
the status row; the video goes to `Movies/XMB`, 1280x720) and switches on an event panel: the wave's frame rate and
the latest of what changed the screen (a rebuild of the cross-bar and why and how long the load took, an icon added
to or gone from the row, focus and screensaver changes, wave frames that came late). Its first line is the wave's cost:
frames a second, the CPU time of a frame on its thread (and of the heights in it) and the GPU time of a frame (a
timer query, where the GPU has them). The same lines go to logcat as `TvHome.Debug`.

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

TV apps go in Video, Music or Apps: known streaming and music apps by package name (`xmb_video_apps` and
`xmb_music_apps` in `config.xml`, which a theme can replace), then the app's own `android:appCategory`, else Apps.
The ready-made menus of your own (Games, Tools, Social...) collect apps by name the same way (`xmb_menu_apps`,
`"tools:com.android.vending"`) and by their own category. Move an app with **Hold OK > Move to another category**, or list it in
`xmb_categories` in `config.xml` or a theme's `config.json`: `"xmb_categories": ["com.example.tv=video"]`
(`video`, `music` or `apps`; a move made on the TV wins). Inside a category the order is the tile grid's:
`dock_apps`, then by name (an order saved earlier with the grid's Reorder counts until `dock_apps` changes).
`hidden_apps`,
`app_labels`, `app_banners` and `launch_actions` apply as on the grid.

Settings holds TV settings, Display and sound, Apps, Open apps and the menu-sound switch. Network shows the connection with
its address, the speed test and the Private DNS in use.

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
