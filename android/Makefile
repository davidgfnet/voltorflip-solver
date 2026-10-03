# --- Config (override on the command line or via env) ---
BUILD_TOOLS_VERSION  ?= 34.0.0
PLATFORM_VERSION     ?= android-34
JAVAC_FLAGS          ?= --release 11
KEYSTORE             ?= debug.keystore

ifeq ($(strip $(ANDROID_SDK)),)
  $(error ANDROID_SDK is not set - e.g. `make ANDROID_SDK=$$HOME/android-sdk`)
endif

BT       := $(ANDROID_SDK)/build-tools/$(BUILD_TOOLS_VERSION)
PLATFORM := $(ANDROID_SDK)/platforms/$(PLATFORM_VERSION)/android.jar
AAPT2    := $(BT)/aapt2
D8       := $(BT)/d8
ZIPALIGN := $(BT)/zipalign
APKSIGNER:= $(BT)/apksigner

PKG_PATH := app/voltorb
SRC      := $(wildcard src/$(PKG_PATH)/*.java)
BUILD    := build
APK      := voltorb.apk

all: $(APK)

$(BUILD):
	mkdir -p $(BUILD)
clean:
	rm -rf $(BUILD) $(APK)

$(BUILD)/base.apk: AndroidManifest.xml | $(BUILD)
	$(AAPT2) link -o $@ -I $(PLATFORM) --manifest AndroidManifest.xml

$(BUILD)/classes.dex: $(SRC) | $(BUILD)
	rm -rf $(BUILD)/classes && mkdir -p $(BUILD)/classes
	javac $(JAVAC_FLAGS) -encoding UTF-8 -classpath $(PLATFORM) -d $(BUILD)/classes $(SRC)
	$(D8) --release --min-api 24 --lib $(PLATFORM) --output $(BUILD) $(BUILD)/classes/$(PKG_PATH)/*.class

$(BUILD)/unsigned.apk: $(BUILD)/base.apk $(BUILD)/classes.dex
	cp $(BUILD)/base.apk $@
	cd $(BUILD) && zip -j unsigned.apk classes.dex

debug.keystore:
	keytool -genkeypair -keystore $@ -alias androiddebugkey \
	  -storepass android -keypass android -keyalg RSA -keysize 2048 \
	  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

$(APK): $(BUILD)/unsigned.apk $(KEYSTORE)
	$(ZIPALIGN) -f 4 $(BUILD)/unsigned.apk $(BUILD)/aligned.apk
	$(APKSIGNER) sign --ks $(KEYSTORE) --ks-pass pass:android --out $@ $(BUILD)/aligned.apk
	@ls -lh $@

install: $(APK)
	$(ANDROID_SDK)/platform-tools/adb install -r $(APK)

run: install
	$(ANDROID_SDK)/platform-tools/adb shell am start -n app.voltorb/.MainActivity

.PHONY: all install run clean
