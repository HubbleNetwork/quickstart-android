# Release Quickstart App

Run the full release workflow for the Hubble Gateway SDK Android Quickstart.
Syncs the app version to the SDK version and triggers a CI release build.

## Steps

1. **Pre-flight checks**: verify clean working tree, on main branch
2. **Read SDK version**: get the current SDK version from `gradle/libs.versions.toml`
3. **Update app version**: set `versionName` to match SDK version, increment `versionCode` by 1
4. **Commit**: commit the version bump
5. **Tag**: create a git tag `v{version}`
6. **Push**: push the commit and tag to origin (tag triggers the release CI job)

## Instructions

Run each step sequentially. Stop and ask the user for confirmation before:
- The release version number (in case they want a different version than the SDK version)
- Pushing to origin (point of no return)

If any pre-flight check fails (dirty tree, wrong branch), stop and explain what needs to be fixed.

### Pre-flight

```bash
# Must be on main
git branch --show-current

# Must have clean working tree
git status
```

If the working tree is dirty, stop and tell the user to commit or stash changes first.
If not on main, stop and tell the user to switch to main first.

### Read SDK version

Parse the SDK version from `gradle/libs.versions.toml`:

```bash
grep 'hubble-gateway-sdk' gradle/libs.versions.toml
```

Extract the version string (e.g. `0.2.2` from `version = "0.2.2"`).

### Read current app version

Parse the current `versionCode` and `versionName` from `app/build.gradle`:

```bash
grep -E 'versionCode|versionName' app/build.gradle
```

### Confirm version

Present to the user:
- Current SDK version: `{sdk_version}`
- Current app versionName: `{current_version_name}`
- Current app versionCode: `{current_version_code}`
- Proposed versionName: `{sdk_version}` (matches SDK)
- Proposed versionCode: `{current_version_code + 1}`

Ask the user to confirm before proceeding.

### Update app/build.gradle

Use the Edit tool to update both `versionCode` and `versionName` in `app/build.gradle`.

### Commit and Tag

```bash
git add app/build.gradle
git commit -m "release: v{version}"
git tag v{version}
```

### Push

Ask the user to confirm before pushing. This is the point of no return... the tag push triggers the CI release build.

```bash
git push origin main
git push origin v{version}
```

### Verify

Tell the user:
- The release CI job was triggered by the tag push
- Check GitHub Actions for the build job status
- Once complete, the signed APK will be attached to the GitHub Release at `v{version}`
