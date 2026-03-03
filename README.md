# LiveLatex JetBrains Plugin

<!-- Plugin description -->

<h1 align="center">
    <a href="">
      <img src="https://raw.githubusercontent.com/bg-omar/LiveLatex/refs/heads/main/src/main/resources/META-INF/pluginIcon.png" width="81" height="99" alt="logo"/>
    </a><br/>
   Writing       <img src="https://raw.githubusercontent.com/bg-omar/LiveLatex/refs/heads/main/src/main/resources/icons/LaTeX_logo.png"  height="32" alt="LaTeX"/> easier.
</h1>
<h2>What is LiveLatex? 🤔 </h2>
 - LiveLatex is a JetBrains plugin that helps you write LaTeX documents faster and easier. <br>
 - It provides a real-time preview of your LaTeX code, so you can see how your document will look like as you type. <br>
 - It also includes a table creation wizard, a Tikz diagram editor, and an image insertion tool. <br>
 - LiveLatex supports most LaTeX commands and environments, including math mode, tables, figures, and more. <br>
 - It is compatible with all JetBrains IDEs that support plugins, such as IntelliJ IDEA, PyCharm, WebStorm, and more. <br>
<h2>Features 🚀 </h2>
<h4>
 - Preview LaTeX code in real-time <br>
 - Draw Tikz Diagrams and Knots click and drag <br>
 - Table Creation Wizard <br>
 - Insert images from browse <br>
 - Rightclick menu for quick access to features <br>
</h4>
<h2>Work in progress 😎 </h2> 
 - This plugin is build for my personal latex writing needs, but I hope it will be useful for you too. <br>
 - If you have any suggestions or feature requests, please open an issue on the GitHub page  <br>
 - If you like the plugin, please give it a star on GitHub and rate it on the JetBrains marketplace. <br>

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

```properties
systemProp.javax.net.ssl.trustStore=C\:\\path\\to\\your\\cacerts
systemProp.javax.net.ssl.trustStorePassword=changeit
```