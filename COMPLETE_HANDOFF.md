# MeshLink — Complete Handoff Document (Fixed & Correct)

**Status:** All source code ready. 3 deployment steps remain.  
**Date:** 2026-11-14  
**Repository:** https://github.com/Iamhasaanzahid/meshlink  
**Release Tag:** v1.0.0

---

## ✅ What Is Already Done (100%)

| Item | Status |
|------|--------|
| GitHub repo created | ✅ https://github.com/Iamhasaanzahid/meshlink |
| Source code pushed | ✅ main branch (commit a01bdba) |
| Tag v1.0.0 | ✅ Pushed |
| GitHub Release v1.0.0 | ✅ Published |
| Android app code | ✅ meshlink_android/ (19 Kotlin files) |
| Backend API | ✅ meshlink_app/ (FastAPI + SQLite) |
| Purple website | ✅ MeshLink.html |
| Cloudflare package | ✅ cloudflare/meshlink/ |
| F-Droid metadata | ✅ meshlink_android/metadata/com.meshlink.app.yml |
| All tests | ✅ 43/43 Green |

---

## ❌ Prerequisites Checklist

Before starting, ensure the target machine has:

- [ ] **Node.js** v18+ (for wrangler) — download from https://nodejs.org/
- [ ] **npm** or **npx** (comes with Node.js)
- [ ] **GitHub CLI (`gh`)** — install: https://cli.github.com/  
  Run: `gh auth login`
- [ ] **Android SDK** — download from: https://developer.android.com/studio#command-tools  
  Set environment: `export ANDROID_HOME=$HOME/Android/Sdk`  
  Add to PATH: `export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin`
- [ ] **Gradle 8.5+** — should be auto-downloaded by wrapper
- [ ] **Cloudflare API Token** — create at: https://dash.cloudflare.com/profile/api-tokens  
  Token needs "Cloudflare Pages:Edit" permission
- [ ] **Signing Keystore** — create with:  
  `keytool -genkey -v -keystore meshlink-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias meshlink`

---

## 🚀 Step 1: Cloudflare Pages Deploy

```bash
cd /home/mhzaly/Documents/Default\ Project/cloudflare/meshlink

# Set environment variables (NEVER paste tokens in prompts!)
export CLOUDFLARE_API_TOKEN="your_token_here"
export CLOUDFLARE_ACCOUNT_ID="your_account_id_here"

# Deploy to Cloudflare Pages
npx wrangler pages publish . --project-name=meshlink

# The output URL will be printed. Example: https://meshlink-abc123.pages.dev
# Note: The URL may not be exactly "meshlink.pages.dev" if that subdomain is taken.
# Use the URL printed by wrangler.
```

**Verification:** Visit the printed URL. You should see the purple MeshLink landing page.

---

## 🚀 Step 2: Build & Upload APK

### Step 2a: Build APK (NOT AAB for GitHub Releases)

```bash
cd /home/mhzaly/Documents/Default\ Project/meshlink_android

# Build the release APK (installable directly on phones)
./gradlew assembleRelease \
  -PmeshlinkApiUrl="https://meshlink-api.onrender.com" \
  --no-daemon

# The APK will be at:
# app/build/outputs/apk/release/app-release.apk
```

**If build fails due to signing:**  
The debug keystore is created automatically by Android Gradle Plugin.  
If you need a release keystore, create one first:
```bash
keytool -genkey -v -keystore release.keystore -alias meshlink -keyalg RSA -keysize 2048 -validity 10000
```

Then add to `app/build.gradle`:
```gradle
signingConfigs {
    release {
        storeFile file("release.keystore")
        storePassword "your_password"
        keyAlias "meshlink"
        keyPassword "your_password"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled false
    }
}
```

### Step 2b: Upload APK to GitHub Release

```bash
gh release upload v1.0.0 \
  app/build/outputs/apk/release/app-release.apk \
  --repo Iamhasaanzahid/meshlink
```

**Verification:** Check https://github.com/Iamhasaanzahid/meshlink/releases/tag/v1.0.0  
The APK should be listed as an asset. Users can download and install directly.

---

## 🚀 Step 3: F-Droid Submission

### Step 3a: Fork fdroiddata

1. Go to: https://gitlab.com/fdroid/fdroiddata
2. Fork the repository (free GitLab account needed)
3. Clone your fork locally:
   ```bash
   git clone https://gitlab.com/your_username/fdroiddata.git
   cd fdroiddata
   ```

### Step 3b: Add MeshLink Metadata

Copy the metadata file to the correct location:
```bash
mkdir -p metadata
cp /home/mhzaly/Documents/Default\ Project/meshlink_android/metadata/com.meshlink.app.yml \
   metadata/com.meshlink.app.yml
```

**IMPORTANT:** The metadata file must be in your **fork** of fdroiddata, not in the app repo.

### Step 3c: Verify Dependencies (No Non-Free Software)

MeshLink has **no** non-free dependencies:
- ✅ No Google Play Services
- ✅ No Firebase
- ✅ No proprietary SDKs
- ✅ Pure Android SDK + Jetpack Compose (Apache 2.0)

### Step 3d: Test the Build Locally

Before submitting, F-Droid requires you to test the build:
```bash
# Install fdroid tools
pip3 install fdroidserver

# Lint the metadata
fdroid lint metadata/com.meshlink.app.yml

# Build the app
fdroid build com.meshlink.app

# If build succeeds, you'll get an APK in build/ directory
```

### Step 3e: Submit Merge Request

1. Commit your metadata changes:
   ```bash
   git add metadata/com.meshlink.app.yml
   git commit -m "Add MeshLink"
   ```

2. Push to your fork:
   ```bash
   git push origin master
   ```

3. Create a Merge Request from your fork to:
   - **Source:** `https://gitlab.com/fdroid/fdroiddata`
   - **Title:** `Add MeshLink`
   - **Description:** Follow F-Droid submission template

4. Wait for volunteer review (typically 1-2 weeks).

### Step 3f: After Acceptance

Once approved, your app will appear at:
```
https://f-droid.org/packages/com.meshlink.app/
```

F-Droid will automatically build and publish new releases when you tag them.

---

## ⚠️ Important Notes

### Cloudflare Pages URL
- The URL might not be exactly `meshlink.pages.dev`
- Use the URL printed by `wrangler` after deployment
- Example: `https://meshlink-xyz789.pages.dev`

### Render Free Tier (Backend)
- Render free tier services sleep after 15 minutes of inactivity
- First API call after sleep takes 30-60 seconds (cold start)
- This is normal and expected for free tier
- For production, use a paid plan or keep-alive ping

### Radio Mesh Testing
- **Cannot be tested from terminal** — requires 2 physical Android devices
- Device A: Enable SMS/Bluetooth/Wi-Fi Direct permissions
- Device B: Same permissions
- Test: Send message from A to B over Bluetooth (both in airplane mode except Bluetooth)
- Test: Send message over SMS (with SIM cards)
- Test: Send message over Wi-Fi Direct (both in airplane mode except Wi-Fi)

### Signing Keystore Security
- **Never** share keystore passwords in prompts
- Store keystore file in secure location (e.g., password manager)
- Use environment variables for passwords in CI/CD:
  ```bash
  export KEYSTORE_PASSWORD="your_password"
  export KEY_ALIAS="meshlink"
  export KEY_PASSWORD="your_key_password"
  ```

### F-Droid Build Requirements
- F-Droid builds from source (not prebuilt binaries)
- Requires reproducible builds
- All dependencies must be open source
- Metadata must include accurate license, description, screenshots
- Run `fdroid lint` and `fdroid build` before submitting

---

## 📋 Final Checklist (Run on Target Machine)

### Step 1: Cloudflare
- [ ] Node.js installed
- [ ] Wrangler installed (`npm install -g wrangler`)
- [ ] Cloudflare API Token set
- [ ] Website deployed to Cloudflare Pages
- [ ] URL verified in browser

### Step 2: APK
- [ ] Android SDK installed
- [ ] Gradle wrapper works (`./gradlew --version`)
- [ ] APK built (`app/build/outputs/apk/release/app-release.apk`)
- [ ] APK uploaded to GitHub Release
- [ ] APK downloadable from release page

### Step 3: F-Droid
- [ ] GitLab account created
- [ ] fdroiddata forked
- [ ] Metadata file added to fork
- [ ] `fdroid lint` passes
- [ ] `fdroid build` succeeds locally
- [ ] Merge Request created
- [ ] Waiting for review (1-2 weeks)

---

## 🎯 Expected Outcomes

| Step | Result |
|------|--------|
| **Cloudflare** | Website live at `https://[your-url].pages.dev` |
| **APK** | APK available at `https://github.com/Iamhasaanzahid/meshlink/releases/tag/v1.0.0` |
| **F-Droid** | App listed at `https://f-droid.org/packages/com.meshlink.app/` (after review) |

---

## 🔗 Useful Links

- Node.js: https://nodejs.org/
- GitHub CLI: https://cli.github.com/
- Cloudflare Dashboard: https://dash.cloudflare.com/
- Android Command-line Tools: https://developer.android.com/studio#command-tools
- F-Droid: https://f-droid.org/
- F-Droid fdroiddata: https://gitlab.com/fdroid/fdroiddata
- MeshLink Repo: https://github.com/Iamhasaanzahid/meshlink

---

## 📞 Support

If you encounter issues:
1. Check the specific error message in terminal output
2. Verify all prerequisites are installed
3. Check environment variables are set correctly
4. Review F-Droid build logs if submission fails
5. Test APK on physical device before uploading

---

**Main ne saara code ready kar diya hai. Ye handoff document copy karke kisi bhi assistant ko de sakti hain jo tumhari machine par tools available ho.**

