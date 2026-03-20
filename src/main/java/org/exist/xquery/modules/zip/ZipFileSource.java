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

import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.LockedDocument;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/**
 * Abstraction for accessing ZIP files from different sources (filesystem or database).
 */
public abstract class ZipFileSource implements Closeable {

    /**
     * Get a ZipInputStream for reading ZIP entries.
     */
    public abstract ZipInputStream getStream() throws IOException;

    /**
     * Get a raw InputStream (not wrapped in ZipInputStream) for reading the file contents.
     */
    public abstract InputStream getRawStream() throws IOException;

    /**
     * Factory method to create the appropriate source from a URI string.
     */
    public static ZipFileSource fromUri(final String href, final XQueryContext context, final Expression expr)
            throws XPathException {
        // Database path: starts with /db or xmldb:
        if (href.startsWith("/db") || href.startsWith("xmldb:")) {
            return new DatabaseSource(href, context);
        }

        // File URI
        if (href.startsWith("file:")) {
            try {
                final Path path = Path.of(new URI(href));
                if (!Files.exists(path)) {
                    throw new XPathException(expr, "ZIP file not found: " + href);
                }
                return new FileSource(path);
            } catch (final URISyntaxException e) {
                throw new XPathException(expr, "Invalid URI: " + href, e);
            }
        }

        // Try as relative path resolved against static base URI
        final String baseUri = context.getBaseURI() != null ? context.getBaseURI().getStringValue() : null;
        if (baseUri != null && baseUri.startsWith("file:")) {
            try {
                final URI resolved = new URI(baseUri).resolve(href);
                final Path path = Path.of(resolved);
                if (Files.exists(path)) {
                    return new FileSource(path);
                }
            } catch (final URISyntaxException e) {
                // Fall through
            }
        }

        // Try as absolute filesystem path
        final Path path = Path.of(href);
        if (Files.exists(path)) {
            return new FileSource(path);
        }

        throw new XPathException(expr, "Cannot resolve ZIP file URI: " + href);
    }

    /**
     * Source backed by a file on the filesystem.
     */
    private static class FileSource extends ZipFileSource {
        private final Path path;
        private InputStream currentStream;

        FileSource(final Path path) {
            this.path = path;
        }

        @Override
        public ZipInputStream getStream() throws IOException {
            close();
            currentStream = Files.newInputStream(path);
            return new ZipInputStream(currentStream);
        }

        @Override
        public InputStream getRawStream() throws IOException {
            close();
            currentStream = Files.newInputStream(path);
            return currentStream;
        }

        @Override
        public void close() {
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (final IOException e) {
                    // ignore
                }
                currentStream = null;
            }
        }
    }

    /**
     * Source backed by a binary document in the eXist-db database.
     */
    private static class DatabaseSource extends ZipFileSource {
        private final XmldbURI uri;
        private final XQueryContext context;
        private LockedDocument lockedDocument;

        DatabaseSource(final String href, final XQueryContext context) {
            if (href.startsWith("xmldb:")) {
                this.uri = XmldbURI.create(href);
            } else {
                this.uri = XmldbURI.create(href);
            }
            this.context = context;
        }

        @Override
        public ZipInputStream getStream() throws IOException {
            return new ZipInputStream(getRawStream());
        }

        @Override
        public InputStream getRawStream() throws IOException {
            close();
            try {
                final DBBroker broker = context.getBroker();
                lockedDocument = broker.getXMLResource(uri, LockMode.READ_LOCK);

                if (lockedDocument == null) {
                    throw new FileNotFoundException("Database resource not found: " + uri);
                }

                if (lockedDocument.getDocument().getResourceType() != DocumentImpl.BINARY_FILE) {
                    lockedDocument.close();
                    lockedDocument = null;
                    throw new IOException("Resource is not a binary file: " + uri);
                }

                return broker.getBinaryResource((BinaryDocument) lockedDocument.getDocument());
            } catch (final PermissionDeniedException e) {
                throw new IOException("Permission denied: " + uri, e);
            }
        }

        @Override
        public void close() {
            if (lockedDocument != null) {
                lockedDocument.close();
                lockedDocument = null;
            }
        }
    }
}
