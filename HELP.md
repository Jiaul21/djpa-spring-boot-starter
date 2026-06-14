# DJPA Generic Helper

Single Maven library jar for generic JPA helper APIs and `@GenerateFields` annotation processing.

## Build

```powershell
.\mvnw.cmd clean verify
```

The jar is created at:

```text
target/djpa-generic-helper-1.0.0.jar
```

Install it into your local Maven repository:

```powershell
.\mvnw.cmd clean install
```

## Maven Usage

Add the repository in pom file:

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```
Add the library as a normal dependency:

```xml
<dependency>
    <groupId>com.github.Jiaul21</groupId>
    <artifactId>djpa-generic-helper</artifactId>
    <version>v1.0.0</version>        <!-- ddsdn-->
</dependency>
```

Use the same artifact as the annotation processor:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>com.github.Jiaul21</groupId>
        <artifactId>djpa-generic-helper</artifactId>
        <version>v1.0.0</version>
    </path>
</annotationProcessorPaths>
```

## Annotation Usage

```java
import com.djpa.annotations.GenerateFields;

@GenerateFields
public class App {
    private Long id;
    private String name;
}
```

After compilation, the processor generates `AppFields` in the same package.
