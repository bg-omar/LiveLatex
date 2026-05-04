# LiveLatex JetBrains Plugin
<!-- Plugin description -->
**LiveLatex** is a little JetBrains sidekick for LaTeX: you type, you peek at a live HTML preview, and you don’t have to context-switch to a PDF every two minutes.

[<img src="https://raw.githubusercontent.com/bg-omar/LiveLatex/refs/heads/main/src/main/resources/META-INF/pluginIcon-64px.png?raw=true"/>]()

Writing [<img src="https://raw.githubusercontent.com/bg-omar/LiveLatex/refs/heads/main/src/main/resources/icons/LaTeX_logo-64px.png?raw=true"/>]() with fewer tab hops.

### What’s the idea? 🤔
- Open the **LaTeX Preview** tool window and watch the document update as you go (math, tables, figures — most everyday stuff works).
- **TikZ** gets extra love: there’s a click-and-drag canvas (knots and diagrams), and you can turn **LiveRender** on when you want TikZ compiled in the preview, or leave it off if you’d rather keep the IDE snappy.
- Tables, **insert image**, **\ref / \cite** from the right-click menu, and a shortcut to yank a section into its own file — basically the shortcuts I got tired of typing by hand.
- Runs anywhere JetBrains runs plugins (IDEA, PyCharm, WebStorm, …).

### Cool bits 🚀
- Live preview next to your editor  
- TikZ canvas (draw, knots, export) + optional LiveRender for TikZ in the preview  
- Table wizard + image insert  
- Zoom, jump to section, and preview options from the tool window  

### Honest disclaimer 😎
I built this for my own LaTeX workflow first — if it helps you too, great. Issues and ideas welcome on GitHub; stars and marketplace ratings always appreciated.
<!-- Plugin description end -->

## Build troubleshooting

### "PKIX path building failed" / "unable to find valid certification path"

This means your JVM does not trust the SSL certificate for Gradle’s servers (common behind corporate proxies or custom CAs).

**Option A – Use IntelliJ’s JDK for Gradle (simplest)**  
In IntelliJ: **File → Settings → Build, Execution, Deployment → Build Tools → Gradle**. Set **Gradle JVM** to **JetBrains Runtime** (or another JDK that can access the internet). Then sync/rebuild.

**Option B – Import the CA certificate into your JDK**  
1. Find your JDK’s `cacerts` (e.g. `C:\Program Files\Java\jdk-21\lib\security\cacerts`).  
2. Export the root/CA certificate your proxy or company uses (e.g. from browser: lock icon → Certificate → export).  
3. Import it:  
   `keytool -import -alias gradle -keystore "C:\path\to\jdk\lib\security\cacerts" -file your-ca.cer`  
   Default password is `changeit`.  
4. Restart the Gradle daemon: `.\gradlew --stop`, then sync again.

**Option C – Point Gradle at a custom truststore**  
If you use a separate truststore file, add to `gradle.properties` (with your paths):