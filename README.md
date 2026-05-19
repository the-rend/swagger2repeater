# Swagger to Repeater

Burp Suite extension that loads a Swagger/OpenAPI document and imports each endpoint into Repeater.

## Quick Manual — Build & Import

1. Generate a local Burp API stub JAR:

```bash
mkdir -p build-support/burp-api-stubs/classes lib
javac -d build-support/burp-api-stubs/classes build-support/burp-api-stubs/src/burp/*.java
jar cf lib/burp-extender-api.jar -C build-support/burp-api-stubs/classes .
```

2. Build the extension's jar:

```bash
mvn -DskipTests package
```

3. In Burp Suite (Extensions → Installed → Add): select the generated shaded jar in `target/` (`target/swagger2repeater-0.1.0-SNAPSHOT.jar`).

4. Open a Swagger/OpenAPI JSON/YAML; the extension lists endpoints on the left.

5. Select endpoints and click `Send to Repeater` to import them; add any custom headers in the Headers panel before sending.
