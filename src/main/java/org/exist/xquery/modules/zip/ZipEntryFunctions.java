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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.dom.QName;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.modules.ModuleUtils;
import org.exist.xquery.value.*;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.exist.xquery.FunctionDSL.*;
import static org.exist.xquery.modules.zip.ZipModule.functionSignature;

/**
 * Implements zip:binary-entry, zip:text-entry, zip:xml-entry, zip:html-entry.
 *
 * @see <a href="http://expath.org/spec/zip">EXPath ZIP Module Specification</a>
 */
public class ZipEntryFunctions extends BasicFunction {

    private static final Logger LOG = LogManager.getLogger(ZipEntryFunctions.class);

    private static final String FS_BINARY_ENTRY_NAME = "binary-entry";
    static final FunctionSignature FS_BINARY_ENTRY = functionSignature(
            FS_BINARY_ENTRY_NAME,
            "Extracts the binary stream from the file positioned at $entry within the ZIP file " +
                    "identified by $href and returns it as a Base64 item.",
            returns(Type.BASE64_BINARY),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file"),
            param("entry", Type.STRING, "The entry within the ZIP file to address")
    );

    private static final String FS_HTML_ENTRY_NAME = "html-entry";
    static final FunctionSignature FS_HTML_ENTRY = functionSignature(
            FS_HTML_ENTRY_NAME,
            "Extracts the HTML file positioned at $entry within the ZIP file identified by $href, " +
                    "and returns a document node.",
            returns(Type.DOCUMENT),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file"),
            param("entry", Type.STRING, "The entry within the ZIP file to address")
    );

    private static final String FS_TEXT_ENTRY_NAME = "text-entry";
    static final FunctionSignature FS_TEXT_ENTRY = functionSignature(
            FS_TEXT_ENTRY_NAME,
            "Extracts the contents of the text file positioned at $entry within the ZIP file " +
                    "identified by $href and returns it as a string.",
            returns(Type.STRING),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file"),
            param("entry", Type.STRING, "The entry within the ZIP file to address")
    );

    static final FunctionSignature FS_TEXT_ENTRY_ENCODING = functionSignature(
            FS_TEXT_ENTRY_NAME,
            "Extracts the contents of the text file positioned at $entry within the ZIP file " +
                    "identified by $href and returns it as a string, using the specified encoding.",
            returns(Type.STRING),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file"),
            param("entry", Type.STRING, "The entry within the ZIP file to address"),
            param("encoding", Type.STRING, "The character encoding of the text file")
    );

    private static final String FS_XML_ENTRY_NAME = "xml-entry";
    static final FunctionSignature FS_XML_ENTRY = functionSignature(
            FS_XML_ENTRY_NAME,
            "Extracts the content from the XML file positioned at $entry within the ZIP file " +
                    "identified by $href and returns it as a document-node.",
            returns(Type.DOCUMENT),
            param("href", Type.ANY_URI, "The URI for locating the ZIP file"),
            param("entry", Type.STRING, "The entry within the ZIP file to address")
    );

    public ZipEntryFunctions(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final String href = args[0].getStringValue();
        final String entryName = args[1].getStringValue();

        final ZipFileSource source = ZipFileSource.fromUri(href, context, this);
        boolean mustClose = true;
        try {
            final ZipInputStream zis = source.getStream();
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.getName().equals(entryName)) {
                    if (isCalledAs(FS_BINARY_ENTRY_NAME)) {
                        // BinaryValueFromInputStream takes ownership of the stream
                        mustClose = false;
                        return extractBinaryEntry(zis);
                    } else if (isCalledAs(FS_HTML_ENTRY_NAME)) {
                        return extractHtmlEntry(zis);
                    } else if (isCalledAs(FS_TEXT_ENTRY_NAME)) {
                        final Charset charset = getCharset(args);
                        return extractStringEntry(zis, charset);
                    } else if (isCalledAs(FS_XML_ENTRY_NAME)) {
                        return extractXmlEntry(zis);
                    }
                }
            }
        } catch (final IOException e) {
            throw new XPathException(this, "IO error reading ZIP file: " + e.getMessage(), e);
        } finally {
            if (mustClose) {
                try {
                    source.close();
                } catch (final IOException ioe) {
                    LOG.warn("Error closing ZIP source: {}", ioe.getMessage(), ioe);
                }
            }
        }

        throw new XPathException(this, "ZIP entry not found: " + entryName);
    }

    private Charset getCharset(final Sequence[] args) throws XPathException {
        if (args.length > 2 && !args[2].isEmpty()) {
            final String encoding = args[2].getStringValue();
            try {
                return Charset.forName(encoding);
            } catch (final UnsupportedCharsetException e) {
                throw new XPathException(this, "Unsupported encoding: " + encoding, e);
            }
        }
        return UTF_8;
    }

    private BinaryValue extractBinaryEntry(final ZipInputStream zis) throws XPathException {
        return BinaryValueFromInputStream.getInstance(context, new Base64BinaryValueType(), zis, this);
    }

    private StringValue extractStringEntry(final ZipInputStream zis, final Charset charset) throws XPathException, IOException {
        final char[] buf = new char[4096];
        final StringBuilder builder = new StringBuilder();
        try (final Reader reader = new InputStreamReader(zis, charset)) {
            int read;
            while ((read = reader.read(buf)) > -1) {
                builder.append(buf, 0, read);
            }
        }
        return new StringValue(this, builder.toString());
    }

    private org.exist.dom.memtree.DocumentImpl extractHtmlEntry(final ZipInputStream zis) throws XPathException {
        try {
            return ModuleUtils.htmlToXHtml(context, new StreamSource(zis), null, null, this);
        } catch (final SAXException | IOException e) {
            throw new XPathException(this, "Error parsing HTML entry: " + e.getMessage(), e);
        }
    }

    private NodeValue extractXmlEntry(final ZipInputStream zis) throws XPathException {
        try {
            return ModuleUtils.streamToXML(context, zis, this);
        } catch (final SAXException | IOException e) {
            throw new XPathException(this, "Error parsing XML entry: " + e.getMessage(), e);
        }
    }
}
