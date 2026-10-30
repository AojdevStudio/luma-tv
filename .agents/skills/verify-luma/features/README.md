# Luma verification map

The primary surface is the TCL launcher and its remote. All live commands use the fixed device in `scripts/verify.py`. The command examples run from the repository root.

## Preconditions

- The TCL address is `10.69.3.171:5555` and its existing ADB key is trusted.
- The source build key already exists. No run creates credentials.
- One operator drives this TV at a time. Stop if another run owns port 5042.
- For `safe`, the TV is awake and Luma is already HOME.
- Leave permission and installation prompts to Ossie. Keep CleanYourTV enabled and preserve the existing disabled packages.

## Features

- [Source build and installation](installation.md) checks the source-built APK and the authorized installation path.
- [Launch and Home](home.md) checks launch, the Home-key event, visible focus, and the current launcher baseline.
- [Capabilities](capabilities.md) checks the allowlisted action center, preview and confirmation flow, local result persistence, and unavailable states.
- [Return to the prior launcher](restore.md) checks the saved journal and the authorized restore path.

The first safe run covers Launch and Home plus the offline tests. It excludes live install and live restore. Record each exclusion alongside the evidence directory. A different entry point is not a substitute for a skipped journey.
