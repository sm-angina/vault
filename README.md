# Vault Android App — Build via GitHub Actions

No Android Studio needed. GitHub builds the APK for free in ~3 minutes.

---

## Steps

### 1. Create a free GitHub account
https://github.com/signup (skip if you have one)

### 2. Create a new repository
- Go to https://github.com/new
- Name it `vault-app` (or anything)
- Set it to **Private** (your code stays private)
- Click **Create repository**

### 3. Upload this folder
On the next screen, click **"uploading an existing file"** and drag the
entire contents of this folder into the browser. Then click **Commit changes**.

### 4. Watch it build
- Click the **Actions** tab at the top of your repo
- You'll see "Build Vault APK" running (yellow dot = in progress)
- Wait ~3 minutes for it to finish (green checkmark)

### 5. Download the APK
- Click the completed workflow run
- Scroll to the bottom — you'll see **Artifacts**
- Click **Vault-debug** to download a zip
- Unzip it — inside is `app-debug.apk`

### 6. Install on your phone
- Copy `app-debug.apk` to your Android phone
- Open it from your Files app
- If prompted, allow "Install unknown apps"
- Done — Vault appears on your home screen

---

## What the app does
- Opens full-screen (no browser bar)
- Tries LAN (192.168.0.200:8000) first — fast on home network
- Falls back to Tailscale (100.100.10.10:8000) automatically
- Retries every 4 seconds if neither is reachable
- Back button navigates within the app

## Rebuilding after changes
Just push any change to GitHub — Actions rebuilds automatically.
Or go to Actions tab → click "Build Vault APK" → "Run workflow".
