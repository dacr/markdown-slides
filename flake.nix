{
  description = "markdown-slides development environment, with Claude Code or OpenCode";

  inputs = {
    nixstable.url   = "github:NixOS/nixpkgs/nixos-26.05";
    nixunstable.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixstable, nixunstable, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
    let
      stable = import nixstable {
        inherit system;
        config.allowUnfree = true;
      };
      unstable = import nixunstable {
        inherit system;
        config.allowUnfree = true;
      };

      # Lucene 10 needs java 21+, the build uses the JDK found in the environment (mill-jvm-version: system)
      jdk = stable.jdk25;

      mill = unstable.mill.override {
        jre = jdk;
      };
      scl = unstable.scala-cli.override {
        jre = jdk;
      };

      # The headless browser drawing the thumbnails and printing the PDF exports, kept apart from the libraries of the
      # session (LD_LIBRARY_PATH) : built for another glibc, they prevent it from starting
      chromium = stable.writeShellScriptBin "chromium" ''
        exec env -u LD_LIBRARY_PATH ${stable.chromium}/bin/chromium "$@"
      '';
    in
    {
      devShells.default = stable.mkShell {
        packages = [
          unstable.opencode      # The AI Agents
          unstable.claude-code

          # Scala development
          jdk                    # Java runtime
          mill                   # Build tool, keep build.mill mill-version in line (make check-mill)
          scl                    # Scripting
          stable.scalafmt        # Formatter

          # Makefile operations
          stable.gnumake
          stable.curl
          stable.jq
          stable.git
          stable.xdg-utils       # xdg-open (make open)

          # Troubleshooting
          stable.lmdb.bin        # mdb_stat, mdb_dump : inspect the database (~/.markdown-slides/database)
          stable.poppler-utils   # pdfinfo, pdftotext : check a PDF export, its bookmarks and text
          stable.imagemagick     # identify / convert : check a thumbnail
        ] ++ stable.lib.optionals stable.stdenv.isLinux [
          chromium               # Application runtime : thumbnails and PDF exports
        ];

        shellHook = ''
          # the browser of the flake, unless another one was chosen (.envrc.local)
          ${stable.lib.optionalString stable.stdenv.isLinux ''export MARKDOWN_SLIDES_BROWSER="''${MARKDOWN_SLIDES_BROWSER:-chromium}"''}
          echo "🎞️ markdown-slides dev environment loaded - make help"
        '';
      };
    }
  );
}
