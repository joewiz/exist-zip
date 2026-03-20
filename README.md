# EXPath ZIP Module for eXist-db

Native implementation of the [EXPath ZIP Module](http://expath.org/spec/zip) for eXist-db, using `java.util.zip`.

This standalone XAR package replaces the ZIP functions previously bundled in the `expath` extension module, eliminating the dependency on `tools-java`.

## Functions

| Function | Description |
|----------|-------------|
| `zip:binary-entry($href, $entry)` | Extract binary content from a ZIP entry |
| `zip:text-entry($href, $entry)` | Extract text content (UTF-8 default) |
| `zip:text-entry($href, $entry, $encoding)` | Extract text with specified encoding |
| `zip:xml-entry($href, $entry)` | Extract and parse XML from a ZIP entry |
| `zip:html-entry($href, $entry)` | Extract and parse HTML from a ZIP entry |
| `zip:entries($href)` | List entries in a ZIP file as XML |
| `zip:zip-file($zip)` | Create a ZIP file from an XML description |
| `zip:update-entries($zip, $output)` | Update entries in an existing ZIP |

## Install

Download the latest `.xar` from [Releases](https://github.com/joewiz/exist-zip/releases), then install via the eXist-db Package Manager or:

```bash
xst package install exist-zip-1.0.0.xar
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
