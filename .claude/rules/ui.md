---
paths:
  - "app/src/main/java/com/fitzy/torio/ui/**"
---
# UI rules
- Material 3 only; use `MaterialTheme` tokens, no hard-coded colors.
- Screens are stateless composables fed by a ViewModel `StateFlow`; collect with `collectAsStateWithLifecycle`.
- Touch targets >= 48dp; every icon has a contentDescription.
- Player state comes from `PlayerConnection`, never from a second ExoPlayer instance.
