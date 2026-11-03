# StreamFlix design system (Material 3 Expressive, MDC 1.14)

The foundation every screen redesign builds on. It covers XML Views and ViewBinding on
`com.google.android.material:material:1.14.0`, for phones and Android TV (Leanback).

Rules:

- Never hard-code a colour in a screen layout. Use a theme attribute (`?attr/colorSurface`, ...).
- Use the type, shape and component styles below, not ad-hoc sizes or drawables.
- Animate with the springs in `ui/motion`, not `ObjectAnimator` + interpolator.

---

## 1. Themes

| Theme | Use |
|---|---|
| `Theme.StreamFlix` | Phone base. Parent `Theme.Material3Expressive.Dark.NoActionBar`. |
| `Theme.StreamFlix.Tv` | TV base. `Theme.StreamFlix`, plus the items of MDC's `Theme.Material3Expressive.Dark.NoActionBar.FocusRings` (copied verbatim), plus Leanback attrs (`defaultBrandColor`, `defaultSearch*Color`, `playback*Color`, `preferenceTheme`). Dark only. |
| `Theme.StreamFlix[.Tv].{Crimson,Ocean,Forest,Violet,Sunset,Amoled}` | Palettes. They only set colour roles. Generated file: `values/themes_palettes.xml`. |
| `Theme.StreamFlix.Light[.Palette]` | Light phone schemes. Generated, but unused for now. The app is dark-first, and older screens still assume a dark surface. |
| `AppTheme.Mobile` / `AppTheme.Tv` | Manifest aliases for the Crimson themes. |
| `AppTheme.SplashScreen` | Launch window: the logo on a Crimson surface. |

The main activities are `FragmentActivity`, so `<Button>` and `<TextView>` stay framework
widgets; they are not swapped for Material ones. Use `com.google.android.material.*` tags
when you want the Material widget.

Activities pick their theme like this:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    ThemeManager.applyMobileTheme(this)   // or ThemeManager.applyTvTheme(this)
    super.onCreate(savedInstanceState)
    ThemeManager.applySystemBars(this)     // edge-to-edge, icon contrast from colorSurface
    ...
}
```

`ThemeManager` (`utils/ThemeManager.kt`) works as follows:

- **Theme ids:** `wallpaper`, `crimson`, `ocean`, `forest`, `violet`, `sunset`, `amoled`.
- **Default:** `wallpaper` on Android 12+ phones that support Material You (`DynamicColors`); `crimson` everywhere else, including all TVs.
- **Legacy ids** are mapped on read:
  - `nero_amoled_oled` → amoled
  - `steel_blue`, `nord_frost` → ocean
  - `forest_night`, `emerald_luxe` → forest
  - `crimson_noir` → crimson
  - `midnight_violet`, `retro_neon` → violet
  - `sunset_cinema` → sunset
  - `default` / unset → the device default above
- **Wallpaper colours:** the activity uses the Crimson theme, and `DynamicColors.applyToActivityIfAvailable` overlays the wallpaper roles on top.
- **Settings:** Appearance lists the themes from `@array/theme_values`. "Wallpaper colours" is hidden where unsupported.
- **Old wrappers:** `Theme.StreamFlix.Sync`, `.Health` and `.PlayerOverlay` are now **empty overlays**, kept only so old references compile. New code needs no wrapper; Material widgets work directly.

## 2. Colour

### How the palettes are made

Palettes are generated from seed colours with Material Color Utilities (HCT), using the copy
bundled inside MDC 1.14. Run `tools/colorgen/GenSchemes.java` with that library on the
classpath, then `gen_xml.py`.

How each palette is built:

- **Primary and tertiary:** SchemeFidelity, so `colorPrimaryContainer` is the seed itself (for example #E50914 for Crimson).
- **Secondary:** chroma 16.
- **Neutrals:** chroma 4 and 8, so dark surfaces read as near-black with only a faint tint.

| Palette | Seed | primary | primaryContainer | surface | surfaceContainer |
|---|---|---|---|---|---|
| Crimson (default) | #E50914 | #FFB4AA | #E50914 | #181211 | #251E1D |
| Ocean | #1E6FD9 | #ACC7FF | #1E6FD9 | #121316 | #1F1F23 |
| Forest | #2E7D4F | #88D7A1 | #2E7D4F | #111411 | #1D201D |
| Violet | #7B4DFF | #CDBDFF | #7B4DFF | #141316 | #201F23 |
| Sunset | #F28C28 | #FFB77D | #F28C28 | #17120F | #241F1B |
| Amoled | Crimson accents | #FFB4AA | #E50914 | **#000000** | neutral tone 6 |

In Amoled, `surface`, `background`, `surfaceDim` and `surfaceContainerLowest` are all #000000.
The other containers are lifted with neutral tones 4, 6, 10 and 14, and `surfaceBright` uses
tone 20.

### Roles to use

| Role | Typical use |
|---|---|
| `?attr/colorSurface` | Screen background. `?attr/app_background_color` aliases it for older layouts. |
| `?attr/colorSurfaceContainerLowest` … `Low` … `Container` … `High` … `Highest` | Cards, rows, sheets, nav bar. Each step is more raised. |
| `?attr/colorSurfaceDim` / `colorSurfaceBright` | Dimmed or brightened large surfaces. |
| `?attr/colorOnSurface` | Primary text and icons. |
| `?attr/colorOnSurfaceVariant` | Secondary text, metadata, inactive icons. |
| `?attr/colorPrimary` / `colorOnPrimary` | Main actions, selection, **focus rings**, progress. |
| `?attr/colorPrimaryContainer` / `colorOnPrimaryContainer` | The brand-red hero CTA (Play), badges. |
| `?attr/colorSecondaryContainer` / `colorOnSecondaryContainer` | Tonal buttons, chips, the active nav indicator. |
| `?attr/colorTertiary*` | Accents that must differ from primary (e.g. "new episode"). |
| `?attr/colorError*` | Errors. |
| `?attr/colorOutline` / `colorOutlineVariant` | Borders and dividers. |
| `?attr/colorSurfaceInverse` / `colorOnSurfaceInverse` / `colorPrimaryInverse` | Snackbars and tooltips. |

To get a colour in Kotlin, use `ThemeManager.color(context, M.attr.colorSurfaceContainer)`,
where `M` is `com.google.android.material.R`. `colorPrimary` comes from
`androidx.appcompat.R.attr`.

Colours that stay **literal** on purpose:

- black video backgrounds and player overlays
- image scrims (`bg_home_fragment_*`, the gradients' transparent ends)
- white text with a shadow drawn over artwork
- the QR code's white background
- the favourites-selection cyan (`@color/favorite_selected`)
- genre tile colours (`@array/genres`)

## 3. Typography

The font is **Google Sans Flex** (SIL OFL 1.1).

- It ships as one variable file, `res/font/google_sans_flex.ttf` (~230 KB): instanced to `wght` 300–800 and subset to Latin, Latin-ext and Vietnamese. Other scripts fall back to the system font.
- The family resource is `@font/sf_sans`, with 400/500/600/700 instances.
- The licence is in `assets/licenses/GoogleSansFlex-OFL.txt`.
- The theme sets `android:fontFamily` too, so plain framework TextViews and Buttons also use it.

Use the type scale through theme attributes:

```xml
<TextView android:textAppearance="?attr/textAppearanceTitleLarge" ... />
<TextView android:textAppearance="?attr/textAppearanceHeadlineSmallEmphasized" ... />
```

| Scale | Attrs (each also has an `...Emphasized` variant) |
|---|---|
| Display | `textAppearanceDisplayLarge` / `Medium` / `Small` |
| Headline | `textAppearanceHeadlineLarge` / `Medium` / `Small` |
| Title | `textAppearanceTitleLarge` / `Medium` / `Small` |
| Body | `textAppearanceBodyLarge` / `Medium` / `Small` |
| Label | `textAppearanceLabelLarge` / `Medium` / `Small` |

- **Emphasized** is one weight step up: 400 becomes 500, and 500 becomes 600.
- **Weights** are applied with `android:textFontWeight`, which needs API 28 or later. Older devices get regular and bold only.
- **Usage:**
  - emphasized styles for hero titles, selected states and key numbers
  - Display/Headline Emphasized for TV hero titles
  - Title for rows and cards
  - Label for buttons and chips
- **Legacy names** used by existing layouts (`textAppearanceHeadline3-6`, `Subtitle1/2`, `Body1/2`, `Caption`, `Button`, `Overline`) map onto the M3 styles. Don't use them in new layouts.

## 4. Shape

The theme sets the M3 Expressive corner tokens (`shapeCornerSize*`, MDC 1.14):

| Token | dp | Shape style |
|---|---|---|
| ExtraSmall | 4 | `?attr/shapeAppearanceCornerExtraSmall` |
| Small | 8 | `?attr/shapeAppearanceCornerSmall` |
| Medium | 12 | `?attr/shapeAppearanceCornerMedium` |
| Large | 16 | `?attr/shapeAppearanceCornerLarge` |
| LargeIncreased | 20 | `?attr/shapeAppearanceCornerLargeIncreased` |
| ExtraLarge | 28 | `?attr/shapeAppearanceCornerExtraLarge` |
| ExtraLargeIncreased | 32 | `?attr/shapeAppearanceCornerExtraLargeIncreased` |
| ExtraExtraLarge | 48 | `?attr/shapeAppearanceCornerExtraExtraLarge` |

App shapes (`values/shapes.xml`). Use them with `app:shapeAppearance` on MaterialCardView,
ShapeableImageView or MaterialButton:

- `@style/ShapeAppearance.StreamFlix.Poster`: 16dp, for posters and cards.
- `@style/ShapeAppearance.StreamFlix.HeroBanner`: 28dp, for hero, backdrop and featured tiles.
- `@style/ShapeAppearance.StreamFlix.Sheet`: 28dp top corners, square bottom, for bottom sheets.
- `@style/ShapeAppearance.StreamFlix.Pill`: fully rounded.

## 5. Components

The theme sets these defaults (`values/styles_components.xml`). Every parent was checked
against the MDC 1.14 AAR.

| Theme attr | Style | Parent |
|---|---|---|
| `materialButtonStyle` | `Widget.StreamFlix.Button` | `Widget.Material3Expressive.Button` (filled, round, press shape-morph via `m3expressive_button_shape_state_list`) |
| `materialButtonTonalStyle` / `materialButtonOutlinedStyle` | `.Tonal` / `.Outlined` | `Widget.Material3Expressive.Button.TonalButton` / `.OutlinedButton` |
| — | `Widget.StreamFlix.Button.Text`, `.Icon`, `.Icon.Tonal` | Expressive text and icon buttons |
| `materialCardViewStyle` (+ `Filled`) | `Widget.StreamFlix.Card` | `Widget.Material3.CardView.Filled` + Poster shape |
| `materialCardViewElevatedStyle` | `Widget.StreamFlix.Card.Elevated` | `Widget.Material3.CardView.Elevated` + Poster shape |
| — | `Widget.StreamFlix.Card.Hero` | Filled + HeroBanner shape |
| `bottomNavigationStyle` | `Widget.StreamFlix.BottomNavigation` | `Widget.Material3Expressive.BottomNavigationView` |
| `navigationRailStyle` | `Widget.StreamFlix.NavigationRail` | `Widget.Material3Expressive.NavigationRailView` |
| `circularProgressIndicatorStyle` | `Widget.StreamFlix.CircularProgressIndicator` | `Widget.Material3Expressive.CircularProgressIndicator.Wavy` |
| `linearProgressIndicatorStyle` | `Widget.StreamFlix.LinearProgressIndicator` | `Widget.Material3Expressive.LinearProgressIndicator.Wavy` (use `.Flat` for poster watch-progress strips) |
| `loadingIndicatorStyle` | `Widget.StreamFlix.LoadingIndicator` (+ `.Contained`) | `Widget.Material3.LoadingIndicator` |
| `materialAlertDialogTheme` | `ThemeOverlay.StreamFlix.MaterialAlertDialog` | `ThemeOverlay.Material3Expressive.MaterialAlertDialog` |
| `alertDialogTheme` | — | `ThemeOverlay.Material3Expressive.Dialog.Alert` |
| `bottomSheetDialogTheme` | `ThemeOverlay.StreamFlix.BottomSheetDialog` | `ThemeOverlay.Material3.BottomSheetDialog` + Sheet shape, `surfaceContainerLow` |
| `chipStyle` | `Widget.StreamFlix.Chip` | `Widget.Material3.Chip.Filter` |
| `sliderStyle` | `Widget.StreamFlix.Slider` (TV: `.Tv`) | `Widget.Material3Expressive.Slider.Small` (TV `.Medium`) |
| `materialSearchBarStyle` / `materialSearchViewStyle` | `Widget.StreamFlix.SearchBar` / Expressive SearchView | `Widget.Material3Expressive.SearchBar` / `.SearchView` |

Loading UI:

- Full-screen and list loading uses `com.google.android.material.loadingindicator.LoadingIndicator`. The ids are unchanged: `pb_is_loading`, `pb_load_more_is_loading`, `pb_pair_qr`.
- Toggle it with `isVisible`, or with `show()` / `hide()` for the animated version.

Older layouts still use `AppTheme.Widget.{Tv,Mobile}.Button` (framework buttons). Their
colours follow the palette now, but new screens should use MaterialButton.

## 6. Motion (`ui/motion`)

Springs come from the MDC 1.14 theme attrs `motionSpring{Fast,Default,Slow}{Spatial,Effects}`.
Our values are in `values/motion.xml`:

| Spring | Stiffness | Damping | Use |
|---|---|---|---|
| `Spring.SpatialFast` | 1400 | 0.8 | focus and press scale, small moves |
| `Spring.SpatialDefault` | 700 | 0.8 | most position and size changes |
| `Spring.SpatialSlow` | 300 | 0.8 | sheets, full-screen hero transitions |
| `Spring.EffectsFast` | 3800 | 1.0 | quick colour and alpha changes |
| `Spring.EffectsDefault` | 1600 | 1.0 | fades |
| `Spring.EffectsSlow` | 800 | 1.0 | large cross-fades |

The guide's spatial damping of 0.6 was raised to 0.8, which gives a slight overshoot instead of
a wobble on TV.

```kotlin
import com.streamflixreborn.streamflix.ui.motion.*

view.springScale(1.08f)                          // SpatialFast
banner.springTranslationY(0f, Spring.SpatialSlow)
scrim.springAlpha(0f)                            // EffectsDefault, never overshoots
card.springTranslationZ(6.dp)                    // lift
val force: SpringForce = Springs.spatialDefault(context) // for your own SpringAnimation
```

- **Interruptible:** the helpers reuse one `SpringAnimation` per view and property, so calling them again mid-flight retargets smoothly.
- **Reduced motion:** when the animator duration scale is 0, every helper snaps to the target.

### Phone cards

```kotlin
PressScale.attach(binding.root)   // 0.96 while pressed, springs back; clicks/ripples unaffected
```

`PressScale` sets the view's `OnTouchListener`, replacing any existing one.

## 7. TV focus pattern

```kotlin
// TV ViewHolder
FocusScaleAnimator.attach(binding.root)   // MaterialCardView: the ring is the card stroke
FocusScaleAnimator.attach(binding.ivPoster, ringCornerRadiusPx = 16.dp)
```

When an item gains focus:

1. It scales from 1.0 to 1.08 (`Spring.SpatialFast`).
2. It lifts 6dp in Z.
3. A 3dp `?attr/colorPrimary` ring appears. On a MaterialCardView this is the card's stroke; on other views it is a rounded foreground.

On blur it springs back. An existing `OnFocusChangeListener` keeps working, because it is
chained.

Guidelines:

- **Clipping:** give the RecyclerView or row `android:clipChildren="false"` and `android:clipToPadding="false"` so focused items aren't clipped.
- **Focus colour:** use `?attr/colorPrimary` for focus everywhere. That covers the XML focus-ring drawables (`fg_item_*_tv`, `bg_item_*_tv`) and selected TV buttons (`bg_button_tv`, primary fill with `colorOnPrimary` text).
- **Framework widgets:** on TV, `selectableItemBackground` and the framework button, checkbox and radio styles are MDC focus-ring variants (`focusRingsEnabled=true`). Plain clickable views get an M3 focus ring for free.
- **Performance:** the main TV is a 1 GB Android 9 projector. Scale and translationZ are cheap. Avoid blur, large shadows, alpha over big images, and animating more than the focused item.
- **Leanback preferences:** `LeanbackPreferenceFragmentCompat` uses `preferenceTheme = PreferenceThemeOverlay.StreamFlix.Leanback`, which puts the Leanback preference theme on `colorSurface`.
