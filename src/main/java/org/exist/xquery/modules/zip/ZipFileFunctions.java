/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery.modules.zip;

import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.collections.Collection;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.util.MimeType;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.exist.xquery.FunctionDSL.*;
import static org.exist.xquery.modules.zip.ZipModule.functionSignature;

/**
 * Implements zip:entries, zip:zip-file, zip:update-entries.
 *
 * @see <a href="http://expath.org/spec/zip">EXPath ZIP Module Specification</a>
 */
public class ZipFileFunctions extends BasicFunction {

    private static final Logger LOG = LogManager.getLogger(ZipFileFunctions.class);

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());

    private static final String FS_ENTRIES_NAME = "entries";
    static final FunctionSignature FS_ENTRIES = functionSignature(
            FS_ENTRIES_NAME,
            "Returns a zip:file element that describes the hierarchical structure of the ZIP file " +
                    "identified by $href in terms of ZIP entries.",
            returns(Type.ELEMENT),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file")
    );

    private static final String FS_ZIP_FILE_NAME = "zip-file";
    static final FunctionSignature FS_ZIP_FILE = functionSignature(
            FS_ZIP_FILE_NAME,
            "Creates a new ZIP file from the XML description element and returns the binary data.",
            returns(Type.BASE64_BINARY),
            param("zip", Type.ELEMENT, "A zip:file element describing the ZIP file to create")
    );

    private static final String FS_UPDATE_ENTRIES_NAME = "update-entries";
    static final FunctionSignature FS_UPDATE_ENTRIES = functionSignature(
            FS_UPDATE_ENTRIES_NAME,
            "Returns a copy of the ZIP file described by $zip, after replacing or adding entries " +
                    "as specified, writing the result to $output.",
            returnsNothing(),
            param("zip", Type.ELEMENT, "A zip:file element describing the updates to apply"),
            param("output", Type.ANY_URI, "The URI where the updated ZIP file should be written")
    );

    public ZipFileFunctions(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        if (isCalledAs(FS_ENTRIES_NAME)) {
            final String href = args[0].getStringValue();
            return extractEntries(href);
        } else if (isCalledAs(FS_ZIP_FILE_NAME)) {
            final Element zipDesc = (Element) ((NodeValue) args[0].itemAt(0)).getNode();
            return createZip(zipDesc);
        } else if (isCalledAs(FS_UPDATE_ENTRIES_NAME)) {
            final Element zipDesc = (Element) ((NodeValue) args[0].itemAt(0)).getNode();
            final String output = args[1].getStringValue();
            return updateEntries(zipDesc, output);
        }
        throw new XPathException(this, "Unknown function: " + getName());
    }

    private Sequence extractEntries(final String href) throws XPathException {
        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            builder.startDocument();
            builder.startElement(new QName("file", ZipModule.NAMESPACE_URI, ZipModule.PREFIX), null);
            builder.addAttribute(new QName("href", null, null), href);

            try (final ZipFileSource source = ZipFileSource.fromUri(href, context, this)) {
                final ZipInputStream zis = source.getStream();
                ZipEntry zipEntry;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (zipEntry.isDirectory()) {
                        builder.startElement(new QName("dir", ZipModule.NAMESPACE_URI, ZipModule.PREFIX), null);
                        builder.addAttribute(new QName("name", null, null), zipEntry.getName());
                        builder.endElement();
                    } else {
                        builder.startElement(new QName("entry", ZipModule.NAMESPACE_URI, ZipModule.PREFIX), null);
                        builder.addAttribute(new QName("name", null, null), zipEntry.getName());
                        if (zipEntry.getSize() >= 0) {
                            builder.addAttribute(new QName("size", null, null),
                                    Long.toString(zipEntry.getSize()));
                        }
                        if (zipEntry.getCompressedSize() >= 0) {
                            builder.addAttribute(new QName("compressed-size", null, null),
                                    Long.toString(zipEntry.getCompressedSize()));
                        }
                        if (zipEntry.getLastModifiedTime() != null) {
                            builder.addAttribute(new QName("last-modified", null, null),
                                    ISO_FORMATTER.format(
                                            Instant.ofEpochMilli(zipEntry.getLastModifiedTime().toMillis())));
                        }
                        final String method = zipEntry.getMethod() == ZipEntry.DEFLATED ? "deflated" : "stored";
                        builder.addAttribute(new QName("method", null, null), method);
                        builder.endElement();
                    }
                }
            } catch (final IOException e) {
                throw new XPathException(this, "IO error reading ZIP file: " + e.getMessage(), e);
            }

            builder.endElement();
            builder.endDocument();
            return (NodeValue) builder.getDocument().getDocumentElement();
        } finally {
            context.popDocumentContext();
        }
    }

    private Sequence createZip(final Element zipDesc) throws XPathException {
        try (final UnsynchronizedByteArrayOutputStream baos = UnsynchronizedByteArrayOutputStream.builder().get();
             final ZipOutputStream zos = new ZipOutputStream(baos)) {

            processZipChildren(zipDesc, zos, "");
            zos.finish();
            zos.flush();

            return BinaryValueFromInputStream.getInstance(context, new Base64BinaryValueType(),
                    baos.toInputStream(), this);
        } catch (final IOException e) {
            throw new XPathException(this, "IO error creating ZIP file: " + e.getMessage(), e);
        }
    }

    private void processZipChildren(final Element parent, final ZipOutputStream zos, final String pathPrefix)
            throws XPathException, IOException {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element elem = (Element) child;
            final String localName = elem.getLocalName();
            final String name = elem.getAttribute("name");

            if ("dir".equals(localName)) {
                final String dirPath = pathPrefix + name + (name.endsWith("/") ? "" : "/");
                processZipChildren(elem, zos, dirPath);
            } else if ("entry".equals(localName)) {
                final String entryPath = pathPrefix + name;
                final String method = elem.getAttribute("method");
                final String src = elem.getAttribute("src");

                final ZipEntry ze = new ZipEntry(entryPath);
                zos.putNextEntry(ze);

                if (src != null && !src.isEmpty()) {
                    writeFromSource(src, zos);
                } else {
                    writeInlineContent(elem, zos, method);
                }

                zos.closeEntry();
            }
        }
    }

    private void writeFromSource(final String src, final ZipOutputStream zos) throws XPathException, IOException {
        try (final ZipFileSource source = ZipFileSource.fromUri(src, context, this)) {
            final InputStream is = source.getRawStream();
            is.transferTo(zos);
        }
    }

    private void writeInlineContent(final Element entry, final ZipOutputStream zos, final String method)
            throws IOException {
        if ("xml".equals(method)) {
            // Serialize child XML nodes
            final StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            serializeChildren(entry, sb);
            zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            // Text content (default)
            final String textContent = entry.getTextContent();
            zos.write(textContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serializeChildren(final Element parent, final StringBuilder sb) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            serializeNode(child, sb);
        }
    }

    private void serializeNode(final Node node, final StringBuilder sb) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE:
                final Element elem = (Element) node;
                sb.append('<').append(elem.getTagName());
                // Serialize attributes
                final var attrs = elem.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    final var attr = attrs.item(i);
                    sb.append(' ').append(attr.getNodeName()).append("=\"");
                    sb.append(escapeXml(attr.getNodeValue()));
                    sb.append('"');
                }
                final NodeList children = elem.getChildNodes();
                if (children.getLength() == 0) {
                    sb.append("/>");
                } else {
                    sb.append('>');
                    for (int i = 0; i < children.getLength(); i++) {
                        serializeNode(children.item(i), sb);
                    }
                    sb.append("</").append(elem.getTagName()).append('>');
                }
                break;
            case Node.TEXT_NODE:
                sb.append(escapeXml(node.getTextContent()));
                break;
            case Node.CDATA_SECTION_NODE:
                sb.append("<![CDATA[").append(node.getTextContent()).append("]]>");
                break;
        }
    }

    private String escapeXml(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Sequence updateEntries(final Element zipDesc, final String outputUri) throws XPathException {
        final String sourceHref = zipDesc.getAttribute("href");
        if (sourceHref == null || sourceHref.isEmpty()) {
            throw new XPathException(this, "zip:file element must have an href attribute pointing to the source ZIP");
        }

        // Collect the update entries from the descriptor
        final java.util.Map<String, Element> updates = new java.util.LinkedHashMap<>();
        collectUpdateEntries(zipDesc, updates, "");

        try (final UnsynchronizedByteArrayOutputStream baos = UnsynchronizedByteArrayOutputStream.builder().get()) {
            // Read the source ZIP and write to output, applying updates
            try (final ZipFileSource source = ZipFileSource.fromUri(sourceHref, context, this);
                 final ZipOutputStream zos = new ZipOutputStream(baos)) {

                final ZipInputStream zis = source.getStream();
                final byte[] buffer = new byte[16384];
                ZipEntry ze;

                while ((ze = zis.getNextEntry()) != null) {
                    final String entryName = ze.getName();

                    if (updates.containsKey(entryName)) {
                        // Replace this entry with the update
                        final Element updateEntry = updates.remove(entryName);
                        final ZipEntry nze = new ZipEntry(entryName);
                        zos.putNextEntry(nze);
                        final String method = updateEntry.getAttribute("method");
                        writeInlineContent(updateEntry, zos, method);
                        zos.closeEntry();
                    } else {
                        // Copy existing entry
                        final ZipEntry nze = new ZipEntry(entryName);
                        zos.putNextEntry(nze);
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            zos.write(buffer, 0, bytesRead);
                        }
                        zos.closeEntry();
                    }
                }

                // Add remaining update entries as new
                for (final var entry : updates.entrySet()) {
                    final ZipEntry nze = new ZipEntry(entry.getKey());
                    zos.putNextEntry(nze);
                    final String method = entry.getValue().getAttribute("method");
                    writeInlineContent(entry.getValue(), zos, method);
                    zos.closeEntry();
                }

                zos.finish();
            }

            // Store the result at the output URI
            storeResult(outputUri, baos.toInputStream(), baos.size());

        } catch (final IOException e) {
            throw new XPathException(this, "IO error updating ZIP file: " + e.getMessage(), e);
        }

        return Sequence.EMPTY_SEQUENCE;
    }

    private void collectUpdateEntries(final Element parent, final java.util.Map<String, Element> updates,
                                      final String pathPrefix) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element elem = (Element) child;
            final String localName = elem.getLocalName();
            final String name = elem.getAttribute("name");

            if ("dir".equals(localName)) {
                final String dirPath = pathPrefix + name + (name.endsWith("/") ? "" : "/");
                collectUpdateEntries(elem, updates, dirPath);
            } else if ("entry".equals(localName)) {
                updates.put(pathPrefix + name, elem);
            }
        }
    }

    private void storeResult(final String outputUri, final InputStream data, final long size)
            throws XPathException {
        // If the URI starts with /db, store in the database
        if (outputUri.startsWith("/db")) {
            final XmldbURI uri = XmldbURI.create(outputUri);
            final XmldbURI collectionUri = uri.removeLastSegment();
            final XmldbURI resourceName = uri.lastSegment();

            try {
                final DBBroker broker = context.getBroker();
                try (final Txn txn = broker.getBrokerPool().getTransactionManager().beginTransaction()) {
                    final Collection collection = broker.getOrCreateCollection(txn, collectionUri);
                    broker.saveCollection(txn, collection);
                    collection.addBinaryResource(txn, broker, resourceName, data,
                            MimeType.BINARY_TYPE.getName(), size);
                    txn.commit();
                }
            } catch (final Exception e) {
                throw new XPathException(this, "Error storing ZIP to database: " + e.getMessage(), e);
            }
        } else {
            // Write to filesystem
            try {
                final java.net.URI uri = new java.net.URI(outputUri);
                final java.nio.file.Path path = java.nio.file.Path.of(uri);
                try (final OutputStream os = java.nio.file.Files.newOutputStream(path)) {
                    data.transferTo(os);
                }
            } catch (final Exception e) {
                throw new XPathException(this, "Error writing ZIP to filesystem: " + e.getMessage(), e);
            }
        }
    }
}
