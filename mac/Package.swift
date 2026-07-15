// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "RD2Mac",
    platforms: [
        .macOS(.v13) // SMAppService login item, modern Network.framework
    ],
    products: [
        .library(name: "RD2MacCore", targets: ["RD2MacCore"]),
        .executable(name: "rd2mac", targets: ["rd2mac-cli"]),
        .executable(name: "RD2MacApp", targets: ["RD2MacApp"]),
    ],
    targets: [
        // Shared networking core: protocol, HTTP server/client, Bonjour discovery.
        // Depended on by BOTH the menu-bar app and the CLI (DRY — one network stack).
        .target(name: "RD2MacCore"),

        // Menu-bar app (NSStatusItem, accessory activation). Runs the server + discovery.
        .executableTarget(
            name: "RD2MacApp",
            dependencies: ["RD2MacCore"]
        ),

        // `rd2mac` CLI invoked by Claude Code (pull-screenshot / send / ping).
        // Talks to the running app over 127.0.0.1 so it reuses the app's live peer state.
        .executableTarget(
            name: "rd2mac-cli",
            dependencies: ["RD2MacCore"]
        ),

        .testTarget(
            name: "RD2MacCoreTests",
            dependencies: ["RD2MacCore"]
        ),
    ]
)
