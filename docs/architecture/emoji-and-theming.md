# Emoji and theming

## Emoji catalog

The picker is built from `app/src/main/assets/emoji/emoji.tsv`, a tab-separated catalog:

```
category <TAB> emoji <TAB> image <TAB> keywords <TAB> subcategory
```

`emoji.tsv` is committed. The pictures (`app/src/main/assets/emoji/img/<image>.webp`) are fetched by
`just emoji` and are not in git.

`:core/emoji` holds the immutable models:

- `EmojiCatalog` parses the catalog, groups by `EmojiCategory` and provides lookup by text.
- `EmojiRecents` keeps the last 36 used emoji, newest first, serialized as a space-separated string.
- `EmojiPins` keeps pinned emoji in order and supports toggling and reordering. Pinned emoji are
  shown in a separate section and can be dragged.
- `EmojiHidden` records hidden categories, subcategories and single emoji. It refuses to hide the
  last visible category or subcategory, so the picker cannot become empty.

`EmojiPrefs` stores recents, pins and hidden state in `SharedPreferences`. `EmojiAssets` reads the
catalog lazily and decodes pictures into an `LruCache` of about 400 bitmaps; a missing picture falls
back to the system emoji glyph. The asset code does not assume that images exist, so a build without
`just emoji` still produces a usable picker.

## Picker UI

`EmojiPanelView` is a `FrameLayout` placed over the keys by `KeysCover`. It contains:

- a top chip row for the user's own emoji and the categories;
- `EmojiGridView`, a custom `View` that draws only visible rows and scrolls with `OverScroller`;
- floating back and backspace buttons with key repeat;
- a dimmed popup for a held emoji with pin/unpin and hide actions.

`EmojiGridView` handles taps, long presses, pinned-emoji dragging with auto-scroll, and reordering
pins. `EmojiCellPainter` draws a bitmap when available, a system glyph otherwise, and marks pinned
and recent cells. Picking an emoji sends `onEmojiPick(text)` to the service, which inserts it through
`insertText`. Recents are not updated in incognito fields.

## Themes

Theming is a `:app/ui` concern; `:core` only provides layouts and colors for logic that needs them.

- `KeyboardPalette` is the color model: background, key faces and pressed states, action keys,
  labels, hints, previews, popups, gesture trail, strip and voice colors. Palettes are built from
  resource colors, from Material You dynamic colors, or derived from a few base colors.
- `KeyboardTheme` adds dimensions and an icon cache: row height, gaps, paddings, radius, shadow,
  trail width, strip height and text size. It caches tinted vector icons.
- `KeyboardThemes` is the theme registry and the persisted choice. It defines groups (default and
  color themes), a light/dark/system mode, key shapes and the `ThemeChoice.key` string. The key is
  compared on every field start; a changed key rebuilds the input view.
- Ten built-in themes cover classic, dynamic, stripe, coral, plum, pistachio, mint, blueberry,
  brownie and dieter. Recent themes, collapsed groups and square-key options are stored in the
  `keyboard_prefs` file.

The settings theme screen is Compose and writes the same preferences; the keyboard reads them when
it builds its next input view. Both light and dark variants exist; a dynamic theme follows the system
wallpaper colors.

## Drawing keys

`KeyRenderer` paints the keyboard without allocating during normal drawing. It draws:

- key shadows and rounded or pill key shapes;
- labels with the right x-height and cap-height centering;
- icons for shift, backspace, language and enter actions;
- hints, emoji hint and alternates;
- key previews above the pressed key and an alternates popup for long presses;
- the gesture trail as a tapering, fading path.

Geometry is computed once per size change in `KeyboardGeometry`. The renderer receives the key height
and the theme, not the layout logic.

## Why custom views

The IME owns the input window and must draw immediately on every touch. The key area, the strip and
the emoji grid are custom `View`s with direct `Canvas` drawing and no per-key view hierarchy. Compose
is used in the settings activity, where layout complexity and animation matter more than per-frame
cost and where the activity lifecycle is ordinary.
