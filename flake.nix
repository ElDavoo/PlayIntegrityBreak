{
  description = "Nix development shell for PlayIntegrityBreak";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ "35.0.0" "36.0.0" "36.1.0" ];
            includeEmulator = false;
            includeNDK = false;
          };

          androidSdk = androidComposition.androidsdk;
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk21
              pkgs.git
              pkgs.which
              pkgs.android-tools
              androidSdk
            ];

            NIX_ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";

            shellHook = ''
              host_sdk="''${HOST_ANDROID_SDK_ROOT:-''${ANDROID_SDK_ROOT:-}}"
              sdk_root="$NIX_ANDROID_SDK_ROOT"

              # Prefer host SDK only when it already has the exact components this build expects.
              if [ -n "$host_sdk" ] \
                && [ -d "$host_sdk/platforms/android-36" ] \
                && [ -d "$host_sdk/build-tools/35.0.0" ]; then
                sdk_root="$host_sdk"
                echo "Using host Android SDK: $sdk_root"
              else
                echo "Using Nix Android SDK: $sdk_root"
              fi

              export JAVA_HOME=${pkgs.jdk21}
              export ANDROID_SDK_ROOT="$sdk_root"
              export ANDROID_HOME="$sdk_root"
              export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME''${GRADLE_OPTS:+ $GRADLE_OPTS}"
            '';
          };
        });
    };
}
