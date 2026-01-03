{
  description = "Android (Kotlin) CLI devshell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "aarch64-darwin"; # or x86_64-darwin
      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      };

      # Keep this simple: include only what you need to build + adb.
      buildToolsVersion = "35.0.0"; # pick one your project uses
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];              # compileSdk can be 35 and still run on Android 16
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeNDK = false;
      };

      androidSdk = androidComposition.androidsdk;
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk17
          pkgs.gradle
          androidComposition.platform-tools  # adb
          androidSdk
        ];

        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";

        # If Gradle/aapt2 complains, this is the documented workaround in nixpkgs Android docs:
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
      };
    };
}

