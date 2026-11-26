# Troubleshooting & Testing Guide (Tecno & Samsung)

## 1. Tecno (HiOS) Primary Troubleshooting
- **Notifications not captured**: Open *Diagnostics -> Notification Capture Access* and toggle **PhoneBridge Capture** ON.
- **Events stuck in `PENDING` in background**:
  1. Settings -> Battery -> App Power Management -> PhoneBridge -> **Unrestricted**.
  2. Phone Master -> App Management -> Auto-Start -> Enable **PhoneBridge**.
  3. Lock PhoneBridge in the Recents switcher so HiOS does not kill the background service.

## 2. Samsung (One UI) Secondary Troubleshooting
- **Notifications not appearing**: Grant **Post Notifications** on Android 13+ in *Diagnostics*.
- **Missed events while screen off**:
  1. Settings -> Apps -> PhoneBridge -> Battery -> **Unrestricted**.
  2. Device Care -> Battery -> Background usage limits -> verify PhoneBridge is NOT in *Sleeping apps* or *Deep sleeping apps*.
  3. Use *Bridge -> Sync Missed Events* to immediately recover via HTTPS cursor sync.

## 3. Running Verification Tests
- **Android Unit, Robolectric & Fault-Injection Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **Relay Server Integration & Security Tests**:
  ```bash
  gradle -p relay-server test
  ```
