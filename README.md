<h1 align="center">🎧 Moto Buds+ ANC 🎧</h1>
<h2 align="center">Minimal Android controller for Moto Buds+ ANC & battery</h2>

<p align="center">
    <img alt="hero" width="450" src="https://emoji-route.vercel.app/svg/🎧" />
</p>

> [!NOTE]
> A lightweight, vibe-coded Android app that talks directly to your **Moto Buds+** over Bluetooth RFCOMM (with GATT fallback). Toggle ANC modes, configure gestures, ring a lost bud, and check battery levels for the left bud, right bud, and case — no official app needed, no background services running when you close it.
>
> **This app is vibe-coded.** The UI mirrors the official Moto Buds app and the RFCOMM protocol behaviour was reverse-engineered from the official APK. Treat it as a hobby project, not a supported product.

## 🌟 Features

- **ANC Control** – Switch between Off, Transparency, Adaptive, and Noise cancellation, with the icon and label matching the official app.
- **Gesture Configuration** – Remap double press, triple press, and long press independently for the left and right bud.
- **Find My Buds** – Ring either earbud individually; stops automatically when you leave the page or after 60 seconds.
- **Sound Settings** – Toggle Hi-Res mode, game mode, and volume boost.
- **In-ear Detection & Dual Connection** – Manage both straight from the home screen.
- **Battery Monitoring** – Circular battery gauges for left, right, and case, including charging state.
- **Live Sync** – Connects and syncs state as soon as you open the app, then keeps polling so changes made elsewhere are picked up.
- **Quick Settings Tile** – A toggleable QS tile that turns ANC on or off without opening the app, with its own icon and label per state.
- **No Background Drain** – Stops all Bluetooth work when the app is closed or backgrounded.
- **Minimal & Fast** – No feature bloat, just the controls you need.

### Model support

Tuned for **Moto Buds+ XT2441-1**. Features the model does not support (auto volume, case recording, bass enhancement) are left out of the UI rather than shown as broken toggles.

## 💻 Installation

> Download the latest APK from the [releases page](https://github.com/ArnavK-09/moto-buds-plus-anc-apk/releases) and install it on your Android device.

###### or build from source

```bash
./build.sh
```

The app is written in 100% Kotlin. The build uses `aapt2`, `kotlinc` and `d8`
straight from the Android build tools — no Gradle, no Android Studio. The Kotlin
compiler is downloaded into `.toolchain/` automatically on first run.

R8 then shrinks the Kotlin standard library, which is ~98% of the unshrunk dex
and would otherwise leave the APK at ~750 KB. Our own classes are deliberately
kept whole (see `r8-rules.txt`) so no app logic is ever rewritten — the shrink
only drops stdlib code the app never calls.

## 🧹 Formatting

Kotlin sources are formatted with [ktlint](https://pinterest.github.io/ktlint/)
and everything else with Prettier:

```bash
npm install
npm run format        # apply
npm run format:check  # verify
```

## 📷 Screenshots

> Here's the expected look of Moto Buds+ ANC

| Home                                  | Sound                                   |
| ------------------------------------- | --------------------------------------- |
| ![Home](./screenshots/home-page.jpeg) | ![Sound](./screenshots/sound-page.jpeg) |

| Gestures                                      | Find                                       |
| --------------------------------------------- | ------------------------------------------ |
| ![Gestures](./screenshots/gestures-page.jpeg) | ![Find](./screenshots/find-buds-page.jpeg) |

---

## 💻 Contributing

> [!TIP]  
> We welcome contributions to improve **Moto Buds+ ANC**! If you have suggestions, bug fixes, or new feature ideas, follow these steps:

1. **Fork the Repository**  
   Click the **Fork** button at the top-right of the repo page.

2. **Clone Your Fork**  
   Clone the repo locally:

   ```bash
   git clone https://github.com/ArnavK-09/moto-buds-plus-anc-apk.git
   ```

3. **Create a Branch**  
   Create a new branch for your changes:

   ```bash
   git checkout -b your-feature-branch
   ```

4. **Make Changes**  
   Implement your changes (bug fixes, features, etc.).

5. **Commit and Push**  
   Commit your changes and push the branch:

   ```bash
   git commit -m "feat(scope): description"
   git push origin your-feature-branch
   ```

6. **Open a Pull Request**  
   Open a PR with a detailed description of your changes.

7. **Collaborate and Merge**  
   The maintainers will review your PR, request changes if needed, and merge it once approved.

## 🙋‍♂️ Issues

Found a bug or need help? Please create an issue on the [GitHub repository](https://github.com/ArnavK-09/moto-buds-plus-anc-apk/issues) with a detailed description.

## 👤 Author

<table>
  <tbody>
    <tr>
        <td align="center" valign="top" width="14.28%"><a href="https://github.com/ArnavK-09"><img src="https://github.com/ArnavK-09.png?s=100" width="130px;" alt="Arnav K"/></a><br /><a href="https://github.com/ArnavK-09"><h4><b>Arnav K</b></h3></a></td>
    </tr>
  </tbody>
</table>

---

<h2 align="center">📄 License</h2>

<p align="center">
<strong>Moto Buds+ ANC</strong> is licensed under the <code>Unlicense</code> License. See the <a href="https://github.com/ArnavK-09/moto-buds-plus-anc-apk/blob/main/LICENSE">LICENSE</a> file for more details.
</p>

---

<p align="center">
    <strong>🌟 If you find this project helpful, please give it a star on GitHub! 🌟</strong>
</p>
