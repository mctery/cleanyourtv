# XMB home

A second Home in the same launcher: a PlayStation 3 style cross-bar. Five categories run across the screen
(Settings, Video, Music, Apps, Network), the selected category's items run down from it, and the selected item
always sits right under the category icon, with the items already passed stacked above the bar, as on a PS3.
Behind it runs the PS3 wave in a colour that changes every month and dims at night.

It is off by default. The tile grid stays the launcher's Home until you switch.

Tested on a Xiaomi Mi Box S 2nd gen (MiTV-AFKR0, Google TV 11, Mali-G31). It needs OpenGL ES 3.0 for the wave;
without it the menu works over a plain black background.

## Switching

- **To the XMB:** on the tile grid, highlight Settings at the top right, hold OK, and choose **XMB Home**.
  From a computer: `tools/tv.py 'am start -n com.gathin.tvhome/.XmbActivity -a com.gathin.tvhome.action.XMB_HOME'`.
- **Back to the grid:** in the XMB, Settings category, **Classic Home**. From a computer, the same command with
  `com.gathin.tvhome.action.CLASSIC_HOME`.

While the XMB is Home, the tile grid is switched off (so it also leaves the TV's app list). On Android 10 and
later Home is chosen per app, and with two Home screens in one app Android has no saved choice between them and
shows its "Choose Home app" dialog. So exactly one of them is on at a time. `launcher/build-and-install.sh` starts
the grid to check an install: switch back to the grid before running it, then switch to the XMB again.

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

## What goes where

TV apps go in Video, Music or Apps: known streaming and music apps by package name, then the app's own
`android:appCategory`, else Apps. Move an app with **Hold OK > Move to another category**, or list it in
`xmb_categories` in `config.xml` or a theme's `config.json`: `"xmb_categories": ["com.example.tv=video"]`
(`video`, `music` or `apps`; a move made on the TV wins). Inside a category the order is the tile grid's:
the order saved with Reorder, then `dock_apps`, then by name. `hidden_apps`, `app_labels`, `app_banners` and
`launch_actions` apply as on the grid.

Settings holds TV settings, Display and sound, Apps, the menu-sound switch and Classic Home. Network shows the
connection with its address and the Private DNS in use.

## Sounds

None ship with the launcher. Put your own files on the TV and the XMB plays them: `cursor`, `cursor-down`,
`confirm`, `cancel` and `opening`, as `.wav` or `.ogg`. The adb shell often cannot write into the launcher's
folder, so send them through its theme provider:

```sh
tools/tv.py --push cursor.wav /data/local/tmp/cursor.wav
tools/tv.py 'content write --uri content://com.gathin.tvhome.theme/sfx/cursor.wav < /data/local/tmp/cursor.wav; rm /data/local/tmp/cursor.wav'
```

Larger files (an `opening` cue) go through `tools/b64push.py` instead of `--push`. The XMB reads them when Home
starts; Settings > Menu sounds switches them off and on. A quiet 16-bit WAV is brought up to full level before it
plays (at most 30 dB louder): cues made for a computer's speakers are otherwise hard to hear on a TV, next to
programmes mastered near full scale.

## Cost

On the Mi Box S, about 18% of one CPU core while the XMB is on screen, and about 17 ms of each 33 ms frame on
its Mali-G31: the wave is drawn at 30 frames a second at the screen's resolution (up to 1080 lines, 4x
multisampled), its height map recalculated 15 times a second.
Nothing runs while an app, the screensaver or the assistant is in front.

With `restore_key_services` on (and the install script's `RESTORE_KEY_SERVICES=1`), the XMB puts the remote's
key helpers back after a force stop, as the tile grid does.

## Credits

- The wave is a Java and OpenGL ES port of [PlayStation-3-XMB](https://github.com/linkev/PlayStation-3-XMB) by
  Mart (MIT, `launcher/XMB-WAVE-LICENSE-MIT.txt`), tuned as in KittiwanaNotify's admin, whose cross-bar this
  layout follows (anchor, timing, month colours, idle fade).
- The icons are [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0,
  `launcher/ICONS-LICENSE-APACHE.txt`).
