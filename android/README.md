# Voltorb Flip Solver (Android)

Plain Java implementation of the solver. You can use the camera to fill the required
data fields. It helps to reduce screen glare and to be in a darker environment.

## Build

Needs a JDK (11+), the Android SDK (a platform + build-tools) and `zip`.

    make ANDROID_SDK=$HOME/android-sdk          # -> voltorb.apk
    make ANDROID_SDK=$HOME/android-sdk run      # adb install + launch

Options: `BUILD_TOOLS_VERSION=34.0.0`, `PLATFORM_VERSION=android-34`.
Signs with a local `debug.keystore` (created once).

## Files

    src/app/voltorb/MainActivity.java  UI, Camera2, glue
    src/app/voltorb/Vision.java        Fits camera reading to a frame (corner fitting, orientation)
    src/app/voltorb/ScreenFinder.java  Find the screen outline in the camera frame, straighten it
    src/app/voltorb/Reader.java        Clue & card reading from the straightened screen
    src/app/voltorb/BoardView.java     Board drawing + taps
    src/app/voltorb/Solver.java        actual solver (calculates probabilities)

