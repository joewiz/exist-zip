# EXPath ZIP Module for eXist-db

A drop-in replacement for the bundled EXPath ZIP module, using `java.util.zip`. Same namespace (`http://expath.org/ns/zip`), same `zip:` prefix, same function signatures — plus new functions that the bundled module never implemented.

No breaking changes. All existing `zip:*` calls continue to work as before.

## Functions

| Function | Status | Description |
|----------|--------|-------------|
| `zip:binary-entry($href, $entry)` | existing | Extract binary content from a ZIP entry |
| `zip:text-entry($href, $entry)` | existing | Extract text content (UTF-8 default) |
| `zip:text-entry($href, $entry, $encoding)` | **new** | Extract text with specified encoding |
| `zip:xml-entry($href, $entry)` | existing | Extract and parse XML from a ZIP entry |
| `zip:html-entry($href, $entry)` | existing | Extract and parse HTML from a ZIP entry |
| `zip:entries($href)` | improved | List entries as XML (now includes `@size`, `@compressed-size`, `@last-modified`) |
| `zip:zip-file($zip)` | **new** | Create a ZIP file from an XML description |
| `zip:update-entries($zip, $output)` | **new** | Update entries in an existing ZIP |

**Module namespace:** `http://expath.org/ns/zip`

## Compatibility

eXist-db bundles an older implementation of the same spec in its `expath` extension (`org.expath.exist.ZipModule`), registered in `conf.xml`. Because both modules use the same namespace, the `conf.xml` registration takes precedence and the XAR's new functions won't be available until the old registration is removed.

| eXist version | Behavior |
|---------------|----------|
| **6.x** | The bundled module's `conf.xml` registration takes precedence. Install the XAR, then remove the old registration (see below) to use new functions. |
| **7.0+** | The bundled registration will be removed from `conf.xml`. The XAR takes over automatically. |

### Enabling the new module

After installing the XAR, remove the old module from `conf.xml`:

```xml
<!-- Remove this line from conf.xml -->
<module uri="http://expath.org/ns/zip" class="org.expath.exist.ZipModule"/>
```

Then restart eXist-db. Existing code using `zip:binary-entry`, `zip:text-entry`, `zip:xml-entry`, `zip:html-entry`, and `zip:entries` will continue to work unchanged. The new functions (`zip:zip-file`, `zip:update-entries`, 3-argument `zip:text-entry`) become available.

## Install

Build the XAR (see below), then install:

```bash
xst package install target/exist-zip-0.9.0-SNAPSHOT.xar
```

## Build

Requires Java 21+ and a local build of eXist-db's `exist-core`.

```bash
mvn package -DskipTests
```

To run integration tests (requires `exist-core` in local Maven repo):

```bash
mvn test -Pintegration-tests
```

## License

LGPL 2.1 — same as eXist-db.
