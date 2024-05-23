# Gradle Public API Analyzer

Analyzes the public API from a Gradle API classpath.
Produces a report in Markdown format that highlights problematic API elements in the context of the provider API migration.

## Usage

```shell
./gradlew :run --args="--classpath lib1.jar --classpath lib2.jar --output report.md"
```

You can pass the JARs from the Gradle distribution, or the generated API JAR. 

## Example

```shell
./gradlew :run --args="--classpath ~/.gradle/caches/8.8-rc-1/generated-gradle-jars/gradle-api-8.8-rc-1.jar --output report.md"
```
