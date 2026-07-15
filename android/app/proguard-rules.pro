# RD2Mac is sideloaded and does not minify (release keeps isMinifyEnabled=false).
# Keep NanoHTTPD if minification is ever enabled.
-keep class fi.iki.elonen.** { *; }
-keep class net.simioni.rd2mac.** { *; }
