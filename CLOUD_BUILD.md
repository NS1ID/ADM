# Build ADM+ APK on GitHub (free, reliable)

Your phone has only 3.5 GB RAM, and the bundled Chromium SDK needs more than that
to produce the final DEX. Building on GitHub's servers (7 GB RAM) avoids the problem.

I already added the build workflow at `.github/workflows/build-apk.yml` and removed
the phone-specific `aapt2` override from `gradle.properties`, so the project is
ready for GitHub's x86-64 machines.

## Steps

1. Create a free GitHub account if needed: https://github.com/signup
2. Create a new repository (empty, no README).
3. Open the repo → **Add file → Upload files**.
4. Drag the **contents** of this `ADM` folder into the upload area
   (all files and subfolders: `app`, `gradle`, `build.gradle.kts`,
   `settings.gradle.kts`, `gradle.properties`, `.github`, etc.).
   Commit the files.
5. Open the **Actions** tab → select **Build ADM+ Debug APK** → **Run workflow**.
6. Wait for the green check (usually 10–20 minutes).
7. Click the completed run → download the **Android-Download-Manager-ADM-Plus** artifact (a ZIP).
8. Unzip it → install `Android Download Manager ADM+.apk` on your phone.
   (Android will ask to allow installing from unknown sources.)

If the build fails, open the failed run and read the log — send me the red error
lines and I will fix them here, then you re-upload the changed file(s).
